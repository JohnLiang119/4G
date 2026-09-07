package com.tw.perapp4grouter.stats

import java.util.concurrent.ConcurrentHashMap

/**
 * 單一 VPN session 的集中式作用中連線追蹤器。
 *
 * 設計重點：
 * - 所有操作都以 [generation] 檢核，舊 session worker 的延遲事件會被直接忽略。
 * - [open]／[close] 皆為冪等操作：重複 SYN 不會重複計數，重複終止不會重複扣除。
 * - 統計數字一律由目前的紀錄集合推導而來，不維護獨立的可變計數器，
 *   因此結構上不可能出現負數或分組與全域總數不一致。
 */
class ConnectionStatsTracker(
    val generation: Long,
    val appliedPackages: List<String>,
    private val clock: MonotonicClock = SystemMonotonicClock
) {

    private val records = ConcurrentHashMap<FlowKey, FlowRecord>()

    /** 目前作用中的 flow 數量。 */
    val activeFlowCount: Int get() = records.size

    fun contains(key: FlowKey): Boolean = records.containsKey(key)

    fun recordOf(key: FlowKey): FlowRecord? = records[key]

    /**
     * 建立一筆新的作用中 flow，初始身分為 [AppIdentity.Unknown]。
     *
     * @return 實際新增時回傳 true；重複開啟或 generation 不符時回傳 false。
     */
    fun open(key: FlowKey): Boolean {
        if (key.vpnGeneration != generation) return false
        val now = clock.elapsedMillis()
        val record = FlowRecord(
            key = key,
            identity = AppIdentity.Unknown,
            createdAtMillis = now,
            lastActivityAtMillis = now
        )
        return records.putIfAbsent(key, record) == null
    }

    /**
     * 套用非同步取得的 APP 歸屬結果。
     *
     * 只有在 flow 仍然存在且 generation 相符時才會生效，
     * 因此延遲回來的結果無法為已關閉的 flow 或新 session 重新標示身分。
     */
    fun resolve(key: FlowKey, identity: AppIdentity): Boolean {
        if (key.vpnGeneration != generation) return false
        return records.computeIfPresent(key) { _, record ->
            record.withIdentity(identity)
        } != null
    }

    /**
     * 更新最後活動時間。任一方向的封包都會呼叫，不重新查詢擁有者。
     *
     * @return flow 仍在追蹤中時回傳 true。
     */
    fun touch(key: FlowKey, timestampMillis: Long = clock.elapsedMillis()): Boolean {
        if (key.vpnGeneration != generation) return false
        return records.computeIfPresent(key) { _, record ->
            record.withActivityAt(timestampMillis)
        } != null
    }

    /**
     * 移除一筆 flow。所有終止路徑（連線失敗、RST、雙向 FIN 完成、channel 錯誤、
     * 快取淘汰、引擎關閉）都會匯集到此處，且重複呼叫不會重複扣除。
     *
     * @return 實際移除時回傳 true。
     */
    fun close(key: FlowKey): Boolean {
        if (key.vpnGeneration != generation) return false
        return records.remove(key) != null
    }

    /** 清除本 session 的所有 flow metadata，不保留任何可供下次 session 查閱的歷史。 */
    fun reset() {
        records.clear()
    }

    /**
     * 掃出所有在任一方向皆已閒置滿 [idleMillis] 的 UDP flow 並移除。
     *
     * @return 被移除的 flow 鍵值，呼叫端可據此關閉對應的 channel。
     */
    fun expireIdleUdpFlows(idleMillis: Long): List<FlowKey> {
        val now = clock.elapsedMillis()
        val expired = ArrayList<FlowKey>()
        for (record in records.values) {
            if (record.protocol != FlowProtocol.UDP) continue
            if (now - record.lastActivityAtMillis >= idleMillis) {
                expired.add(record.key)
            }
        }
        for (key in expired) {
            records.remove(key)
        }
        return expired
    }

    /**
     * 由目前紀錄推導出一份內部一致的不可變快照。
     *
     * 分組排序：總數多者優先，其次為最近活動，最後以顯示名稱穩定排序，
     * 讓數量相同的分組不會在每次刷新時跳動。
     */
    fun snapshot(): StatsSnapshot {
        val accumulators = LinkedHashMap<String, RowAccumulator>()
        var totalTcp = 0
        var totalUdp = 0

        for (record in records.values) {
            val accumulator = accumulators.getOrPut(record.identity.groupKey) {
                RowAccumulator(record.identity)
            }
            when (record.protocol) {
                FlowProtocol.TCP -> {
                    accumulator.tcp++
                    totalTcp++
                }
                FlowProtocol.UDP -> {
                    accumulator.udp++
                    totalUdp++
                }
            }
            if (record.lastActivityAtMillis > accumulator.lastActivityAtMillis) {
                accumulator.lastActivityAtMillis = record.lastActivityAtMillis
            }
        }

        val rows = accumulators.values
            .map { AppStatsRow(it.identity, it.tcp, it.udp, it.lastActivityAtMillis) }
            .sortedWith(ROW_ORDER)

        return StatsSnapshot(
            generation = generation,
            appliedPackages = appliedPackages,
            rows = rows,
            totalTcp = totalTcp,
            totalUdp = totalUdp,
            publishedAtMillis = clock.elapsedMillis()
        )
    }

    private class RowAccumulator(val identity: AppIdentity) {
        var tcp: Int = 0
        var udp: Int = 0
        var lastActivityAtMillis: Long = 0L
    }

    companion object {
        /** UDP flow 在任一方向最後活動後的保留時間。 */
        const val UDP_IDLE_TIMEOUT_MILLIS: Long = 60_000L

        private val ROW_ORDER: Comparator<AppStatsRow> =
            compareByDescending<AppStatsRow> { it.total }
                .thenByDescending { it.lastActivityAtMillis }
                .thenBy { it.label }
                .thenBy { it.groupKey }
    }
}
