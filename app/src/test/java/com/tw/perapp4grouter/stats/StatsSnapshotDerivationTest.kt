package com.tw.perapp4grouter.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 驗證由追蹤器紀錄推導出的快照恆滿足加總不變量。
 */
class StatsSnapshotDerivationTest {

    private val clock = FakeMonotonicClock()
    private val tracker = ConnectionStatsTracker(1L, listOf("com.example.a", "com.example.b"), clock)

    private val appA = AppIdentity.Resolved(10101, "com.example.a", "APP A")
    private val appB = AppIdentity.Resolved(10102, "com.example.b", "APP B")
    private val shared = AppIdentity.SharedUid(10500, listOf("com.example.c", "com.example.d"), listOf("APP C", "APP D"))

    private var nextPort = 40000

    private fun openFlow(protocol: FlowProtocol, identity: AppIdentity): FlowKey {
        val key = FlowKey(1L, protocol, "10.0.0.2", nextPort++, "93.184.216.34", 443)
        tracker.open(key)
        if (identity != AppIdentity.Unknown) {
            tracker.resolve(key, identity)
        }
        return key
    }

    private fun assertInvariants(snapshot: StatsSnapshot) {
        for (row in snapshot.rows) {
            assertEquals("每列合計必須等於 TCP + UDP", row.activeTcp + row.activeUdp, row.total)
            assertTrue(row.activeTcp >= 0 && row.activeUdp >= 0)
        }
        assertEquals(snapshot.rows.sumOf { it.activeTcp }, snapshot.totalTcp)
        assertEquals(snapshot.rows.sumOf { it.activeUdp }, snapshot.totalUdp)
        assertEquals("全域總數必須等於所有分組加總", snapshot.rows.sumOf { it.total }, snapshot.totalFlows)
    }

    @Test
    fun twoAppsWithDifferentUidsAreCountedSeparately() {
        openFlow(FlowProtocol.TCP, appA)
        openFlow(FlowProtocol.TCP, appA)
        openFlow(FlowProtocol.TCP, appB)

        val snapshot = tracker.snapshot()
        assertEquals(2, snapshot.rows.first { it.identity == appA }.total)
        assertEquals(1, snapshot.rows.first { it.identity == appB }.total)
        assertEquals(3, snapshot.totalFlows)
        assertInvariants(snapshot)
    }

    @Test
    fun tcpAndUdpOfSameAppAggregateIntoOneRow() {
        repeat(2) { openFlow(FlowProtocol.TCP, appA) }
        repeat(3) { openFlow(FlowProtocol.UDP, appA) }

        val snapshot = tracker.snapshot()
        assertEquals(1, snapshot.rows.size)
        val row = snapshot.rows.single()
        assertEquals(2, row.activeTcp)
        assertEquals(3, row.activeUdp)
        assertEquals(5, row.total)
        assertInvariants(snapshot)
    }

    @Test
    fun sharedUidFlowsAppearExactlyOnceInOneGroup() {
        repeat(3) { openFlow(FlowProtocol.TCP, shared) }

        val snapshot = tracker.snapshot()
        assertEquals("共用 UID 必須聚合成單一分組", 1, snapshot.rows.size)
        assertEquals(3, snapshot.rows.single().total)
        assertEquals("不得複製到多個 APP", 3, snapshot.totalFlows)
        assertInvariants(snapshot)
    }

    @Test
    fun unknownFlowsRemainCountableInTheGlobalTotal() {
        openFlow(FlowProtocol.TCP, appA)
        openFlow(FlowProtocol.UDP, AppIdentity.Unknown)
        openFlow(FlowProtocol.TCP, AppIdentity.Unknown)

        val snapshot = tracker.snapshot()
        val unknownRow = snapshot.rows.first { it.identity == AppIdentity.Unknown }
        assertEquals(2, unknownRow.total)
        assertEquals(3, snapshot.totalFlows)
        assertInvariants(snapshot)
    }

    @Test
    fun mixedResolvedSharedAndUnknownGroupsStillReconcile() {
        repeat(9) { openFlow(FlowProtocol.TCP, appA) }
        repeat(4) { openFlow(FlowProtocol.UDP, appB) }
        repeat(2) { openFlow(FlowProtocol.UDP, shared) }
        openFlow(FlowProtocol.TCP, AppIdentity.Unknown)

        val snapshot = tracker.snapshot()
        assertEquals(4, snapshot.activeAppCount)
        assertEquals(16, snapshot.totalFlows)
        assertInvariants(snapshot)
    }

    @Test
    fun rowsAreOrderedByTotalDescending() {
        repeat(2) { openFlow(FlowProtocol.TCP, appA) }
        repeat(9) { openFlow(FlowProtocol.TCP, appB) }

        val snapshot = tracker.snapshot()
        assertEquals(appB, snapshot.rows[0].identity)
        assertEquals(appA, snapshot.rows[1].identity)
    }

    @Test
    fun emptyTrackerProducesEmptySnapshot() {
        val snapshot = tracker.snapshot()
        assertTrue(snapshot.isEmpty)
        assertEquals(0, snapshot.activeAppCount)
        assertEquals(0, snapshot.totalFlows)
        assertInvariants(snapshot)
    }

    @Test
    fun snapshotCarriesGenerationAndAppliedPackages() {
        val snapshot = tracker.snapshot()
        assertEquals(1L, snapshot.generation)
        assertEquals(listOf("com.example.a", "com.example.b"), snapshot.appliedPackages)
    }
}
