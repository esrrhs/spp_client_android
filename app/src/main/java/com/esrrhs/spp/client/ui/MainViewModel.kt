package com.esrrhs.spp.client.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.esrrhs.spp.client.data.AppSettings
import com.esrrhs.spp.client.data.ConfigRepository
import com.esrrhs.spp.client.data.SettingsRepository
import com.esrrhs.spp.client.spp.Profile
import com.esrrhs.spp.client.tun.HevTunnel
import com.esrrhs.spp.client.util.ServerPing
import com.esrrhs.spp.client.util.TrafficMeter
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

    fun saveProfile(profile: Profile, onResult: (String?) -> Unit) {
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

    /** 单个配置延迟测试。 */
    fun testPing(id: String) {
        viewModelScope.launch {
            val profile = profiles.value.firstOrNull { it.id == id } ?: return@launch
            val ms = withContext(Dispatchers.IO) {
                ServerPing.measure(profile.config.serverHost, profile.config.serverPort)
            }
            repository.updatePing(id, ms)
        }
    }

    /** 测全部配置延迟。 */
    fun testAllPings() {
        if (_testingPings.value) return
        viewModelScope.launch {
            _testingPings.value = true
            try {
                profiles.value.forEach { profile ->
                    val ms = withContext(Dispatchers.IO) {
                        ServerPing.measure(profile.config.serverHost, profile.config.serverPort)
                    }
                    repository.updatePing(profile.id, ms)
                }
            } finally {
                _testingPings.value = false
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
