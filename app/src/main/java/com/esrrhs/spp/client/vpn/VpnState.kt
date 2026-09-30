package com.esrrhs.spp.client.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface VpnState {
    data object Disconnected : VpnState
    data object Connecting : VpnState
    data object Connected : VpnState
    data object Disconnecting : VpnState
    data class Error(val message: String) : VpnState
}

/**
 * 全局 VPN 状态。UI 与 Service 解耦：Service 更新、UI 只读，
 * Service 销毁重建时状态不丢。
 */
object VpnStateHolder {
    private val _state = MutableStateFlow<VpnState>(VpnState.Disconnected)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    fun set(state: VpnState) {
        _state.value = state
    }
}
