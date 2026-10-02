package com.esrrhs.spp.client.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.esrrhs.spp.client.util.WifiNames

/**
 * 监视默认网络在「可信 WiFi / 非可信网络」之间的边沿变化。
 *
 * - 注册后第一次观察到的网络只作为基线（可能正是用户主动连 VPN 时的网络，不应立即暂停）；
 * - 之后可信↔非可信的状态翻转才触发 [onTrustedChanged]；
 * - 蜂窝网络 SSID 为 null，天然是非可信。
 */
class TrustedWifiMonitor(
    context: Context,
    private val evaluate: (ssid: String?) -> Boolean,
    private val onTrustedChanged: (Boolean) -> Unit,
) {
    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(ConnectivityManager::class.java)
    private var registered = false
    private var baselineDone = false
    private var lastTrusted: Boolean? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = handle(network)

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: android.net.NetworkCapabilities,
        ) = handle(network)

        override fun onLost(network: Network) {
            // WiFi 断连后系统可能短暂没有默认网络；等下一个 onAvailable 判定
        }
    }

    private fun handle(network: Network) {
        val ssid = WifiNames.ssidOf(appContext, network)
        val trusted = evaluate(ssid)
        if (!baselineDone) {
            baselineDone = true
            lastTrusted = trusted
            Log.i(TAG, "baseline network trusted=$trusted ssid=$ssid")
            return
        }
        if (lastTrusted != trusted) {
            lastTrusted = trusted
            Log.i(TAG, "trusted state changed: $trusted (ssid=$ssid)")
            onTrustedChanged(trusted)
        }
    }

    /** 重新注册并重置基线（每次成功建会话时调用）。 */
    fun start() {
        stop()
        if (cm == null) return
        baselineDone = false
        lastTrusted = null
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onFailure { Log.w(TAG, "registerDefaultNetworkCallback failed", it) }
            .onSuccess { registered = true }
    }

    fun stop() {
        if (!registered) return
        registered = false
        baselineDone = false
        lastTrusted = null
        runCatching { cm?.unregisterNetworkCallback(callback) }
    }

    private companion object {
        const val TAG = "TrustedWifiMonitor"
    }
}
