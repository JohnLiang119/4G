package com.tw.perapp4grouter.stats

/**
 * 單一作用中 flow 的完整狀態。僅保留產生統計所需的 metadata，
 * 不記錄封包內容、目的網址或流量 bytes。
 */
data class FlowRecord(
    val key: FlowKey,
    val identity: AppIdentity,
    val createdAtMillis: Long,
    val lastActivityAtMillis: Long
) {
    val protocol: FlowProtocol get() = key.protocol

    fun withIdentity(newIdentity: AppIdentity): FlowRecord = copy(identity = newIdentity)

    fun withActivityAt(timestampMillis: Long): FlowRecord =
        if (timestampMillis <= lastActivityAtMillis) this
        else copy(lastActivityAtMillis = timestampMillis)
}
