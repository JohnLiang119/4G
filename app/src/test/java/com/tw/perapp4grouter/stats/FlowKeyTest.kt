package com.tw.perapp4grouter.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 驗證 [FlowKey] 以完整五元組加上 VPN generation 作為身分。
 */
class FlowKeyTest {

    private fun key(
        generation: Long = 1L,
        protocol: FlowProtocol = FlowProtocol.TCP,
        sourceAddress: String = "10.0.0.2",
        sourcePort: Int = 40000,
        destinationAddress: String = "93.184.216.34",
        destinationPort: Int = 443
    ) = FlowKey(generation, protocol, sourceAddress, sourcePort, destinationAddress, destinationPort)

    @Test
    fun identicalTuplesAreEqualAndShareHashCode() {
        assertEquals(key(), key())
        assertEquals(key().hashCode(), key().hashCode())
    }

    @Test
    fun tcpAndUdpOnSameTupleAreDifferentFlows() {
        assertNotEquals(key(protocol = FlowProtocol.TCP), key(protocol = FlowProtocol.UDP))
    }

    @Test
    fun everyTupleComponentAffectsIdentity() {
        assertNotEquals(key(), key(sourceAddress = "10.0.0.3"))
        assertNotEquals(key(), key(sourcePort = 40001))
        assertNotEquals(key(), key(destinationAddress = "93.184.216.35"))
        assertNotEquals(key(), key(destinationPort = 80))
    }

    @Test
    fun differentVpnGenerationsAreDifferentFlows() {
        assertNotEquals(key(generation = 1L), key(generation = 2L))
    }

    @Test
    fun protocolCarriesIpProtocolNumber() {
        assertEquals(6, FlowProtocol.TCP.ipProtocolNumber)
        assertEquals(17, FlowProtocol.UDP.ipProtocolNumber)
    }
}
