package com.tw.perapp4grouter.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 驗證非同步擁有者解析：每條 flow 只查一次、佇列滿載時退化為未知，且從不阻塞呼叫端。
 */
class OwnerResolutionDispatcherTest {

    private val appliedIndex = AppliedAppIndex.build(listOf(AppliedApp("com.example.a", 10101, "APP A")))

    private fun key(sourcePort: Int) =
        FlowKey(1L, FlowProtocol.TCP, "10.0.0.2", sourcePort, "93.184.216.34", 443)

    private class CountingLookup(
        private val uid: Int = 10101,
        private val gate: CountDownLatch? = null
    ) : ConnectionOwnerLookup {
        val callsPerLocalPort = ConcurrentHashMap<Int, AtomicInteger>()

        override fun lookupUid(ipProtocolNumber: Int, local: InetSocketAddress, remote: InetSocketAddress): Int {
            callsPerLocalPort.computeIfAbsent(local.port) { AtomicInteger() }.incrementAndGet()
            gate?.await(10, TimeUnit.SECONDS)
            return uid
        }
    }

    @Test
    fun eachFlowIsResolvedOnceAndTheResultIsDelivered() {
        val lookup = CountingLookup()
        val results = ConcurrentHashMap<FlowKey, AppIdentity>()
        val dispatcher = OwnerResolutionDispatcher(
            ConnectionOwnerResolver(lookup, appliedIndex),
            { flowKey, identity -> results[flowKey] = identity }
        )
        try {
            assertTrue(dispatcher.submit(key(40000)))
            assertTrue(dispatcher.awaitQuiescence(10_000L))
            assertEquals(AppIdentity.Resolved(10101, "com.example.a", "APP A"), results[key(40000)])
            assertEquals(1, lookup.callsPerLocalPort[40000]?.get())
        } finally {
            dispatcher.shutdown()
        }
    }

    @Test
    fun duplicateFlowPacketsScheduleOnlyOneLookup() {
        val gate = CountDownLatch(1)
        val lookup = CountingLookup(gate = gate)
        val dispatcher = OwnerResolutionDispatcher(
            ConnectionOwnerResolver(lookup, appliedIndex),
            { _, _ -> }
        )
        try {
            assertTrue(dispatcher.submit(key(40000)))
            // 同一條 flow 的重複封包在第一次查詢完成前不得重複排程
            repeat(20) { assertFalse(dispatcher.submit(key(40000))) }
            assertEquals(1, dispatcher.submittedCount)
            gate.countDown()
            assertTrue(dispatcher.awaitQuiescence(10_000L))
            assertEquals(1, lookup.callsPerLocalPort[40000]?.get())
        } finally {
            gate.countDown()
            dispatcher.shutdown()
        }
    }

    @Test
    fun queueRejectionLeavesFlowsUnknownAndNeverBlocksTheCaller() {
        val gate = CountDownLatch(1)
        val lookup = CountingLookup(gate = gate)
        val resolved = ConcurrentHashMap<FlowKey, AppIdentity>()
        val tracker = ConnectionStatsTracker(1L, listOf("com.example.a"), FakeMonotonicClock())
        val dispatcher = OwnerResolutionDispatcher(
            resolver = ConnectionOwnerResolver(lookup, appliedIndex),
            onResolved = { flowKey, identity ->
                resolved[flowKey] = identity
                tracker.resolve(flowKey, identity)
            },
            queueCapacity = 4,
            threadCount = 1
        )
        try {
            val flowCount = 500
            val startedAt = System.nanoTime()
            for (port in 0 until flowCount) {
                val flowKey = key(40000 + port)
                // 轉送端一律先開 flow 再嘗試排程，排程失敗不影響轉送
                assertTrue(tracker.open(flowKey))
                dispatcher.submit(flowKey)
            }
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L

            assertTrue("提交路徑不得阻塞封包轉送，實際耗時 ${elapsedMillis}ms", elapsedMillis < 5_000L)
            assertTrue("佇列滿載時必須有提交被拒絕", dispatcher.rejectedCount > 0)
            assertEquals("所有 flow 都必須保留在統計中", flowCount, tracker.activeFlowCount)

            val snapshot = tracker.snapshot()
            assertEquals(flowCount, snapshot.totalFlows)
            val unknownRow = snapshot.rows.firstOrNull { it.identity == AppIdentity.Unknown }
            assertTrue("被拒絕的 flow 必須維持在未知 APP 分組", (unknownRow?.total ?: 0) > 0)
        } finally {
            gate.countDown()
            dispatcher.shutdown()
        }
    }

    @Test
    fun submissionsAfterShutdownAreIgnored() {
        val dispatcher = OwnerResolutionDispatcher(
            ConnectionOwnerResolver(CountingLookup(), appliedIndex),
            { _, _ -> }
        )
        dispatcher.shutdown()
        assertFalse(dispatcher.submit(key(40000)))
        assertEquals(0, dispatcher.submittedCount)
    }
}
