package com.tw.perapp4grouter.stats

/**
 * 單一 APP 分組的作用中連線統計。[total] 恆等於 [activeTcp] + [activeUdp]。
 */
data class AppStatsRow(
    val identity: AppIdentity,
    val activeTcp: Int,
    val activeUdp: Int,
    val lastActivityAtMillis: Long
) {
    val groupKey: String get() = identity.groupKey
    val label: String get() = identity.displayLabel
    val packageNames: List<String> get() = identity.packageNames
    val total: Int get() = activeTcp + activeUdp
}
