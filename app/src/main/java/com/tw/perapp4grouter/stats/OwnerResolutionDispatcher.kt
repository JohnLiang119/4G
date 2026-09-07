package com.tw.perapp4grouter.stats

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 在小型有界執行緒池上執行擁有者解析，讓封包轉送執行緒永遠不會被框架查詢阻塞。
 *
 * 行為約束：
 * - 每條 flow 只會排入一次查詢；重複封包不會重複排程。
 * - 佇列滿載時直接拒絕，flow 維持 [AppIdentity.Unknown]，轉送照常進行。
 * - 已關閉後的提交一律被忽略。
 */
class OwnerResolutionDispatcher(
    private val resolver: ConnectionOwnerResolver,
    private val onResolved: (FlowKey, AppIdentity) -> Unit,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    threadCount: Int = DEFAULT_THREAD_COUNT
) {

    private val inFlight = ConcurrentHashMap.newKeySet<FlowKey>()
    private val submitted = AtomicInteger()
    private val rejected = AtomicInteger()
    private val completed = AtomicInteger()

    @Volatile
    private var shuttingDown = false

    private val executor = ThreadPoolExecutor(
        threadCount,
        threadCount,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(queueCapacity),
        { runnable -> Thread(runnable, "owner-resolver").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )

    /** 已實際排程的查詢次數。 */
    val submittedCount: Int get() = submitted.get()

    /** 因佇列滿載而被拒絕的次數。 */
    val rejectedCount: Int get() = rejected.get()

    /** 已完成並回呼的查詢次數。 */
    val completedCount: Int get() = completed.get()

    /**
     * 為一條新 flow 排程一次擁有者解析。此方法不會阻塞呼叫端。
     *
     * @return 實際排程時回傳 true；重複、已關閉或佇列滿載時回傳 false。
     */
    fun submit(key: FlowKey): Boolean {
        if (shuttingDown) return false
        if (!inFlight.add(key)) return false

        return try {
            executor.execute {
                try {
                    val identity = resolver.resolve(key)
                    if (!shuttingDown) {
                        onResolved(key, identity)
                    }
                    completed.incrementAndGet()
                } catch (e: Exception) {
                    // 解析失敗維持未知身分，不影響轉送
                } finally {
                    inFlight.remove(key)
                }
            }
            submitted.incrementAndGet()
            true
        } catch (e: RejectedExecutionException) {
            // 佇列滿載：維持未知身分，封包照常轉送
            inFlight.remove(key)
            rejected.incrementAndGet()
            false
        }
    }

    /** 停止接受新查詢並取消尚未執行的工作。 */
    fun shutdown() {
        shuttingDown = true
        executor.shutdownNow()
        inFlight.clear()
    }

    /** 僅供測試：等待目前排入的查詢全部結束。 */
    fun awaitQuiescence(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (executor.queue.isEmpty() && executor.activeCount == 0 && inFlight.isEmpty()) return true
            Thread.sleep(5L)
        }
        return false
    }

    companion object {
        private const val DEFAULT_QUEUE_CAPACITY = 512
        private const val DEFAULT_THREAD_COUNT = 2
    }
}
