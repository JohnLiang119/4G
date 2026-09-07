package com.tw.perapp4grouter.stats

import java.util.concurrent.atomic.AtomicLong

/**
 * 單一 VPN session 所擁有的完整統計狀態：追蹤器、擁有者解析與快照發布。
 *
 * 只有在 `establish()` 成功之後才會建立，並在每個停止／失敗路徑上被完整關閉，
 * 因此每個成功的 session 都必定從零開始。
 */
class VpnStatsSession(
    override val generation: Long,
    val appliedAppIndex: AppliedAppIndex,
    ownerLookup: ConnectionOwnerLookup,
    private val clock: MonotonicClock = SystemMonotonicClock
) : FlowObserver {

    val tracker = ConnectionStatsTracker(generation, appliedAppIndex.appliedPackages, clock)

    private val dispatcher = OwnerResolutionDispatcher(
        resolver = ConnectionOwnerResolver(ownerLookup, appliedAppIndex),
        onResolved = { key, identity ->
            // tracker 會再次以 generation 與「flow 是否仍存在」把關，
            // 因此延遲結果無法重新標示已關閉的 flow 或新的 session
            tracker.resolve(key, identity)
        }
    )

    @Volatile
    private var closed = false

    override fun keyFor(
        protocol: FlowProtocol,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): FlowKey = FlowKey(
        vpnGeneration = generation,
        protocol = protocol,
        sourceAddress = sourceAddress,
        sourcePort = sourcePort,
        destinationAddress = destinationAddress,
        destinationPort = destinationPort
    )

    override fun onFlowOpened(key: FlowKey) {
        if (closed) return
        // 先以未知身分計數，轉送立即繼續；解析在有界執行緒池非同步進行
        if (tracker.open(key)) {
            dispatcher.submit(key)
        }
    }

    override fun onFlowActivity(key: FlowKey) {
        if (closed) return
        tracker.touch(key)
    }

    override fun onFlowClosed(key: FlowKey) {
        tracker.close(key)
    }

    /** 移除所有閒置滿 60 秒的 UDP flow，回傳被移除的鍵值供呼叫端關閉 channel。 */
    fun expireIdleUdpFlows(): List<FlowKey> =
        tracker.expireIdleUdpFlows(ConnectionStatsTracker.UDP_IDLE_TIMEOUT_MILLIS)

    fun snapshot(): StatsSnapshot = tracker.snapshot()

    /** 發布目前狀態的不可變快照給三個呈現介面。 */
    fun publish() {
        if (closed) return
        StatsSnapshotRepository.publish(tracker.snapshot())
    }

    /** 發布本 session 的空白快照，讓新 session 從零開始顯示。 */
    fun publishEmpty() {
        StatsSnapshotRepository.publish(
            StatsSnapshot.empty(generation, appliedAppIndex.appliedPackages, clock.elapsedMillis())
        )
    }

    /** 停止解析、清空追蹤器並釋放本 session 的所有 flow metadata。 */
    fun shutdown() {
        closed = true
        dispatcher.shutdown()
        tracker.reset()
    }

    companion object {
        private val generationCounter = AtomicLong(0L)

        /** 只有在 VPN 成功建立之後才呼叫，取得新的統計 generation。 */
        fun nextGeneration(): Long = generationCounter.incrementAndGet()
    }
}
