package com.esrrhs.spp.client.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.esrrhs.spp.client.data.AppSettings
import com.esrrhs.spp.client.data.ConfigRepository
import com.esrrhs.spp.client.data.HistoryRepository
import com.esrrhs.spp.client.data.SettingsRepository
import com.esrrhs.spp.client.spp.Profile
import com.esrrhs.spp.client.spp.SppProcess
import com.esrrhs.spp.client.spp.ValidationError
import com.esrrhs.spp.client.tun.HevTunnel
import com.esrrhs.spp.client.util.LeakCheck
import com.esrrhs.spp.client.util.SocksProbe
import com.esrrhs.spp.client.util.TrafficMeter
import com.esrrhs.spp.client.util.TunnelCheck
import com.esrrhs.spp.client.vpn.ActiveSession
import com.esrrhs.spp.client.vpn.VpnController
import com.esrrhs.spp.client.vpn.VpnState
import com.esrrhs.spp.client.vpn.VpnStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 连接自检状态。 */
sealed interface SelfCheckState {
    data object Idle : SelfCheckState
    data object Running : SelfCheckState
    data class Done(val result: TunnelCheck.Result) : SelfCheckState
}

/** 防泄漏检测状态。 */
sealed interface LeakState {
    data object Idle : LeakState
    data object Running : LeakState
    data class Done(val report: LeakCheck.Report) : LeakState
}

/**
 * 当前会话的实时数据（叠加在 profile 已累计值之上显示）。
 * [tx]/[rx] 为本轮隧道累计字节；[txRate]/[rxRate] 为实时速率（字节/秒）；
 * [connectedAtMs] 为本次连接建立时刻，用于时长展示。
 */
data class SessionTraffic(
    val tx: Long = 0,
    val rx: Long = 0,
    val txRate: Long = 0,
    val rxRate: Long = 0,
    val connectedAtMs: Long? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = ConfigRepository(app)
    private val settingsRepository = SettingsRepository(app)
    private val historyRepository = HistoryRepository(app)

    val history = historyRepository.records
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val profiles: StateFlow<List<Profile>> = repository.profiles
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val activeId: StateFlow<String?> = repository.activeId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val emptySession = SessionTraffic()
    private val _session = MutableStateFlow(emptySession)
    val session: StateFlow<SessionTraffic> = _session

    private val trafficMeter = TrafficMeter()
    private var connectedAtMs: Long? = null

    private val _testingPings = MutableStateFlow(false)
    val testingPings: StateFlow<Boolean> = _testingPings

    private val _selfCheck = MutableStateFlow<SelfCheckState>(SelfCheckState.Idle)
    val selfCheck: StateFlow<SelfCheckState> = _selfCheck

    /** 已连接时对当前隧道做出口 IP / DNS 路径自检。 */
    fun runSelfCheck() {
        val port = ActiveSession.socksPort
        if (VpnStateHolder.state.value !is VpnState.Connected || port == null) return
        viewModelScope.launch {
            _selfCheck.value = SelfCheckState.Running
            val result = withContext(Dispatchers.IO) { TunnelCheck.run(port) }
            _selfCheck.value = SelfCheckState.Done(result)
        }
    }

    fun dismissSelfCheck() {
        _selfCheck.value = SelfCheckState.Idle
    }

    private val _leak = MutableStateFlow<LeakState>(LeakState.Idle)
    val leak: StateFlow<LeakState> = _leak

    /** IPv4/IPv6/DNS 防泄漏专项检测（经当前隧道）。 */
    fun runLeakCheck() {
        val port = ActiveSession.socksPort
        if (VpnStateHolder.state.value !is VpnState.Connected || port == null) return
        viewModelScope.launch {
            _leak.value = LeakState.Running
            val report = withContext(Dispatchers.IO) { LeakCheck.run(port) }
            _leak.value = LeakState.Done(report)
        }
    }

    init {
        viewModelScope.launch { pollSessionTraffic() }
    }

    /**
     * 点击某条配置：若它正在连接/已连接则断开；否则选中并连接。
     */
    fun onProfileClicked(id: String) {
        val current = VpnStateHolder.state.value
        if (id == activeId.value &&
            (current is VpnState.Connected || current is VpnState.Connecting)
        ) {
            VpnController.disconnect(getApplication())
        } else {
            viewModelScope.launch {
                repository.setActive(id)
                VpnController.connect(getApplication())
            }
        }
    }

    fun saveProfile(profile: Profile, onResult: (ValidationError?) -> Unit) {
        viewModelScope.launch {
            // 新建配置套用全局默认「绕过局域网」
            val toSave = if (profiles.value.none { it.id == profile.id } &&
                settings.value.defaultBypassLan && !profile.bypassLan
            ) {
                profile.copy(bypassLan = true)
            } else {
                profile
            }
            val error = toSave.validate()
            if (error == null) repository.upsert(toSave)
            onResult(error)
        }
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }

    fun updateSettings(settings: AppSettings) {
        viewModelScope.launch { settingsRepository.save(settings) }
    }

    /**
     * 单个配置延迟测试：经 SPP 隧道发起真实 SOCKS5 建连（含认证与远端 DNS）。
     * 已连接的当前配置直接走运行中的隧道；其它配置临时拉起 socks5_client 实测后关闭。
     */
    fun testPing(id: String) {
        viewModelScope.launch {
            val profile = profiles.value.firstOrNull { it.id == id } ?: return@launch
            val ms = measureRealLatency(profile)
            repository.updatePing(id, ms)
        }
    }

    /** 测全部配置延迟（逐个临时建隧道）。 */
    fun testAllPings() {
        if (_testingPings.value) return
        viewModelScope.launch {
            _testingPings.value = true
            try {
                profiles.value.forEach { profile ->
                    repository.updatePing(profile.id, measureRealLatency(profile))
                }
            } finally {
                _testingPings.value = false
            }
        }
    }

    /**
     * 返回经隧道 CONNECT 到稳定目标的耗时（ms），失败 -1。
     * 当前活动配置复用已运行端口；其余配置临时拉起 socks5_client，
     * 因此错误的 key/encrypt 或不可达 server 都会得到 -1。
     */
    private suspend fun measureRealLatency(profile: Profile): Int =
        withContext(Dispatchers.IO) {
            val activePort = ActiveSession.socksPort
            val isActive = profile.id == activeId.value &&
                VpnStateHolder.state.value is VpnState.Connected && activePort != null
            if (isActive) {
                SocksProbe.measure(activePort!!)
            } else {
                var proc: SppProcess? = null
                try {
                    proc = SppProcess(getApplication())
                    val port = proc.start(profile.config)
                    SocksProbe.measure(port)
                } catch (e: Exception) {
                    -1
                } finally {
                    proc?.stop()
                }
            }
        }

    /** 选中延迟最低的配置（忽略未测/失败）。 */
    fun selectFastest() {
        viewModelScope.launch {
            val fastest = profiles.value.filter { it.pingMs >= 0 }.minByOrNull { it.pingMs }
            if (fastest != null) repository.setActive(fastest.id)
        }
    }

    /** 导入扫码得到的配置。 */
    fun importScanned(profile: Profile) {
        viewModelScope.launch { repository.importProfiles(listOf(profile)) }
    }

    suspend fun importProfiles(list: List<Profile>) = repository.importProfiles(list)

    /** 清空所有配置的累计流量统计。 */
    fun resetTraffic() {
        viewModelScope.launch { repository.resetTraffic() }
    }

    /** 清空指定配置的累计流量统计。 */
    fun resetTrafficFor(id: String) {
        viewModelScope.launch { repository.resetTrafficFor(id) }
    }

    /** 清空连接历史。 */
    fun clearHistory() {
        viewModelScope.launch { historyRepository.clear() }
    }

    /** 返回当前选中配置 id（无显式选择时取第一条）；无配置返回 null。 */
    suspend fun awaitActiveProfileId(): String? {
        val list = repository.profiles.first()
        val active = repository.activeId.first()
        return list.firstOrNull { it.id == active }?.id ?: list.firstOrNull()?.id
    }

    /**
     * 已连接时每秒取隧道计数，展示本会话字节与实时速率；
     * 重连中保持上一次数据；断开/出错则清零。
     */
    private suspend fun pollSessionTraffic() {
        while (true) {
            delay(POLL_INTERVAL_MS)
            val state = VpnStateHolder.state.value
            when {
                state is VpnState.Connected -> {
                    val raw = HevTunnel.stats()
                    if (raw != null) {
                        val now = System.currentTimeMillis()
                        val tx = raw.getOrNull(1) ?: 0L
                        val rx = raw.getOrNull(3) ?: 0L
                        if (connectedAtMs == null) {
                            connectedAtMs = now
                            trafficMeter.rebaseline(tx, rx, now)
                        }
                        val rates = trafficMeter.update(tx, rx, now)
                        _session.value = SessionTraffic(
                            tx = tx,
                            rx = rx,
                            txRate = rates.txBytesPerSec,
                            rxRate = rates.rxBytesPerSec,
                            connectedAtMs = connectedAtMs,
                        )
                    }
                }
                // 重连过渡态：保留上一次的会话数据
                state is VpnState.Connecting || state is VpnState.Disconnecting -> Unit
                else -> {
                    trafficMeter.reset()
                    connectedAtMs = null
                    _session.value = emptySession
                }
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1000L
    }
}
