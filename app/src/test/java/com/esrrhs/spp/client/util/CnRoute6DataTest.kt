package com.esrrhs.spp.client.util

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 基于真实 APNIC CN IPv6 数据的验证（缺文件则跳过）。 */
class CnRoute6DataTest {

    private val asset = File("src/main/assets/cn_ipv6_cidr.txt")

    private fun cnV6(): List<String> {
        assumeTrue("cn v6 asset missing", asset.exists())
        return asset.readLines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    @Test
    fun cnV6_cnPrefixesGoDirect() {
        val routes = Cidr6Routes.globalCidrs(cnV6(), EXPAND_PREFIX)
        // 从 CN 分配段中取样（网段起始地址；扩展到 /22 块后仍直连）
        cnV6().take(50).map { it.substringBefore("/") }.forEach { ip ->
            assertTrue("$ip (CN) should go direct", !covers6(routes, ip))
        }
    }

    @Test
    fun cnV6_nonCnGlobalAddressesAreProxied() {
        val routes = Cidr6Routes.globalCidrs(cnV6(), EXPAND_PREFIX)
        listOf(
            "2001:4860:4860::8888", "2606:4700:4700::1111", "2620:fe::fe",
        ).forEach { ip ->
            assertTrue("$ip should be proxied", covers6(routes, ip))
        }
    }

    @Test
    fun cnV6_routeCountIsBounded() {
        val routes = Cidr6Routes.globalCidrs(cnV6(), EXPAND_PREFIX)
        println("v6 routes with CN bypass: ${routes.size}")
        assertTrue("unexpected v6 route count: ${routes.size}", routes.size in 100..400)
    }

    private companion object {
        const val EXPAND_PREFIX = 22
    }

    private fun covers6(cidrs: List<Cidr6>, ip: String): Boolean {
        val value = Cidr6Routes.parseAddress(ip)
        return cidrs.any { cidr ->
            val network = Cidr6Routes.parseAddress(cidr.address)
            val hostBits = 128 - cidr.prefix
            val size = if (hostBits == 128) java.math.BigInteger.ONE.shiftLeft(128)
            else java.math.BigInteger.ONE.shiftLeft(hostBits)
            value >= network && value < network + size
        }
    }
}
