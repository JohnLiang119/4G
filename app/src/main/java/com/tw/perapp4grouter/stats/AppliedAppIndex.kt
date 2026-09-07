package com.tw.perapp4grouter.stats

/**
 * 一個已通過驗證、確實被加入 VPN builder 的 APP。
 */
data class AppliedApp(
    val packageName: String,
    val uid: Int,
    val label: String
)

/**
 * 本次 VPN session 的不可變已套用 APP 索引。
 *
 * 只包含成功加入 VPN builder 的 package，因此執行期間修改勾選不會改變既有 flow 的標示。
 * 索引在 session 開始時一次建立，之後不再查詢 PackageManager。
 */
class AppliedAppIndex private constructor(
    val appliedPackages: List<String>,
    private val identityByUid: Map<Int, AppIdentity>
) {

    val isEmpty: Boolean get() = appliedPackages.isEmpty()

    /**
     * 將 UID 映射成本次 session 的 APP 身分。
     *
     * 不在已套用清單中的 UID（例如路由服務本身或未套用的 APP）一律回傳
     * [AppIdentity.Unknown]，絕不猜測歸屬。
     */
    fun identityFor(uid: Int): AppIdentity =
        if (uid < 0) AppIdentity.Unknown else identityByUid[uid] ?: AppIdentity.Unknown

    companion object {

        val EMPTY = AppliedAppIndex(emptyList(), emptyMap())

        /**
         * 由已驗證的 APP 清單建立索引。
         *
         * 同一個 UID 對應多個 package 時，會建立單一 [AppIdentity.SharedUid] 分組，
         * 不挑選其中之一，也不把 flow 複製到多個 APP。
         */
        fun build(applied: List<AppliedApp>): AppliedAppIndex {
            if (applied.isEmpty()) return EMPTY

            val distinct = applied.distinctBy { it.packageName }
            val identityByUid = HashMap<Int, AppIdentity>()

            for ((uid, group) in distinct.groupBy { it.uid }) {
                identityByUid[uid] = if (group.size == 1) {
                    val only = group.single()
                    AppIdentity.Resolved(uid, only.packageName, only.label)
                } else {
                    val sorted = group.sortedBy { it.packageName }
                    AppIdentity.SharedUid(
                        uid = uid,
                        packageNames = sorted.map { it.packageName },
                        labels = sorted.map { it.label }
                    )
                }
            }

            return AppliedAppIndex(distinct.map { it.packageName }, identityByUid)
        }
    }
}
