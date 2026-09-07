package com.tw.perapp4grouter.stats

/**
 * 查詢單一 package 是否已安裝且可見，並取得其 UID 與在地化名稱。
 */
interface AppliedPackageResolver {
    /** @return 已安裝且可見時回傳資訊，否則回傳 null */
    fun resolve(packageName: String): AppliedApp?
}

/**
 * 把 package 實際加入 VPN builder 的允許清單。
 */
interface AllowedApplicationSink {
    /** @return 成功加入時回傳 true */
    fun addAllowedApplication(packageName: String): Boolean
}

/**
 * 在 `establish()` 之前驗證並套用 APP 選擇。
 *
 * 只有「驗證成功且確實被 builder 接受」的 package 才會出現在結果中。
 * 結果為空時呼叫端必須中止建立 VPN —— 空的 allowed list 會讓 Android
 * 把所有 APP 都導入 VPN，絕不可作為退化行為。
 */
object AppliedAppSelector {

    fun apply(
        selectedPackages: Collection<String>,
        resolver: AppliedPackageResolver,
        sink: AllowedApplicationSink
    ): List<AppliedApp> {
        val applied = ArrayList<AppliedApp>()
        val seen = HashSet<String>()

        for (packageName in selectedPackages) {
            if (packageName.isBlank() || !seen.add(packageName)) continue

            // 先驗證：APP 可能在按下啟動之前才被解除安裝
            val candidate = resolver.resolve(packageName) ?: continue

            if (sink.addAllowedApplication(packageName)) {
                applied.add(candidate)
            }
        }

        return applied
    }
}
