package com.esrrhs.spp.client.vpn

import kotlin.math.min

/**
 * 断线/网络切换重连退避（纯逻辑，便于单测）。
 *
 * 1s、2s、4s、8s、16s，之后封顶 16s（与原内联实现一致）。
 */
object ReconnectBackoff {

    const val MAX_ATTEMPTS = 5
    private const val BASE_MS = 1000L
    private const val MAX_SHIFT = 4

    fun delayMs(attempt: Int): Long {
        require(attempt >= 1) { "attempt must be >= 1, was $attempt" }
        return BASE_MS shl min(attempt - 1, MAX_SHIFT)
    }
}
