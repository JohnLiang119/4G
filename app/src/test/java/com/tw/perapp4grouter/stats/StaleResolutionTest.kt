package com.tw.perapp4grouter.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 驗證延遲回來的解析結果無法重新標示已關閉的 flow，也不會污染新的 VPN session。
 */
class StaleResolutionTest {

    private val appA = AppIdentity.Resolved(10101, "com.example.a", "APP A")

    private fun key(generation: Long) =
        FlowKey(generation, FlowProtocol.TCP, "10.0.0.2", 40000, "93.184.216.34", 443)

    @Test
    fun delayedResultCannotRelabelAClosedFlow() {
        val tracker = ConnectionStatsTracker(1L, listOf("com.example.a"), FakeMonotonicClock())
        val flowKey = key(1L)
        tracker.open(flowKey)
        tracker.close(flowKey)

        assertFalse("已關閉的 flow 不得被延遲結果復活", tracker.resolve(flowKey, appA))
        assertNull(tracker.recordOf(flowKey))
        assertEquals(0, tracker.snapshot().totalFlows)
    }

    @Test
    fun delayedResultFromAnOldSessionCannotTouchTheNewSession() {
        val oldSession = ConnectionStatsTracker(1L, listOf("com.example.a"), FakeMonotonicClock())
        oldSession.open(key(1L))

        // 新 session 建立，舊 session 的 worker 才回傳結果
        val newSession = ConnectionStatsTracker(2L, listOf("com.example.a"), FakeMonotonicClock())
        newSession.open(key(2L))

        assertFalse(newSession.resolve(key(1L), appA))
        assertFalse(newSession.close(key(1L)))
        assertFalse(newSession.touch(key(1L)))

        assertEquals("新 session 統計不得被舊事件扣減", 1, newSession.activeFlowCount)
        assertEquals(AppIdentity.Unknown, newSession.recordOf(key(2L))?.identity)
    }

    @Test
    fun aResolvedFlowThatClosesBeforeTheResultArrivesStaysRemoved() {
        val tracker = ConnectionStatsTracker(5L, listOf("com.example.a"), FakeMonotonicClock())
        val flowKey = key(5L)
        tracker.open(flowKey)
        assertTrue(tracker.resolve(flowKey, appA))
        tracker.close(flowKey)
        assertFalse(tracker.resolve(flowKey, appA))
        assertTrue(tracker.snapshot().isEmpty)
    }

    @Test
    fun resolutionAfterResetDoesNotRecreateRecords() {
        val tracker = ConnectionStatsTracker(3L, listOf("com.example.a"), FakeMonotonicClock())
        val flowKey = key(3L)
        tracker.open(flowKey)
        tracker.reset()
        assertFalse(tracker.resolve(flowKey, appA))
        assertEquals(0, tracker.activeFlowCount)
    }
}
