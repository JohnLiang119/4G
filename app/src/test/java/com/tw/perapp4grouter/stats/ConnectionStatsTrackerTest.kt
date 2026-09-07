package com.tw.perapp4grouter.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 驗證追蹤器的 generation 檢核與冪等生命週期操作。
 */
class ConnectionStatsTrackerTest {

    private val clock = FakeMonotonicClock()
    private val tracker = ConnectionStatsTracker(
        generation = 7L,
        appliedPackages = listOf("com.example.a"),
        clock = clock
    )

    private fun key(
        generation: Long = 7L,
        protocol: FlowProtocol = FlowProtocol.TCP,
        sourcePort: Int = 40000,
        destinationPort: Int = 443
    ) = FlowKey(generation, protocol, "10.0.0.2", sourcePort, "93.184.216.34", destinationPort)

    @Test
    fun openInsertsExactlyOncePerFlow() {
        assertTrue(tracker.open(key()))
        assertEquals(1, tracker.activeFlowCount)
    }

    @Test
    fun duplicateOpenDoesNotIncreaseCount() {
        tracker.open(key())
        assertFalse("重複 SYN 不得重複計數", tracker.open(key()))
        assertFalse(tracker.open(key()))
        assertEquals(1, tracker.activeFlowCount)
    }

    @Test
    fun duplicateCloseRemovesOnlyOnce() {
        tracker.open(key())
        assertTrue(tracker.close(key()))
        assertFalse("重複終止事件不得重複扣除", tracker.close(key()))
        assertEquals(0, tracker.activeFlowCount)
    }

    @Test
    fun countsNeverGoNegativeWhenClosingUnknownFlows() {
        assertFalse(tracker.close(key()))
        assertFalse(tracker.close(key(sourcePort = 50000)))
        assertEquals(0, tracker.activeFlowCount)
        assertTrue(tracker.snapshot().totalFlows >= 0)
        assertEquals(0, tracker.snapshot().totalFlows)
    }

    @Test
    fun staleGenerationEventsAreIgnored() {
        val staleKey = key(generation = 6L)
        assertFalse(tracker.open(staleKey))
        assertEquals(0, tracker.activeFlowCount)

        tracker.open(key())
        assertFalse(tracker.close(key(generation = 6L)))
        assertFalse(tracker.touch(key(generation = 6L)))
        assertFalse(tracker.resolve(key(generation = 6L), AppIdentity.Resolved(1, "p", "L")))
        assertEquals("舊 session 事件不得影響新 session", 1, tracker.activeFlowCount)
    }

    @Test
    fun resolveOnlyAppliesToStillActiveFlows() {
        val identity = AppIdentity.Resolved(10123, "com.example.a", "APP A")
        assertFalse("尚未建立的 flow 不得被標示", tracker.resolve(key(), identity))

        tracker.open(key())
        assertTrue(tracker.resolve(key(), identity))
        assertEquals(identity, tracker.recordOf(key())?.identity)

        tracker.close(key())
        assertFalse("已關閉的 flow 不得被重新標示", tracker.resolve(key(), identity))
        assertNull(tracker.recordOf(key()))
    }

    @Test
    fun newFlowStartsAsUnknown() {
        tracker.open(key())
        assertEquals(AppIdentity.Unknown, tracker.recordOf(key())?.identity)
    }

    @Test
    fun touchRefreshesLastActivityWithoutChangingCount() {
        tracker.open(key(protocol = FlowProtocol.UDP))
        clock.advanceBy(5_000L)
        assertTrue(tracker.touch(key(protocol = FlowProtocol.UDP)))
        assertEquals(5_000L, tracker.recordOf(key(protocol = FlowProtocol.UDP))?.lastActivityAtMillis)
        assertEquals(1, tracker.activeFlowCount)
    }

    @Test
    fun resetClearsAllSessionMetadata() {
        tracker.open(key())
        tracker.open(key(sourcePort = 40001))
        tracker.reset()
        assertEquals(0, tracker.activeFlowCount)
        assertTrue(tracker.snapshot().isEmpty)
    }
}
