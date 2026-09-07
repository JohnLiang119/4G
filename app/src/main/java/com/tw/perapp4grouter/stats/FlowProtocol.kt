package com.tw.perapp4grouter.stats

/**
 * 統計所涵蓋的傳輸協定。僅 TCP 與 UDP 會進入連線統計，其餘協定一律被排除。
 */
enum class FlowProtocol(val ipProtocolNumber: Int) {
    TCP(6),
    UDP(17)
}
