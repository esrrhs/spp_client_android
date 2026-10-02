package com.esrrhs.spp.client.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log

/**
 * 监视系统默认网络切换（WiFi ↔ 蜂窝、基站/SSID 重连等）。
 *
 * 旧默认网络上的 SPP socket 在切换后可能被黑洞（进程不退出、无异常），
 * 因此默认网络变为另一个已验证网络时主动通知 Service 重建数据面。
 */
class NetworkWatchdog(
    context: Context,
    private val onDefaultNetworkChanged: () -> Unit,
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var registered = false
    private var current: Network? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = handle(network)

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: android.net.NetworkCapabilities,
        ) {
            if (capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                handle(network)
            }
        }

        override fun onLost(network: Network) {
            if (network == current) current = null
        }
    }

    private fun handle(network: Network) {
        val previous = current
        current = network
        // 首次回调只记录基线；之后出现不同的默认网络才是切换
        if (previous != null && previous != network) {
            Log.i(TAG, "default network changed: $previous -> $network")
            onDefaultNetworkChanged()
        }
    }

    fun start() {
        if (registered || cm == null) return
        current = null
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onFailure { Log.w(TAG, "registerDefaultNetworkCallback failed", it) }
            .onSuccess { registered = true }
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
