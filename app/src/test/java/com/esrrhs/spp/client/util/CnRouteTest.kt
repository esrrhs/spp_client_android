package com.esrrhs.spp.client.util

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 基于真实 APNIC chnroute 数据的验证（数据文件缺失时跳过，CI 因仓库包含该文件而执行）。
 */
class CnRouteTest {

    private val asset = File("src/main/assets/cn_ipv4_cidr.txt")

    private fun requireCnList(): List<String> {
        assumeTrue("chnroute asset missing", asset.exists())
        return asset.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    private fun routesWithCnBypass() =
        CidrRoutes.publicCidrs(requireCnList(), CN_GAP_PADDING)

    @Test
    fun cnDirect_cnSamplesAreNotRouted() {
        val routes = routesWithCnBypass()
        listOf(
            "1.0.1.1", "114.114.114.114", "180.76.76.76",
            "210.2.4.8", "220.181.38.148",
        ).forEach { ip ->
            assertTrue("$ip should go direct", !covers(routes, ip))
        }
    }

    @Test
    fun cnDirect_foreignSamplesAreRouted() {
        val routes = routesWithCnBypass()
        listOf(
            "8.8.8.8", "9.9.9.9", "208.67.222.222", "20.44.145.247",
        ).forEach { ip ->
            assertTrue("$ip should be proxied", covers(routes, ip))
        }
    }

    @Test
    fun cnDirect_privateRangesStillBypassed() {
        val routes = routesWithCnBypass()
        listOf("10.0.0.1", "192.168.1.1", "127.0.0.1").forEach { ip ->
            assertTrue("$ip should stay direct", !covers(routes, ip))
        }
    }

    @Test
    fun cnDirect_routeCountIsAcceptable() {
        val routes = routesWithCnBypass()
        println("routes with CN bypass: ${routes.size}")
        assertTrue("too many routes: ${routes.size}", routes.size in 3000..6000)
    }

    private companion object {
        const val CN_GAP_PADDING = 16_384L
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
