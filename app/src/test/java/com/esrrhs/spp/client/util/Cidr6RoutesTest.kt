package com.esrrhs.spp.client.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class Cidr6RoutesTest {

    @Test
    fun parseAddress_supportsCompression() {
        // 2001:db8::1
        val expected = BigInteger("20010db8000000000000000000000001", 16)
        assertEquals(expected, Cidr6Routes.parseAddress("2001:db8::1"))
        assertEquals(BigInteger.ONE, Cidr6Routes.parseAddress("::1"))
        assertEquals(
            BigInteger("20010db8000000000000000000000000", 16),
            Cidr6Routes.parseAddress("2001:db8::"),
        )
    }

    @Test
    fun toCompressed_roundTrips() {
        listOf("2001:db8::1", "::1", "2001:250::", "fe80::1").forEach { text ->
            assertEquals(text, Cidr6Routes.toCompressed(Cidr6Routes.parseAddress(text)))
        }
    }

    @Test
    fun globalCidrs_reservedRangesAreExcluded() {
        val cidrs = Cidr6Routes.globalCidrs()
        listOf("::1", "fc00::1", "fd12::1", "fe80::1", "ff02::1").forEach { ip ->
            assertTrue("$ip should go direct", !covers(cidrs, ip))
        }
    }

    @Test
    fun globalCidrs_globalAddressesAreRouted() {
        val cidrs = Cidr6Routes.globalCidrs()
        listOf(
            "2001:4860:4860::8888", "2606:4700:4700::1111", "2400:3200::1",
        ).forEach { ip ->
            assertTrue("$ip should be proxied", covers(cidrs, ip))
        }
    }

    @Test
    fun globalCidrs_decomposesToGlobalUnicast() {
        // 无 CN：2000::/3 挖掉文档段后的最小分解
        val cidrs = Cidr6Routes.globalCidrs()
        println("global v6 routes (no CN): ${cidrs.size}")
        assertTrue("unexpected count: ${cidrs.size}", cidrs.size in 20..50)
    }

    @Test
    fun globalCidrs_neverRouteOutsideGlobalUnicast() {
        val cidrs = Cidr6Routes.globalCidrs()
        // 2000::/3 之外的任何地址都不应走隧道
        listOf("100::1", "4000::1", "d000::1", "f000::1").forEach { ip ->
            assertTrue("$ip outside global unicast", !covers(cidrs, ip))
        }
    }

    private fun covers(cidrs: List<Cidr6>, ip: String): Boolean {
        val value = Cidr6Routes.parseAddress(ip)
        return cidrs.any { cidr ->
            val network = Cidr6Routes.parseAddress(cidr.address)
            val hostBits = 128 - cidr.prefix
            val size = if (hostBits == 128) BigInteger.ONE.shiftLeft(128)
            else BigInteger.ONE.shiftLeft(hostBits)
            value >= network && value < network + size
        }
    }
}
