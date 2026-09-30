package com.esrrhs.spp.client.util

import java.net.InetSocketAddress
import java.net.Socket

/**
 * 服务器延迟测试：到 server 地址的 TCP 握手耗时（ICMP ping 需 root，不可用）。
 */
object ServerPing {

    /**
     * 多次 TCP 握手取样，返回最小耗时（ms）；全部失败返回 -1。
     */
    fun measure(host: String, port: Int, samples: Int = 3, timeoutMs: Int = 3000): Int {
        var best = -1
        repeat(samples) {
            val ms = oneSample(host, port, timeoutMs)
            if (ms >= 0 && (best == -1 || ms < best)) best = ms
        }
        return best
    }

    private fun oneSample(host: String, port: Int, timeoutMs: Int): Int =
        runCatching {
            val start = System.nanoTime()
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            ((System.nanoTime() - start) / 1_000_000).toInt()
        }.getOrDefault(-1)
}
