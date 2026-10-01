package com.esrrhs.spp.client.util

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 基于真实 APNIC chnroute 数据的验证（数据文件缺失时跳过，CI 因仓库包含该文件而执行）。
 * 同时覆盖精确集合与生产环境实际使用的 /21 块聚合集合。
 */
class CnRouteTest {

    private val asset = File("src/main/assets/cn_ipv4_cidr.txt")

    private fun requireCnList(): List<String> {
        assumeTrue("chnroute asset missing", asset.exists())
        return asset.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    // ---- 精确模式（API 默认值） ----

    private fun exactRoutes() = CidrRoutes.publicCidrs(requireCnList())

    @Test
    fun exact_cnSamplesAreNotRouted() {
        val routes = exactRoutes()
        listOf(
            "1.0.1.1", "114.114.114.114", "180.76.76.76",
            "210.2.4.8", "220.181.38.148",
        ).forEach { ip ->
            assertTrue("$ip should go direct", !covers(routes, ip))
        }
    }

    @Test
    fun exact_foreignSamplesAreRouted() {
        val routes = exactRoutes()
        listOf(
            "8.8.8.8", "1.1.1.1", "9.9.9.9", "208.67.222.222", "20.44.145.247",
        ).forEach { ip ->
            assertTrue("$ip should be proxied", covers(routes, ip))
        }
    }

    @Test
    fun exact_privateRangesStillBypassed() {
        val routes = exactRoutes()
        listOf("10.0.0.1", "192.168.1.1", "127.0.0.1").forEach { ip ->
            assertTrue("$ip should stay direct", !covers(routes, ip))
        }
    }

    @Test
    fun exact_routeCountIsExactSet() {
        val routes = exactRoutes()
        println("routes with exact CN bypass: ${routes.size}")
        assertTrue("unexpected route count: ${routes.size}", routes.size in 11000..13000)
    }

    // ---- /21 块聚合模式（SppVpnService 生产配置） ----

    private fun aggregatedRoutes() =
        CidrRoutes.publicCidrs(requireCnList(), EXPAND_PREFIX)

    @Test
    fun aggregated_cnSamplesAreNotRouted() {
        val routes = aggregatedRoutes()
        listOf(
            "1.0.1.1", "114.114.114.114", "180.76.76.76",
            "210.2.4.8", "220.181.38.148",
        ).forEach { ip ->
            assertTrue("$ip should go direct", !covers(routes, ip))
        }
    }

    @Test
    fun aggregated_foreignSamplesAreRouted() {
        val routes = aggregatedRoutes()
        // 注意：1.1.1.1 不适用——它与 CN 1.1.0.0/24 同处一个 /21 块，会直连
        listOf("8.8.8.8", "208.67.222.222", "20.44.145.247").forEach { ip ->
            assertTrue("$ip should be proxied", covers(routes, ip))
        }
    }

    @Test
    fun aggregated_privateRangesStillBypassed() {
        val routes = aggregatedRoutes()
        listOf("10.0.0.1", "192.168.1.1", "127.0.0.1").forEach { ip ->
            assertTrue("$ip should stay direct", !covers(routes, ip))
        }
    }

    @Test
    fun aggregated_routeCountIsBoundedForBinderParcel() {
        val routes = aggregatedRoutes()
        println("routes with /21-aggregated CN bypass: ${routes.size}")
        assertTrue("unexpected route count: ${routes.size}", routes.size in 6000..9000)
    }

    @Test
    fun aggregated_foreignAddressInsideCnBlockAlsoGoesDirect() {
        // 块聚合近似的已知代价：1.1.0.0/24(CN) 扩展为 1.1.0.0/21，覆盖 1.1.1.1
        val routes = aggregatedRoutes()
        assertTrue("1.1.1.1 goes direct due to /21 padding", !covers(routes, "1.1.1.1"))
    }

    private companion object {
        const val EXPAND_PREFIX = 21
    }

    private fun covers(cidrs: List<Cidr4>, ip: String): Boolean {
        val value = ip.split(".").fold(0L) { acc, p -> (acc shl 8) + p.toLong() }
        return cidrs.any { cidr ->
            val hostBits = 32 - cidr.prefix
            val network = cidr.address.split(".")
                .fold(0L) { acc, p -> (acc shl 8) + p.toLong() }
                .shr(hostBits).shl(hostBits)
            value >= network && value < network + (1L shl hostBits)
        }
    }
}
