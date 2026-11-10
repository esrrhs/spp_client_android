package com.esrrhs.spp.client.proxy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 结构化的内部代理管道时序记录（用于渲染可视化瀑布图/甘特图）。
 * 剖析每个连接在各个内部阶段的耗时与传输情况：
 * - 规则路由 (Route)
 * - 目标 DNS 解析 / 直连建连 (DNS / Direct Connect)
 * - 上游隧道建连 (Upstream Connect)
 * - 隧道协议握手 (Handshake)
 * - 等待首包响应 / TTFB (Waiting first packet)
 * - 数据传输与双向关闭 (Data Piping & Close)
 */
data class InternalPipelineTiming(
    val id: String = java.util.UUID.randomUUID().toString(),
    val host: String,
    val port: Int,
    val isDirect: Boolean,
    val startTimeMs: Long = System.currentTimeMillis(),
    val routeMs: Long = 0L,
    val dnsOrConnectMs: Long = 0L,
    val handshakeMs: Long = 0L,
    val waitFirstDataMs: Long = 0L,
    val dataTransferMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val uploadBytes: Long = 0L,
    val downloadBytes: Long = 0L,
    val status: String = "ACTIVE", // CONNECTING, ACTIVE, CLOSED, ERROR
    val errorReason: String? = null,
) {
    val totalTime: Long
        get() = if (totalDurationMs > 0) totalDurationMs else (System.currentTimeMillis() - startTimeMs).coerceAtLeast(1L)
}

/**
 * 细粒度的代理内部运转事件（用于诊断分析 App 内部到底卡在哪个阶段、发了什么、收了什么）。
 */
data class InternalProxyTrace(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    val stage: String,     // e.g., "ROUTE", "DNS", "HANDSHAKE", "UPSTREAM", "DIRECT", "DATA", "CLOSE"
    val host: String,
    val port: Int,
    val detail: String,
    val isError: Boolean = false,
)

/** 一次 TCP CONNECT 的判定与结果（由 [RuleSocksServer] 产生）。 */
data class ProxyConnectEvent(
    /** 请求目标：域名或 IP 字面量。 */
    val host: String,
    val port: Int,
    /** true=本地直连（命中域名规则）；false=转发 SPP 隧道。 */
    val direct: Boolean,
    val success: Boolean,
    /** 失败原因摘要（成功时为 null）。 */
    val reason: String?,
    val durationMs: Int,
    val ts: Long,
)

/**
 * 进程内短期事件缓冲：分流器在 TCP CONNECT 结束时写入，
 * 全局连接记录器据此把每条会话标注为「直连/代理」「成功/失败」及原因。
 * UDP 无 CONNECT 过程，不会有事件。
 */
object ProxyEventBus {

    private const val MAX_AGE_MS = 120_000L
    private const val MAX_EVENTS = 2000

    private val queue = ConcurrentLinkedQueue<ProxyConnectEvent>()

    private val _traces = MutableStateFlow<List<InternalProxyTrace>>(emptyList())
    val traces: StateFlow<List<InternalProxyTrace>> = _traces.asStateFlow()

    private val _pipelines = MutableStateFlow<List<InternalPipelineTiming>>(emptyList())
    val pipelines: StateFlow<List<InternalPipelineTiming>> = _pipelines.asStateFlow()

    fun updatePipeline(pipeline: InternalPipelineTiming) {
        val current = _pipelines.value.toMutableList()
        val index = current.indexOfFirst { it.id == pipeline.id }
        if (index >= 0) {
            current[index] = pipeline
        } else {
            current.add(pipeline)
            if (current.size > 200) {
                current.removeAt(0)
            }
        }
        _pipelines.value = current
    }

    fun logTrace(stage: String, host: String, port: Int, detail: String, isError: Boolean = false) {
        val trace = InternalProxyTrace(
            stage = stage,
            host = host,
            port = port,
            detail = detail,
            isError = isError,
        )
        val current = _traces.value.toMutableList()
        current.add(trace)
        if (current.size > 500) {
            current.removeAt(0)
        }
        _traces.value = current
    }

    fun clearTraces() {
        _traces.value = emptyList()
        _pipelines.value = emptyList()
    }

    fun record(
        host: String,
        port: Int,
        direct: Boolean,
        success: Boolean,
        reason: String?,
        durationMs: Int,
    ) {
        val now = System.currentTimeMillis()
        queue.add(
            ProxyConnectEvent(
                host = host,
                port = port,
                direct = direct,
                success = success,
                reason = reason,
                durationMs = durationMs,
                ts = now,
            ),
        )
        prune(now)
    }

    /**
     * 查找 [notBeforeMs] 之后目标匹配（host 相等、端口一致）的最新事件；
     * 优先返回失败事件（一个目标可能重试多次）。消费后不移除（同一目标多会话允许复用）。
     */
    fun find(host: String?, port: Int, notBeforeMs: Long): ProxyConnectEvent? {
        if (host.isNullOrBlank()) return null
        val now = System.currentTimeMillis()
        var bestFailure: ProxyConnectEvent? = null
        var bestSuccess: ProxyConnectEvent? = null
        val it = queue.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.ts > MAX_AGE_MS || e.ts < notBeforeMs || e.port != port) continue
            if (!hostEquals(e.host, host)) continue
            // 同目标可能重试多次：失败优先，其次取最新
            if (e.success) {
                if (bestSuccess == null || e.ts > bestSuccess.ts) bestSuccess = e
            } else {
                if (bestFailure == null || e.ts > bestFailure.ts) bestFailure = e
            }
        }
        return bestFailure ?: bestSuccess
    }

    private fun hostEquals(a: String, b: String): Boolean =
        a.equals(b, ignoreCase = true).let { eq ->
            if (eq) true else a.trimEnd('.').equals(b.trimEnd('.'), ignoreCase = true)
        }

    private fun prune(now: Long) {
        while (true) {
            val head = queue.peek() ?: break
            if (now - head.ts <= MAX_AGE_MS && queue.size <= MAX_EVENTS) break
            queue.poll()
        }
    }
}
