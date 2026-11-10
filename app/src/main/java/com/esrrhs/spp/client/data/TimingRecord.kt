package com.esrrhs.spp.client.data

import kotlinx.serialization.Serializable

/**
 * 单条网络请求的时序明细（兼容 Navigation Timing 与 Resource Timing API）。
 * 时间单位统一为毫秒（ms）。
 */
@Serializable
data class TimingRecord(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val initiatorType: String = "",
    val startTime: Double = 0.0,
    val responseEnd: Double = 0.0,
    val duration: Double = 0.0,
    // 阶段细分
    val dnsStart: Double = 0.0,
    val dnsEnd: Double = 0.0,
    val connectStart: Double = 0.0,
    val connectEnd: Double = 0.0,
    val sslStart: Double = 0.0,
    val sslEnd: Double = 0.0,
    val requestStart: Double = 0.0,
    val responseStart: Double = 0.0,
    val transferSize: Long = 0L,
    val decodedBodySize: Long = 0L,
    val nextHopProtocol: String = "",
    val pageUrl: String = "",
    val timeOrigin: Double = 0.0, // 页面全局时间起点 window.performance.timeOrigin (毫秒 Unix 时间戳)
) {
    /** 绝对起始绝对时间戳 (ms Unix epoch) */
    val absoluteStartMs: Long
        get() = if (timeOrigin > 0.0) (timeOrigin + startTime).toLong() else 0L

    /** DNS 查找耗时（ms） */
    val dnsDuration: Double
        get() = if (dnsEnd > dnsStart && dnsStart > 0) dnsEnd - dnsStart else 0.0

    /** TCP 握手耗时（含 SSL） */
    val connectDuration: Double
        get() = if (connectEnd > connectStart && connectStart > 0) connectEnd - connectStart else 0.0

    /** SSL/TLS 握手耗时 */
    val sslDuration: Double
        get() = if (sslEnd > sslStart && sslStart > 0) sslEnd - sslStart else 0.0

    /** TTFB (Time to First Byte): 从发出请求到收到第一个响应字节 */
    val ttfb: Double
        get() = if (responseStart > requestStart && requestStart > 0) responseStart - requestStart else 0.0

    /** 内容下载耗时 */
    val downloadDuration: Double
        get() = if (responseEnd > responseStart && responseStart > 0) responseEnd - responseStart else 0.0

    /** 是否有完整的网络建连阶段（缓存或复用连接的请求可能为 0） */
    val hasConnectionPhase: Boolean
        get() = dnsDuration > 0 || connectDuration > 0
}
