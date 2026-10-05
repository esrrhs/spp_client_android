package com.esrrhs.spp.client.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log

/**
 * 监视系统默认网络切换（WiFi ↔ 蜂窝、基站/SSID 重连等）。
 *
 * 忽略 VPN 虚拟网络自身，仅在底层物理网络（WiFi / 蜂窝）发生切换时通知重建数据面。
 */
class NetworkWatchdog(
    context: Context,
    private val onNetworkUpdate: ((Network?) -> Unit)? = null,
    private val onDefaultNetworkChanged: () -> Unit,
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var registered = false
    var current: Network? = null
        private set

    private fun isPhysicalNetwork(network: Network): Boolean {
        val caps = cm?.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (isPhysicalNetwork(network)) {
                handle(network)
            }
        }

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities,
        ) {
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) {
                handle(network)
            }
        }

        override fun onLost(network: Network) {
            if (network == current) {
                current = null
                onNetworkUpdate?.invoke(null)
            }
        }
    }

    private fun handle(network: Network) {
        if (!isPhysicalNetwork(network)) return
        val previous = current
        current = network
        onNetworkUpdate?.invoke(network)
        // 首次回调只记录基线；之后出现不同的物理网络才是切换（如 WiFi ↔ 蜂窝）
        if (previous != null && previous != network) {
            Log.i(TAG, "default physical network changed: $previous -> $network")
            onDefaultNetworkChanged()
        }
    }

    fun start() {
        if (cm == null) return
        if (current == null) {
            val active = cm.activeNetwork
            if (active != null && isPhysicalNetwork(active)) {
                current = active
            }
        }
        if (!registered) {
            runCatching { cm.registerDefaultNetworkCallback(callback) }
                .onFailure { Log.w(TAG, "registerDefaultNetworkCallback failed", it) }
                .onSuccess { registered = true }
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        current = null
        runCatching { cm?.unregisterNetworkCallback(callback) }
    }

    private companion object {
        const val TAG = "NetworkWatchdog"
    }
}
