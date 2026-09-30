package com.esrrhs.spp.client.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CidrRoutesTest {

    @Test
    fun intervalToCidrs_wholeSpace_isDefaultRoute() {
        val cidrs = CidrRoutes.intervalToCidrs(0L, 1L shl 32)
        assertEquals(listOf(Cidr4("0.0.0.0", 0)), cidrs)
    }

    @Test
    fun intervalToCidrs_decomposesAlignedHalves() {
        // 128.0.0.0 .. 256-space = the upper half
        val cidrs = CidrRoutes.intervalToCidrs(1L shl 31, 1L shl 32)
        assertEquals(listOf(Cidr4("128.0.0.0", 1)), cidrs)
    }

    @Test
    fun intervalToCidrs_unalignedStart_splitsMinimally() {
        // 1.0.0.0 .. 3.0.0.0 (two /8: odd start, len 2/8)
        val cidrs = CidrRoutes.intervalToCidrs(1L shl 24, 3L shl 24)
        assertEquals(
            listOf(Cidr4("1.0.0.0", 8), Cidr4("2.0.0.0", 8)),
            cidrs,
        )
    }

    @Test
    fun publicCidrs_neverContainPrivateRanges() {
        val cidrs = CidrRoutes.publicCidrs
        // 私有地址不应被任何路由覆盖；抽样关键地址
        listOf(
            "10.1.2.3", "172.16.0.1", "172.31.255.255",
            "192.168.9.9", "127.0.0.1", "169.254.1.1",
        ).forEach { ip ->
            assertTrue("$ip should not be covered", !covers(cidrs, ip))
        }
    }

    @Test
    fun publicCidrs_stillCoverMapdnsAndPublicSamples() {
        val cidrs = CidrRoutes.publicCidrs
        listOf(
            "8.8.8.8", "1.1.1.1", "100.64.0.1",   // CGN fake-ip 必须仍被覆盖
            "198.18.0.1", "220.181.0.1",
        ).forEach { ip ->
            assertTrue("$ip should be covered", covers(cidrs, ip))
        }
    }

    @Test
    fun publicCidrs_routeCountIsBounded() {
        // 路由数需在 VpnService 可接受范围内
        assertTrue(CidrRoutes.publicCidrs.size in 10..80)
    }

    @Test
    fun publicCidrs_areAllAlignedAndNonOverlapping() {
        var prevEnd = -1L
        for (cidr in CidrRoutes.publicCidrs) {
            val (start, end) = rangeOf(cidr)
            assertEquals("misaligned at $cidr", 0L, start % (end - start))
            assertTrue("overlap/order at $cidr", start >= prevEnd)
            prevEnd = end
        }
    }

    private fun covers(cidrs: List<Cidr4>, ip: String): Boolean {
        val value = ip.split(".").fold(0L) { acc, p -> (acc shl 8) + p.toLong() }
        return cidrs.any { cidr ->
            val (start, end) = rangeOf(cidr)
            value in start until end
        }
    }

    private fun rangeOf(cidr: Cidr4): Pair<Long, Long> {
        val hostBits = 32 - cidr.prefix
        val value = cidr.address.split(".").fold(0L) { acc, p -> (acc shl 8) + p.toLong() }
        val network = value shr hostBits shl hostBits
        val size = if (hostBits == 32) 1L shl 32 else 1L shl hostBits
        return network to network + size
    }
}
