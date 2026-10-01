package com.esrrhs.spp.client.util

import android.content.Context

/** 读取 assets 中的中国大陆 IPv4/IPv6 CIDR 列表。 */
object CnRouteList {

    private const val ASSET_V4 = "cn_ipv4_cidr.txt"
    private const val ASSET_V6 = "cn_ipv6_cidr.txt"

    @Volatile private var cachedV4: List<String>? = null
    @Volatile private var cachedV6: List<String>? = null

    fun loadV4(context: Context): List<String> {
        cachedV4?.let { return it }
        val list = read(context, ASSET_V4)
        cachedV4 = list
        return list
    }

    fun loadV6(context: Context): List<String> {
        cachedV6?.let { return it }
        val list = read(context, ASSET_V6)
        cachedV6 = list
        return list
    }

    private fun read(context: Context, asset: String): List<String> =
        context.assets.open(asset).bufferedReader().useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toList()
        }
}
