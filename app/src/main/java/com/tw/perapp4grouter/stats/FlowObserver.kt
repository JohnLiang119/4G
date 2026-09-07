package com.tw.perapp4grouter.stats

/**
 * 提供給 LocalVPN 封包處理執行緒的窄介面。
 *
 * 所有方法都必須是非阻塞且冪等的：統計失敗絕不能中斷或延遲封包轉送。
 */
interface FlowObserver {

    /** 本 observer 所屬的 VPN session generation。 */
    val generation: Long

    /**
     * 以 APP 的原始外送端點建立 flow 身分。
     *
     * 呼叫端必須在 `swapSourceAndDestination()` 之前取得這些值，
     * 且絕不可傳入 LocalVPN 受保護的上游 socket 端點。
     */
    fun keyFor(
        protocol: FlowProtocol,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): FlowKey

    /** 首次觀察到新 flow。重複呼叫不會重複計數。 */
    fun onFlowOpened(key: FlowKey)

    /** 任一方向出現後續封包，僅刷新最後活動時間。 */
    fun onFlowActivity(key: FlowKey)

    /** flow 終止。所有終止路徑共用此入口，重複呼叫不會重複扣除。 */
    fun onFlowClosed(key: FlowKey)
}

/**
 * 讓 LocalVPN 引擎中的靜態快取能取得目前 session 的 observer。
 *
 * generation 檢核在 observer 內部完成，因此即使舊 worker 拿到新的 observer，
 * 它以舊 generation 建立的鍵值也不會生效。
 */
object FlowObservers {

    @Volatile
    private var current: FlowObserver? = null

    @JvmStatic
    fun get(): FlowObserver? = current

    @JvmStatic
    fun set(observer: FlowObserver?) {
        current = observer
    }
}
