package com.esrrhs.spp.client.util

import android.content.Context
import android.os.PowerManager
import com.esrrhs.spp.client.data.ConnectionLogEntry
import java.io.File
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
 * 全局连接采集器：VPN 已连接期间由 SppVpnService 驱动。
 *
 * 功耗自适应策略：
 * - 页面在前台查看「当前连接」时：高频 1.5s 刷新并计算完整分组、差分速率；
 * - 亮屏但界面在后台：降频至 5s，仅采样轻量会话行供历史记录，跳过 UI 分组与速率计算；
 * - 灭屏待机：降频至 15s，最大程度减少 CPU 唤醒以利于系统进入 Doze/深睡；
 * - 磁盘日志截断由 4s 一次放宽至 5 分钟一次，消除频繁闪存 I/O。
 */
object ConnectionRecorder {

    private const val TICK_MS = 1500L
    private const val SAVE_INTERVAL_MS = 3000L
    private const val TRIM_INTERVAL_MS = 300_000L // 5 分钟

    private val _liveGroups = MutableStateFlow<List<AppConnectionGroup>>(emptyList())
    val liveGroups: StateFlow<List<AppConnectionGroup>> = _liveGroups.asStateFlow()

    private var job: Job? = null
    private var sample: ConnectionSample = ActiveConnections.empty()
    private var state: ConnectionLogMerge.State = ConnectionLogMerge.State()
    private var pending = ArrayList<ConnectionLogEntry>()
    private var lastSaveMs = 0L
    private var lastTrimMs = 0L

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
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        sample = ActiveConnections.empty()
        state = ConnectionLogMerge.State()
        pending = ArrayList()
        lastSaveMs = System.currentTimeMillis()
        lastTrimMs = System.currentTimeMillis()
        _liveGroups.value = emptyList()

        job = scope.launch(Dispatchers.IO) {
            val repository = ConnectionLogRepository(appContext)
            var lastMs = System.currentTimeMillis()
            while (isActive) {
                val now = System.currentTimeMillis()
                val interval = now - lastMs
                val isUiObserving = _liveGroups.subscriptionCount.value > 0

                val liveRows = if (isUiObserving) {
                    sample = ActiveConnections.snapshot(
                        context = appContext,
                        profile = profile,
                        prev = sample,
                        nowMs = now,
                        intervalMs = interval,
                        directDomains = directDomains,
                    )
                    _liveGroups.value = sample.groups
                    sample.liveRows
                } else {
                    // 没有 UI 观察当前连接，完全跳过 groups 排序、速率差分与留痕计算
                    ActiveConnections.historyRows(
                        context = appContext,
                        profile = profile,
                        directDomains = directDomains,
                    )
                }

                val tick = ConnectionLogMerge.tick(
                    state,
                    liveRows,
                    now,
                    eventFinder,
                    proxyName = profile.name,
                    proxyServer = profile.config.serverAddr,
                )
                state = tick.state
                if (tick.finished.isNotEmpty()) {
                    pending.addAll(tick.finished)
                }
                if (now - lastSaveMs >= SAVE_INTERVAL_MS) {
                    if (pending.isNotEmpty()) {
                        repository.append(pending, now)
                        pending.clear()
                    }
                    lastSaveMs = now
                }
                if (now - lastTrimMs >= TRIM_INTERVAL_MS) {
                    lastTrimMs = now
                    trimRuntimeLogs(appContext)
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
        ActiveConnections.clearAppCache()
        trimRuntimeLogs(context.applicationContext)
    }

    /** hev 以 O_APPEND 持续写 hev.log，连接期间定期截断，停掉后再收一次尾。 */
    private fun trimRuntimeLogs(context: Context) {
        val dir = context.filesDir
        RuntimeLogs.trim(File(dir, "hev.log"))
        RuntimeLogs.trim(File(dir, "spp.log"))
    }
}
