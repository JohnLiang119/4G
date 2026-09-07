package com.tw.perapp4grouter.stats

/**
 * 作用中 flow 的不可變身分。
 *
 * 位址與埠號一律取自 APP 的「原始」外送封包（在 [com.tw.perapp4grouter.localvpn.Packet.swapSourceAndDestination]
 * 之前），因此絕不會誤把 LocalVPN 自己的上游 socket 當成來源。
 *
 * [vpnGeneration] 會在每次成功建立 VPN 時遞增，用來讓已停止 session 的延遲事件無法影響新 session。
 */
data class FlowKey(
    val vpnGeneration: Long,
    val protocol: FlowProtocol,
    val sourceAddress: String,
    val sourcePort: Int,
    val destinationAddress: String,
    val destinationPort: Int
) {
    override fun toString(): String =
        "gen=$vpnGeneration $protocol $sourceAddress:$sourcePort->$destinationAddress:$destinationPort"
}
