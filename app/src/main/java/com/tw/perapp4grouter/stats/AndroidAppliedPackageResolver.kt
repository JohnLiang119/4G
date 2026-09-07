package com.tw.perapp4grouter.stats

import android.content.pm.PackageManager

/**
 * 以 [PackageManager] 驗證選取的 package 是否仍然存在且可見，並取得 UID 與在地化名稱。
 *
 * 每個 VPN session 只在建立索引時查詢一次，之後的每封包活動與畫面刷新都不再查詢。
 */
class AndroidAppliedPackageResolver(
    private val packageManager: PackageManager
) : AppliedPackageResolver {

    override fun resolve(packageName: String): AppliedApp? = try {
        val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
        AppliedApp(
            packageName = packageName,
            uid = applicationInfo.uid,
            label = applicationInfo.loadLabel(packageManager).toString()
        )
    } catch (e: PackageManager.NameNotFoundException) {
        // APP 在套用前已被解除安裝或不可見
        null
    } catch (e: Exception) {
        null
    }
}
