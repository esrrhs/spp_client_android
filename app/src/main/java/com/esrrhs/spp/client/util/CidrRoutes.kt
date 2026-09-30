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
 * CN 模式（chnroute）为**近似**方案：受系统路由承载能力所限，CN 段间 ≤16K 地址
 * 的微小空隙会被一并直连（如 1.1.1.0/24 这类被 CN 分配包围的地址）；个别因此
 * 直连而不可达的站点，应改用全局代理模式。
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
     * 公网路由：排除私有段 + [extraExcluded]（如 CN CIDR 列表）。
     * 调用方保证额外项格式合法。
     */
    fun publicCidrs(
        extraExcluded: List<String>,
        /** 间隙填充：被排除段间小于该绝对值、或小于相邻段相对比例的空隙视为直连。 */
        gapPadding: Long = 0L,
        gapRatio: Double = 0.0,
    ): List<Cidr4> {
        val excluded = (PRIVATE + extraExcluded)
            .map { parse(it) }
            .sortedBy { range -> range.first }
            .let { merge(it) }
            .let {
                if (gapPadding > 0 || gapRatio > 0) padGaps(it, gapPadding, gapRatio) else it
            }

        val freeIntervals = mutableListOf<Pair<Long, Long>>()
        var cursor = 0L
        for ((start, end) in excluded) {
            if (cursor < start) freeIntervals.add(cursor to start)
            if (end > cursor) cursor = end
        }
        if (cursor < SPACE) freeIntervals.add(cursor to SPACE)

        return freeIntervals.flatMap { (lo, hi) -> intervalToCidrs(lo, hi) }
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

    /**
     * 合并相邻区间：当间隙 <= [absPadding]，或间隙 <= [ratio] × 两侧较小区间时
     * （即 CN 包围的小"岛屿"），间隙内地址随之直连。
     */
    private fun padGaps(
        intervals: List<Pair<Long, Long>>,
        absPadding: Long,
        ratio: Double,
    ): List<Pair<Long, Long>> {
        if (intervals.size < 2) return intervals
        val result = mutableListOf<Pair<Long, Long>>()
        var (lo, hi) = intervals.first()
        for (i in 1..intervals.lastIndex) {
            val (nextLo, nextHi) = intervals[i]
            val gap = nextLo - hi
            val island = gap <= absPadding ||
                gap <= ratio * minOf(hi - lo, nextHi - nextLo)
            if (island) {
                hi = nextHi
            } else {
                result.add(lo to hi)
                lo = nextLo; hi = nextHi
            }
        }
        result.add(lo to hi)
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
