package com.esrrhs.spp.client.util

/** 一条 IPv4 CIDR。 */
data class Cidr4(val address: String, val prefix: Int)

/**
 * 「绕过局域网/私有网段」路由计算：返回覆盖全部**公网** IPv4 的最小 CIDR 集合，
 * VpnService 只把这些路由指向 TUN，私有/保留地址自然走底层直连。
 *
 * 注意：100.64.0.0/10（mapdns fake-ip 段）与 198.18.0.0/15 必须仍指向 TUN，
 * 因此不在排除列表中。
 *
 * CN 模式（chnroute）：CN 段直连、其余公网地址代理。因 establish 的全部路由
 * 会打进**一个** Binder parcel（上限约 1MB），CN 段需扩展到对齐 /N 块以压缩
 * 路由数（v4/v6 同理）。
 */
object CidrRoutes {

    /** 绕过模式下排除的私有/保留网段（dotted/prefix）。 */
    private val PRIVATE = listOf(
        "0.0.0.0/8",       // "this" network
        "10.0.0.0/8",      // RFC1918
        "127.0.0.0/8",     // loopback
        "169.254.0.0/16",  // link-local
        "172.16.0.0/12",   // RFC1918
        "192.168.0.0/16",  // RFC1918
        "224.0.0.0/4",     // multicast
        "240.0.0.0/4",     // reserved
    )

    private const val SPACE = 1L shl 32

    /** 仅排除私有段时的公网路由（结果固定，惰性计算一次）。 */
    val publicCidrs: List<Cidr4> by lazy { publicCidrs(emptyList()) }

    /**
     * 公网路由：排除私有段 + [extraExcluded]（CN CIDR 列表）。
     * [expandPrefix]：把每个 CN 段扩展到对齐 /N 块后合并，用于适配 parcel 上限。
     */
    fun publicCidrs(
        extraExcluded: List<String>,
        expandPrefix: Int = 32,
    ): List<Cidr4> {
        val excluded = (PRIVATE + expandCn(extraExcluded, expandPrefix))
            .map { parse(it) }
            .sortedBy { range -> range.first }
            .let { merge(it) }

        val freeIntervals = mutableListOf<Pair<Long, Long>>()
        var cursor = 0L
        for ((start, end) in excluded) {
            if (cursor < start) freeIntervals.add(cursor to start)
            if (end > cursor) cursor = end
        }
        if (cursor < SPACE) freeIntervals.add(cursor to SPACE)

        return freeIntervals.flatMap { (lo, hi) -> intervalToCidrs(lo, hi) }
    }

    /** CN 段扩展到对齐 /[expandPrefix] 块；更粗的段保留。 */
    private fun expandCn(cnCidrs: List<String>, expandPrefix: Int): List<String> {
        if (cnCidrs.isEmpty() || expandPrefix >= 32) return cnCidrs
        return cnCidrs.map { cidr ->
            val (addr, prefixText) = cidr.split("/")
            val prefix = prefixText.toInt()
            if (prefix <= expandPrefix) {
                cidr
            } else {
                val value = addr.split(".").fold(0L) { acc, p -> (acc shl 8) + p.toLong() }
                val hostBits = 32 - expandPrefix
                val network = value shr hostBits shl hostBits
                toDotted(network) + "/" + expandPrefix
            }
        }.distinct()
    }

    private fun merge(intervals: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        val result = mutableListOf<Pair<Long, Long>>()
        for (interval in intervals) {
            val last = result.lastOrNull()
            if (last != null && interval.first <= last.second) {
                result[result.lastIndex] = last.first.coerceAtMost(interval.first) to
                    last.second.coerceAtLeast(interval.second)
            } else {
                result.add(interval)
            }
        }
        return result
    }

    /** 空闲区间 [lo, hi) 的最小 CIDR 分解。 */
    internal fun intervalToCidrs(loIn: Long, hi: Long): List<Cidr4> {
        val result = mutableListOf<Cidr4>()
        var lo = loIn
        while (lo < hi) {
            val remaining = hi - lo
            val maxByLength = floorPow2(remaining)
            // lo 的对齐度（lo==0 时按整个 2^32 空间对齐）
            val maxByAlign = if (lo == 0L) SPACE else lowestSetBitPow2(lo)
            val block = minOf(maxByLength, maxByAlign)
            val prefix = (32 - log2(block)).toInt()
            result.add(Cidr4(toDotted(lo), prefix))
            lo += block
        }
        return result
    }

    private fun parse(cidr: String): Pair<Long, Long> {
        val (addr, prefixText) = cidr.split("/")
        val prefix = prefixText.toInt()
        val value = addr.split(".").fold(0L) { acc, part -> (acc shl 8) + part.toLong() }
        val hostBits = 32 - prefix
        val network = value shr hostBits shl hostBits
        val size = if (hostBits == 32) SPACE else 1L shl hostBits
        return network to network + size
    }

    private fun floorPow2(v: Long): Long = java.lang.Long.highestOneBit(v)

    private fun lowestSetBitPow2(v: Long): Long = java.lang.Long.lowestOneBit(v)

    private fun log2(v: Long): Int = java.lang.Long.numberOfTrailingZeros(v)

    private fun toDotted(v: Long): String =
        "${(v ushr 24) and 255}.${(v ushr 16) and 255}.${(v ushr 8) and 255}.${v and 255}"
}
