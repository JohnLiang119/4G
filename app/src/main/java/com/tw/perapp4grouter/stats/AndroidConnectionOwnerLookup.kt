package com.tw.perapp4grouter.stats

import android.content.Context
import android.net.ConnectivityManager
import android.os.Process
import java.net.InetSocketAddress

/**
 * Android 10（API 29）起提供的官方連線擁有者查詢。
 *
 * 這是唯一被支援的歸屬來源，不提供舊版 `/proc/net` 之類的替代路徑。
 */
class AndroidConnectionOwnerLookup(context: Context) : ConnectionOwnerLookup {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun lookupUid(
        ipProtocolNumber: Int,
        local: InetSocketAddress,
        remote: InetSocketAddress
    ): Int {
        val uid = connectivityManager.getConnectionOwnerUid(ipProtocolNumber, local, remote)
        return if (uid == Process.INVALID_UID) INVALID_UID else uid
    }
}
