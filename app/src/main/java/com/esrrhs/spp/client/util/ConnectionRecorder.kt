package com.esrrhs.spp.client.util

import android.content.Context
import com.esrrhs.spp.client.data.ConnectionLogEntry
import com.esrrhs.spp.client.data.ConnectionLogRepository
import com.esrrhs.spp.client.proxy.ProxyEventBus
import com.esrrhs.spp.client.spp.Profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全局连接采集器：VPN 已连接期间由 SppVpnService 驱动，与界面是否打开无关。
 *
 * - [liveGroups]：实时「当前连接」分组，页面直接订阅；
 * - 每条 hev 会话经 [ConnectionLogMerge] 跟踪生命周期，结束时落一条
 *   [ConnectionLogEntry]（方式/结果/原因/字节/起止），供历史页溯源。
 */
object ConnectionRecorder {

    private const val TICK_MS = 1500L
    private const val SAVE_INTERVAL_MS = 4000L

    private val _liveGroups = MutableStateFlow<List<AppConnectionGroup>>(emptyList())
    val liveGroups: StateFlow<List<AppConnectionGroup>> = _liveGroups.asStateFlow()

    private var job: Job? = null
    private var sample: ConnectionSample = ActiveConnections.empty()
    private var state: ConnectionLogMerge.State = ConnectionLogMerge.State()
    private var pending = ArrayList<ConnectionLogEntry>()
    private var lastSaveMs = 0L

    private val eventFinder = ConnectEventFinder { host, port, notBefore ->
        ProxyEventBus.find(host, port, notBefore)?.let {
            ConnectEventInfo(it.direct, it.success, it.reason, it.durationMs)
        }
    }

    /** 开始一轮 VPN 会话的采集；[profile]/[directDomains] 在本次会话内固定。 */
    fun start(
        scope: CoroutineScope,
        context: Context,
        profile: Profile,
        directDomains: Set<String>,
    ) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        sample = ActiveConnections.empty()
        state = ConnectionLogMerge.State()
        pending = ArrayList()
        lastSaveMs = 0L
        _liveGroups.value = emptyList()

        job = scope.launch(Dispatchers.IO) {
            val repository = ConnectionLogRepository(appContext)
            var lastMs = System.currentTimeMillis()
            while (isActive) {
                val now = System.currentTimeMillis()
                val interval = now - lastMs
                sample = ActiveConnections.snapshot(
                    context = appContext,
                    profile = profile,
                    prev = sample,
                    nowMs = now,
                    intervalMs = interval,
                    directDomains = directDomains,
                )
                _liveGroups.value = sample.groups
                val tick = ConnectionLogMerge.tick(
                    state,
                    sample.liveRows,
                    now,
                    eventFinder,
                    proxyName = profile.name,
                    proxyServer = profile.config.serverAddr,
                )
                state = tick.state
                if (tick.finished.isNotEmpty()) {
                    pending.addAll(tick.finished)
                }
                if (pending.isNotEmpty() && now - lastSaveMs >= SAVE_INTERVAL_MS) {
                    repository.append(pending, now)
                    pending.clear()
                    lastSaveMs = now
                }
                lastMs = now
                delay(TICK_MS)
            }
        }
    }

    /** 停止采集：把残留会话收尾并立即落盘（在 service 协程内调用）。 */
    suspend fun stop(context: Context) {
        job?.cancel()
        job = null
        val now = System.currentTimeMillis()
        val tail = ConnectionLogMerge.finishAll(state, now, eventFinder)
        state = ConnectionLogMerge.State()
        _liveGroups.value = emptyList()
        withContext(Dispatchers.IO) {
            val all = pending + tail
            if (all.isNotEmpty()) {
                ConnectionLogRepository(context.applicationContext).append(all, now)
            }
        }
        pending.clear()
        sample = ActiveConnections.empty()
    }
}
