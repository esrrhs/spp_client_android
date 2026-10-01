package com.esrrhs.spp.client.util

import java.math.BigInteger

/** 一条 IPv6 CIDR。 */
data class Cidr6(val address: String, val prefix: Int)

/**
 * IPv6「智能分流」路由计算：排除 ULA/link-local 等保留段（以及可选的 CN 分配段），
 * 返回覆盖其余**全球**地址的最小 CIDR 集合。
 *
 * 用 BigInteger 在 128 位空间上做区间运算（路由数量不大，性能足够）。
 */
object Cidr6Routes {

    private val SPACE = BigInteger.ONE.shiftLeft(128)

    /**
     * 非全球单播空间（始终直连）：只在 2000::/3 内做分流。
     * 另外 7 个 /3 段覆盖 2000::/3 之外全部地址（loopback、ULA、link-local、
     * multicast）；再挖掉文档段。
     */
    private val RESERVED = listOf(
        // 2000::/3（最高位 001）之外的全部空间，即另外 7 个 /3：
        "::/3",             // 000：loopback(::1)
        "4000::/3",         // 010
        "6000::/3",         // 011
        "8000::/3",         // 100
        "a000::/3",         // 101
        "c000::/3",         // 110
        "e000::/3",         // 111：ULA(fc00)、link-local(fe80)、multicast(ff)
        "2001:db8::/32",    // documentation
    )

    /** 全球地址路由：仅排除保留段（智能分流但不含 CN 时）。 */
    fun globalCidrs(): List<Cidr6> = globalCidrs(emptyList())

    /**
     * 全球地址路由：排除保留段 + [extraExcluded]（CN IPv6 分配段）。
     *
     * [expandPrefix]：把每个 CN 段扩展到其所在的对齐 /[expandPrefix] 块后合并。
     * 因 establish 的全部路由会打进**一个** Binder parcel（上限约 1MB），精确
     * 集合（v4 约 12k 条、v6 更多）装不下，v4/v6 均需以此压缩路由数。
     */
    fun globalCidrs(extraExcluded: List<String>, expandPrefix: Int = 128): List<Cidr6> {
        val rawExcluded = (RESERVED + expandCn(extraExcluded, expandPrefix))
        val excluded = rawExcluded
            .map { parse(it) }
            .sortedBy { interval -> interval.first }
            .let { merge(it) }

        val freeIntervals = mutableListOf<Pair<BigInteger, BigInteger>>()
        var cursor = BigInteger.ZERO
        for ((start, end) in excluded) {
            if (cursor < start) freeIntervals.add(cursor to start)
            if (end > cursor) cursor = end
        }
        if (cursor < SPACE) freeIntervals.add(cursor to SPACE)

        return freeIntervals.flatMap { (lo, hi) -> intervalToCidrs(lo, hi) }
    }

    /**
     * 把 CN 段扩展到对齐的 /[expandPrefix] 块：prefix 更细的段上取整到该块，
     * 本就更粗的段保留。返回去重后的 CIDR 文本。
     */
    private fun expandCn(cnCidrs: List<String>, expandPrefix: Int): List<String> {
        if (cnCidrs.isEmpty() || expandPrefix >= 128) return cnCidrs
        return cnCidrs.map { cidr ->
            val (addrText, prefixText) = cidr.split("/")
            val prefix = prefixText.trim().toInt()
            if (prefix <= expandPrefix) {
                cidr
            } else {
                val value = parseAddress(addrText.trim())
                val block = value.shiftRight(expandPrefix).shiftLeft(expandPrefix)
                toCompressed(block) + "/" + expandPrefix
            }
        }.distinct()
    }

    private fun merge(intervals: List<Pair<BigInteger, BigInteger>>): List<Pair<BigInteger, BigInteger>> {
        val result = mutableListOf<Pair<BigInteger, BigInteger>>()
        for (interval in intervals) {
            val last = result.lastOrNull()
            if (last != null && interval.first <= last.second) {
                result[result.lastIndex] =
                    (if (last.first < interval.first) last.first else interval.first) to
                        (if (last.second > interval.second) last.second else interval.second)
            } else {
                result.add(interval)
            }
        }
        return result
    }

    /** 空闲区间 [lo, hi) 的最小 CIDR 分解。 */
    internal fun intervalToCidrs(loIn: BigInteger, hi: BigInteger): List<Cidr6> {
        val result = mutableListOf<Cidr6>()
        var lo = loIn
        while (lo < hi) {
            val remaining = hi - lo
            // 受长度限制的最大 2^n 块
            var block = floorPow2(remaining)
            // 受 lo 对齐限制
            val align = if (lo.signum() == 0) SPACE else lowestSetBitPow2(lo)
            if (align < block) block = align
            val prefix = 128 - block.bitLength() + 1
            result.add(Cidr6(toCompressed(lo), prefix))
            lo += block
        }
        return result
    }

    private fun parse(cidr: String): Pair<BigInteger, BigInteger> {
        val (addrText, prefixText) = cidr.split("/")
        val prefix = prefixText.trim().toInt()
        val value = parseAddress(addrText.trim())
        val hostBits = 128 - prefix
        val size = if (hostBits == 128) SPACE else BigInteger.ONE.shiftLeft(hostBits)
        val network = value.shiftRight(hostBits).shiftLeft(hostBits)
        return network to network + size
    }

    private fun floorPow2(v: BigInteger): BigInteger =
        BigInteger.ONE.shiftLeft(v.bitLength() - 1)

    private fun lowestSetBitPow2(v: BigInteger): BigInteger =
        BigInteger.ONE.shiftLeft(v.lowestSetBit)

    /** 解析 IPv6 文本（支持 :: 压缩）为 128 位整数。 */
    internal fun parseAddress(text: String): BigInteger {
        val parts = text.split("::", limit = 2)
        val head = if (parts[0].isEmpty()) emptyList()
        else parts[0].split(":").filter { it.isNotEmpty() }
        val tail = if (parts.size == 2 && parts[1].isNotEmpty())
            parts[1].split(":").filter { it.isNotEmpty() }
        else emptyList()

        val groups = if (parts.size == 2) {
            val missing = 8 - head.size - tail.size
            head + List(missing) { "0" } + tail
        } else {
            head
        }

        var value = BigInteger.ZERO
        for (group in groups) {
            val g = group.toLong(16)
            value = value.shiftLeft(16).or(BigInteger.valueOf(g))
        }
        return value
    }

    /** 128 位整数转带 :: 压缩的规范文本。 */
    internal fun toCompressed(value: BigInteger): String {
        val groups = IntArray(8)
        for (i in 7 downTo 0) {
            groups[i] = value.shiftRight((7 - i) * 16)
                .and(BigInteger.valueOf(0xffff)).toInt()
        }
        // 找最长的连续零段（长度≥2）用于 :: 压缩
        var bestStart = -1; var bestLen = 0
        var s = -1
        for (i in 0..8) {
            if (i < 8 && groups[i] == 0) {
                if (s < 0) s = i
            } else if (s >= 0) {
                val len = i - s
                if (len > bestLen) { bestLen = len; bestStart = s }
                s = -1
            }
        }
        val sb = StringBuilder()
        if (bestLen < 2) {
            groups.forEachIndexed { i, g ->
                if (i > 0) sb.append(':')
                sb.append(Integer.toHexString(g))
            }
            return sb.toString()
        }
        for (i in 0 until bestStart) {
            if (i > 0) sb.append(':')
            sb.append(Integer.toHexString(groups[i]))
        }
        sb.append("::")
        for (i in (bestStart + bestLen)..7) {
            if (i > bestStart + bestLen) sb.append(':')
            sb.append(Integer.toHexString(groups[i]))
        }
        return sb.toString()
    }
}
