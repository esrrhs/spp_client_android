package com.esrrhs.spp.client.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.esrrhs.spp.client.data.ConfigRepository
import com.esrrhs.spp.client.spp.SppConfig
import com.esrrhs.spp.client.tun.HevTunnel
import com.esrrhs.spp.client.vpn.VpnState
import com.esrrhs.spp.client.vpn.VpnStateHolder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 累计流量与实时速率（字节）。 */
data class TrafficStats(
    val txBytes: Long,
    val rxBytes: Long,
    val txRate: Long,
    val rxRate: Long,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ConfigRepository(application)

    private val _form = MutableStateFlow(SppConfig())
    val form: StateFlow<SppConfig> = _form.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val _traffic = MutableStateFlow<TrafficStats?>(null)
    val traffic: StateFlow<TrafficStats?> = _traffic.asStateFlow()

    init {
        viewModelScope.launch {
            _form.value = repository.config.first()
        }
        viewModelScope.launch { pollTrafficStats() }
    }

    fun updateForm(config: SppConfig) {
        _form.value = config
    }

    /** 校验并持久化；返回首个校验错误，null 表示已保存。 */
    suspend fun validateAndSave(): String? {
        val error = _form.value.validate()
        if (error == null) {
            repository.save(_form.value)
            _saved.value = true
        }
        return error
    }

    fun clearSaved() {
        _saved.value = false
    }

    /** 已连接时每秒取一次 hev 统计，计算上行/下行速率；断开后清空。 */
    private suspend fun pollTrafficStats() {
        var lastTx = 0L
        var lastRx = 0L
        var lastTime = 0L
        while (true) {
            delay(POLL_INTERVAL_MS)
            val raw = if (VpnStateHolder.state.value is VpnState.Connected) {
                HevTunnel.stats()
            } else {
                null
            }
            if (raw == null) {
                lastTime = 0L
                _traffic.value = null
                continue
            }
            val now = System.currentTimeMillis()
            val tx = raw.getOrNull(1) ?: 0L
            val rx = raw.getOrNull(3) ?: 0L
            if (lastTime > 0L) {
                val dtMs = now - lastTime
                val txRate = if (dtMs > 0) ((tx - lastTx) * 1000 / dtMs).coerceAtLeast(0) else 0L
                val rxRate = if (dtMs > 0) ((rx - lastRx) * 1000 / dtMs).coerceAtLeast(0) else 0L
                _traffic.value = TrafficStats(tx, rx, txRate, rxRate)
            }
            lastTx = tx
            lastRx = rx
            lastTime = now
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1000L
    }
}
