package com.tw.perapp4grouter.stats

/**
 * flow 的 APP 歸屬結果。
 *
 * 系統絕不猜測歸屬：無法唯一映射的 flow 一律落在 [SharedUid] 或 [Unknown] 分組，
 * 讓全域總數永遠可以對帳。
 */
sealed class AppIdentity {

    /** 統計分組的穩定鍵值，相同 groupKey 的 flow 會被聚合成同一列。 */
    abstract val groupKey: String

    /** 顯示用名稱。 */
    abstract val displayLabel: String

    /** 此分組涵蓋的 package 名稱，供重複名稱時做區別使用。 */
    abstract val packageNames: List<String>

    /** UID 已解析且唯一對應一個可見 package。 */
    data class Resolved(
        val uid: Int,
        val packageName: String,
        val label: String
    ) : AppIdentity() {
        override val groupKey: String get() = "app:$uid:$packageName"
        override val displayLabel: String get() = label
        override val packageNames: List<String> get() = listOf(packageName)
    }

    /**
     * 一個 UID 對應多個可見 package。以單一分組呈現，
     * 絕不把同一條 flow 複製到多個 APP，也不任意挑其中之一。
     */
    data class SharedUid(
        val uid: Int,
        override val packageNames: List<String>,
        val labels: List<String>
    ) : AppIdentity() {
        override val groupKey: String get() = "shared:$uid"
        override val displayLabel: String get() = "共用 UID：" + labels.joinToString(" / ")
    }

    /** 取不到有效 UID、UID 沒有可見 package，或解析被拒絕／逾時。 */
    object Unknown : AppIdentity() {
        override val groupKey: String get() = "unknown"
        override val displayLabel: String get() = "未知 APP"
        override val packageNames: List<String> get() = emptyList()
    }
}
