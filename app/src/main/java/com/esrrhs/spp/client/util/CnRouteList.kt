package com.esrrhs.spp.client.util

import android.content.Context

/** 读取 assets 中的中国大陆 CIDR 列表。 */
object CnRouteList {

    private const val ASSET = "cn_ipv4_cidr.txt"

    @Volatile
    private var cached: List<String>? = null

    /** 读取 CN CIDR 列表（进程内缓存）。 */
    fun load(context: Context): List<String> {
        cached?.let { return it }
        val list = context.assets.open(ASSET).bufferedReader().useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toList()
        }
        cached = list
        return list
    }
}
