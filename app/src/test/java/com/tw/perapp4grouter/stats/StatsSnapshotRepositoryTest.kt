package com.tw.perapp4grouter.stats

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 驗證快照發布是整份原子替換，讀者永遠看不到半更新的內容。
 */
class StatsSnapshotRepositoryTest {

    @Before
    fun setUp() = StatsSnapshotRepository.clear()

    @After
    fun tearDown() = StatsSnapshotRepository.clear()

    private fun snapshotOf(generation: Long, tcp: Int, udp: Int): StatsSnapshot {
        val identity = AppIdentity.Resolved(10101, "com.example.a", "APP A")
        return StatsSnapshot(
            generation = generation,
            appliedPackages = listOf("com.example.a"),
            rows = listOf(AppStatsRow(identity, tcp, udp, generation)),
            totalTcp = tcp,
            totalUdp = udp,
            publishedAtMillis = generation
        )
    }

    @Test
    fun latestReturnsTheMostRecentlyPublishedSnapshot() {
        val snapshot = snapshotOf(1L, 3, 4)
        StatsSnapshotRepository.publish(snapshot)
        assertSame(snapshot, StatsSnapshotRepository.latest())
        assertEquals(7, StatsSnapshotRepository.latest().totalFlows)
    }

    @Test
    fun clearResetsToTheEmptySnapshot() {
        StatsSnapshotRepository.publish(snapshotOf(1L, 3, 4))
        StatsSnapshotRepository.clear()
        assertEquals(0, StatsSnapshotRepository.latest().totalFlows)
        assertTrue(StatsSnapshotRepository.latest().isEmpty)
    }

    @Test
    fun listenersReceiveEveryPublishedSnapshot() {
        val received = AtomicInteger()
        val listener: (StatsSnapshot) -> Unit = { received.incrementAndGet() }
        StatsSnapshotRepository.addListener(listener)
        try {
            StatsSnapshotRepository.publish(snapshotOf(1L, 1, 0))
            StatsSnapshotRepository.publish(snapshotOf(2L, 2, 0))
            assertEquals(2, received.get())
        } finally {
            StatsSnapshotRepository.removeListener(listener)
        }
    }

    @Test
    fun aFailingListenerDoesNotBlockOtherListenersOrPublication() {
        val secondListenerRan = AtomicBoolean(false)
        val failing: (StatsSnapshot) -> Unit = { throw IllegalStateException("boom") }
        val healthy: (StatsSnapshot) -> Unit = { secondListenerRan.set(true) }
        StatsSnapshotRepository.addListener(failing)
        StatsSnapshotRepository.addListener(healthy)
        try {
            StatsSnapshotRepository.publish(snapshotOf(1L, 5, 0))
            assertTrue(secondListenerRan.get())
            assertEquals(5, StatsSnapshotRepository.latest().totalFlows)
        } finally {
            StatsSnapshotRepository.removeListener(failing)
            StatsSnapshotRepository.removeListener(healthy)
        }
    }

    @Test
    fun concurrentReadersNeverObserveAPartiallyUpdatedSnapshot() {
        val writerCount = 4
        val readerCount = 6
        val iterations = 5_000
        val start = CountDownLatch(1)
        val done = CountDownLatch(writerCount + readerCount)
        val inconsistencies = AtomicInteger()

        val writers = (1..writerCount).map { writerIndex ->
            Thread {
                start.await()
                repeat(iterations) { i ->
                    val n = writerIndex * 1000 + i % 97
                    StatsSnapshotRepository.publish(snapshotOf(writerIndex.toLong(), n, n + 1))
                }
                done.countDown()
            }
        }

        val readers = (1..readerCount).map {
            Thread {
                start.await()
                repeat(iterations) {
                    val snapshot = StatsSnapshotRepository.latest()
                    val rowTotal = snapshot.rows.sumOf { row -> row.total }
                    if (rowTotal != snapshot.totalFlows || snapshot.totalFlows < 0) {
                        inconsistencies.incrementAndGet()
                    }
                }
                done.countDown()
            }
        }

        (writers + readers).forEach { it.start() }
        start.countDown()
        assertTrue("併發測試必須在時限內完成", done.await(60, TimeUnit.SECONDS))
        assertEquals("讀者不得看到半更新的快照", 0, inconsistencies.get())
    }
}
