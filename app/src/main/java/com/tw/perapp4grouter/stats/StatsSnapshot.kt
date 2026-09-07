package com.tw.perapp4grouter.stats

/**
 * 某個時間點的不可變統計快照。主畫面、通知列與懸浮窗一律讀同一份快照，
 * 因此三個介面永遠呈現彼此一致的數字。
 *
 * 不變量：
 * - 每列 `row.total == row.activeTcp + row.activeUdp`
 * - `totalTcp == rows.sumOf { it.activeTcp }`、`totalUdp == rows.sumOf { it.activeUdp }`
 * - `totalFlows == rows.sumOf { it.total }`
 */
data class StatsSnapshot(
    val generation: Long,
    val appliedPackages: List<String>,
    val rows: List<AppStatsRow>,
    val totalTcp: Int,
    val totalUdp: Int,
    val publishedAtMillis: Long
) {
    /** 全域作用中 flow 總數。 */
    val totalFlows: Int get() = totalTcp + totalUdp

    /** 目前有作用中 flow 的分組數；僅被勾選但閒置的 APP 不計入。 */
    val activeAppCount: Int get() = rows.size

    val isEmpty: Boolean get() = rows.isEmpty()

    companion object {
        /** 尚未啟動任何 VPN session 時的初始快照。 */
        val NONE = StatsSnapshot(
            generation = 0L,
            appliedPackages = emptyList(),
            rows = emptyList(),
            totalTcp = 0,
            totalUdp = 0,
            publishedAtMillis = 0L
        )

        /** 新 session 建立後、尚無任何 flow 時發布的空白快照。 */
        fun empty(
            generation: Long,
            appliedPackages: List<String>,
            publishedAtMillis: Long
        ): StatsSnapshot = StatsSnapshot(
            generation = generation,
            appliedPackages = appliedPackages,
            rows = emptyList(),
            totalTcp = 0,
            totalUdp = 0,
            publishedAtMillis = publishedAtMillis
        )
    }
}
