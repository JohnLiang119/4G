package com.tw.perapp4grouter.stats

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/**
 * 驗證統計 session 的生命週期：每個成功建立的 session 都從零開始，
 * 停止時清除所有狀態，且執行期間的身分綁定在不可變的已套用清單上。
 */
class VpnStatsSessionTest {

    private val appA = AppliedApp("com.example.a", 10101, "APP A")
    private val appB = AppliedApp("com.example.b", 10102, "APP B")
    private val appC = AppliedApp("com.example.c", 10103, "APP C")

    private class UidMapLookup(private val uidByPort: Map<Int, Int>) : ConnectionOwnerLookup {
        override fun lookupUid(ipProtocolNumber: Int, local: InetSocketAddress, remote: InetSocketAddress): Int =
            uidByPort[local.port] ?: INVALID_UID
    }

    @Before
    fun setUp() {
        FlowObservers.set(null)
        StatsSnapshotRepository.clear()
    }

    @After
    fun tearDown() {
        FlowObservers.set(null)
        StatsSnapshotRepository.clear()
    }

    private fun session(
        applied: List<AppliedApp>,
        uidByPort: Map<Int, Int> = emptyMap(),
        clock: MonotonicClock = FakeMonotonicClock()
    ) = VpnStatsSession(
        generation = VpnStatsSession.nextGeneration(),
        appliedAppIndex = AppliedAppIndex.build(applied),
        ownerLookup = UidMapLookup(uidByPort),
        clock = clock
    )

    private fun openTcpFlow(session: VpnStatsSession, sourcePort: Int): FlowKey {
        val key = session.keyFor(FlowProtocol.TCP, "10.0.0.2", sourcePort, "93.184.216.34", 443)
        session.onFlowOpened(key)
        return key
    }

    @Test
    fun eachSuccessfulSessionGetsAFreshGeneration() {
        val first = session(listOf(appA))
        val second = session(listOf(appA))
        assertNotEquals(first.generation, second.generation)
        assertTrue(second.generation > first.generation)
        first.shutdown()
        second.shutdown()
    }

    @Test
    fun aNewSessionStartsAtZeroAndPublishesAnEmptySnapshot() {
        val session = session(listOf(appA, appB))
        try {
            session.publishEmpty()
            val snapshot = StatsSnapshotRepository.latest()
            assertEquals(0, snapshot.totalFlows)
            assertEquals(0, snapshot.activeAppCount)
            assertEquals(session.generation, snapshot.generation)
            assertEquals(listOf("com.example.a", "com.example.b"), snapshot.appliedPackages)
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun stoppingASessionClearsTrackerAndPublishedSnapshot() {
        val session = session(listOf(appA))
        openTcpFlow(session, 40000)
        openTcpFlow(session, 40001)
        session.publish()
        assertEquals(2, StatsSnapshotRepository.latest().totalFlows)

        session.shutdown()
        StatsSnapshotRepository.clear()

        assertEquals("停止後不得保留任何 flow metadata", 0, session.tracker.activeFlowCount)
        assertEquals(0, StatsSnapshotRepository.latest().totalFlows)
    }

    @Test
    fun restartingAfterActiveFlowsBeginsFromZero() {
        val first = session(listOf(appA))
        openTcpFlow(first, 40000)
        openTcpFlow(first, 40001)
        first.publish()
        assertEquals(2, StatsSnapshotRepository.latest().totalFlows)

        first.shutdown()
        StatsSnapshotRepository.clear()

        val second = session(listOf(appA))
        try {
            second.publishEmpty()
            assertEquals("新 session 必須從零開始", 0, StatsSnapshotRepository.latest().totalFlows)
            assertNotEquals(first.generation, StatsSnapshotRepository.latest().generation)
        } finally {
            second.shutdown()
        }
    }

    @Test
    fun aStoppedSessionIgnoresLateFlowEventsFromItsOwnWorkers() {
        val session = session(listOf(appA))
        val key = openTcpFlow(session, 40000)
        session.shutdown()

        session.onFlowOpened(key)
        session.onFlowActivity(key)
        assertEquals("已關閉的 session 不得再累積統計", 0, session.tracker.activeFlowCount)
    }

    @Test
    fun runningSessionLabelsStayBoundToTheAppliedSelection() {
        // 已套用 A + B，執行期間把草稿選擇改成 A + C 不得改變既有標示
        val running = session(listOf(appA, appB), uidByPort = mapOf(40000 to 10101, 40001 to 10102))
        try {
            openTcpFlow(running, 40000)
            openTcpFlow(running, 40001)
            waitForResolution(running, expectedResolvedRows = 2)

            val snapshot = running.snapshot()
            assertEquals(listOf("com.example.a", "com.example.b"), snapshot.appliedPackages)
            assertEquals(setOf("APP A", "APP B"), snapshot.rows.map { it.label }.toSet())
            assertEquals(2, snapshot.totalFlows)

            // APP C 的 UID 不在已套用索引內，只會落到未知分組，絕不會被標成 APP C
            assertTrue(running.appliedAppIndex.identityFor(appC.uid) == AppIdentity.Unknown)
        } finally {
            running.shutdown()
        }
    }

    @Test
    fun applyingANewSelectionStartsACleanSessionWithTheNewLabels() {
        val old = session(listOf(appA, appB), uidByPort = mapOf(40000 to 10101, 40001 to 10102))
        openTcpFlow(old, 40000)
        openTcpFlow(old, 40001)
        waitForResolution(old, expectedResolvedRows = 2)
        old.shutdown()
        StatsSnapshotRepository.clear()

        val rebuilt = session(listOf(appA, appC), uidByPort = mapOf(40000 to 10101, 40002 to 10103))
        try {
            rebuilt.publishEmpty()
            assertEquals("重建後必須歸零", 0, StatsSnapshotRepository.latest().totalFlows)
            assertEquals(listOf("com.example.a", "com.example.c"), rebuilt.snapshot().appliedPackages)

            openTcpFlow(rebuilt, 40000)
            openTcpFlow(rebuilt, 40002)
            waitForResolution(rebuilt, expectedResolvedRows = 2)

            assertEquals(setOf("APP A", "APP C"), rebuilt.snapshot().rows.map { it.label }.toSet())
            assertEquals("APP B 不在新的已套用清單中", AppIdentity.Unknown, rebuilt.appliedAppIndex.identityFor(appB.uid))
        } finally {
            rebuilt.shutdown()
        }
    }

    @Test
    fun flowsFromUidsOutsideTheAppliedSetRemainUnknown() {
        val session = session(listOf(appA), uidByPort = mapOf(40000 to 19999))
        try {
            openTcpFlow(session, 40000)
            Thread.sleep(200L)
            val snapshot = session.snapshot()
            assertEquals(1, snapshot.totalFlows)
            assertEquals(AppIdentity.Unknown, snapshot.rows.single().identity)
        } finally {
            session.shutdown()
        }
    }

    private fun waitForResolution(session: VpnStatsSession, expectedResolvedRows: Int) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            val resolved = session.snapshot().rows.count { it.identity != AppIdentity.Unknown }
            if (resolved >= expectedResolvedRows) return
            Thread.sleep(5L)
        }
        throw AssertionError("等待擁有者解析逾時")
    }
}
