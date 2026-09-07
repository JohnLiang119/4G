package com.tw.perapp4grouter.stats

import java.net.InetAddress
import java.net.InetSocketAddress

/** 無效或無法取得的 UID。 */
const val INVALID_UID: Int = -1

/**
 * 連線擁有者查詢的抽象介面，讓追蹤與歸屬邏輯可以在 JVM 上以假實作測試，
 * 不需要真的 Android framework。
 */
interface ConnectionOwnerLookup {

    /**
     * @param ipProtocolNumber TCP 為 6、UDP 為 17
     * @param local APP 端的原始來源端點
     * @param remote APP 端的原始目的端點
     * @return 來源 UID，取不到時回傳 [INVALID_UID]
     */
    fun lookupUid(ipProtocolNumber: Int, local: InetSocketAddress, remote: InetSocketAddress): Int
}

/**
 * 把 flow 的原始五元組解析成本次 session 的 APP 身分。
 *
 * 傳入的端點必須是 APP 的原始外送 tuple（swap 之前），
 * 絕不能使用 LocalVPN 受保護的上游 socket，否則擁有者會變成路由服務自己。
 *
 * 任何查詢失敗（無效 UID、SecurityException、找不到 package）都退化為
 * [AppIdentity.Unknown]，不會讓 flow 失敗。
 */
class ConnectionOwnerResolver(
    private val lookup: ConnectionOwnerLookup,
    private val appliedAppIndex: AppliedAppIndex
) {

    fun resolve(key: FlowKey): AppIdentity {
        val uid = lookupUidOrInvalid(key)
        if (uid == INVALID_UID) return AppIdentity.Unknown
        return appliedAppIndex.identityFor(uid)
    }

    private fun lookupUidOrInvalid(key: FlowKey): Int = try {
        val local = InetSocketAddress(InetAddress.getByName(key.sourceAddress), key.sourcePort)
        val remote = InetSocketAddress(InetAddress.getByName(key.destinationAddress), key.destinationPort)
        val uid = lookup.lookupUid(key.protocol.ipProtocolNumber, local, remote)
        if (uid < 0) INVALID_UID else uid
    } catch (e: SecurityException) {
        // 系統拒絕查詢：維持未知，不中斷 flow
        INVALID_UID
    } catch (e: Exception) {
        // 位址解析或框架錯誤：同樣維持未知
        INVALID_UID
    }
}
