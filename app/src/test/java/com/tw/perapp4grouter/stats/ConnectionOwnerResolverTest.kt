package com.tw.perapp4grouter.stats

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetSocketAddress

/**
 * 驗證擁有者解析在成功與各種失敗情境下的行為。
 */
class ConnectionOwnerResolverTest {

    private val appliedIndex = AppliedAppIndex.build(
        listOf(
            AppliedApp("com.example.a", 10101, "APP A"),
            AppliedApp("com.example.shared1", 10500, "共用一"),
            AppliedApp("com.example.shared2", 10500, "共用二")
        )
    )

    private fun key(protocol: FlowProtocol) =
        FlowKey(1L, protocol, "10.0.0.2", 40000, "93.184.216.34", 443)

    private class RecordingLookup(private val result: () -> Int) : ConnectionOwnerLookup {
        var lastProtocol: Int = -1
        var lastLocal: InetSocketAddress? = null
        var lastRemote: InetSocketAddress? = null
        var callCount: Int = 0

        override fun lookupUid(ipProtocolNumber: Int, local: InetSocketAddress, remote: InetSocketAddress): Int {
            callCount++
            lastProtocol = ipProtocolNumber
            lastLocal = local
            lastRemote = remote
            return result()
        }
    }

    @Test
    fun tcpLookupUsesProtocol6AndTheOriginalOutboundTuple() {
        val lookup = RecordingLookup { 10101 }
        val identity = ConnectionOwnerResolver(lookup, appliedIndex).resolve(key(FlowProtocol.TCP))

        assertEquals(6, lookup.lastProtocol)
        assertEquals(40000, lookup.lastLocal?.port)
        assertEquals("10.0.0.2", lookup.lastLocal?.address?.hostAddress)
        assertEquals(443, lookup.lastRemote?.port)
        assertEquals("93.184.216.34", lookup.lastRemote?.address?.hostAddress)
        assertEquals(AppIdentity.Resolved(10101, "com.example.a", "APP A"), identity)
    }

    @Test
    fun udpLookupUsesProtocol17() {
        val lookup = RecordingLookup { 10101 }
        ConnectionOwnerResolver(lookup, appliedIndex).resolve(key(FlowProtocol.UDP))
        assertEquals(17, lookup.lastProtocol)
    }

    @Test
    fun invalidUidBecomesUnknown() {
        val lookup = RecordingLookup { INVALID_UID }
        assertEquals(AppIdentity.Unknown, ConnectionOwnerResolver(lookup, appliedIndex).resolve(key(FlowProtocol.TCP)))
    }

    @Test
    fun securityExceptionBecomesUnknown() {
        val lookup = RecordingLookup { throw SecurityException("denied") }
        assertEquals(AppIdentity.Unknown, ConnectionOwnerResolver(lookup, appliedIndex).resolve(key(FlowProtocol.TCP)))
    }

    @Test
    fun frameworkFailureBecomesUnknown() {
        val lookup = RecordingLookup { throw IllegalStateException("boom") }
        assertEquals(AppIdentity.Unknown, ConnectionOwnerResolver(lookup, appliedIndex).resolve(key(FlowProtocol.TCP)))
    }

    @Test
    fun packageLookupFailureBecomesUnknown() {
        // UID 有效但不在已套用索引內，代表取不到可見 package
        val lookup = RecordingLookup { 10999 }
        assertEquals(AppIdentity.Unknown, ConnectionOwnerResolver(lookup, appliedIndex).resolve(key(FlowProtocol.TCP)))
    }

    @Test
    fun sharedUidResolvesToASingleSharedGroup() {
        val lookup = RecordingLookup { 10500 }
        val identity = ConnectionOwnerResolver(lookup, appliedIndex).resolve(key(FlowProtocol.TCP))
        assertEquals("shared:10500", identity.groupKey)
        assertEquals(2, identity.packageNames.size)
    }

    @Test
    fun resolutionQueriesTheFrameworkExactlyOncePerCall() {
        val lookup = RecordingLookup { 10101 }
        ConnectionOwnerResolver(lookup, appliedIndex).resolve(key(FlowProtocol.TCP))
        assertEquals(1, lookup.callCount)
    }
}
