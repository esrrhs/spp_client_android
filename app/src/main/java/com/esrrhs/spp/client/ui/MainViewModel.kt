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
import com.esrrhs.spp.client.vpn.VpnController
import com.esrrhs.spp.client.vpn.VpnState
import com.esrrhs.spp.client.vpn.VpnStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 当前会话的实时字节增量（叠加在 profile 已累计值之上显示）。 */
data class SessionTraffic(val tx: Long, val rx: Long)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = ConfigRepository(app)
    private val settingsRepository = SettingsRepository(app)

    val profiles: StateFlow<List<Profile>> = repository.profiles
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val activeId: StateFlow<String?> = repository.activeId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val emptySession = SessionTraffic(0, 0)
    private val _session = MutableStateFlow(emptySession)
    val session: StateFlow<SessionTraffic> = _session

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

    /** 已连接时每秒取隧道计数，展示本会话实时字节；断开则清零。 */
    private suspend fun pollSessionTraffic() {
        while (true) {
            delay(POLL_INTERVAL_MS)
            val raw = if (VpnStateHolder.state.value is VpnState.Connected) {
                HevTunnel.stats()
            } else null
            _session.value = if (raw == null) {
                emptySession
            } else {
                SessionTraffic(raw.getOrNull(1) ?: 0L, raw.getOrNull(3) ?: 0L)
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1000L
    }
}
