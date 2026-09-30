package com.esrrhs.spp.client.vpn

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** UI 层启停 VPN 的唯一入口；状态请读 [VpnStateHolder]。 */
object VpnController {

    fun connect(context: Context) {
        val intent = Intent(context, SppVpnService::class.java)
            .setAction(SppVpnService.ACTION_CONNECT)
        ContextCompat.startForegroundService(context, intent)
    }

    fun disconnect(context: Context) {
        val intent = Intent(context, SppVpnService::class.java)
            .setAction(SppVpnService.ACTION_DISCONNECT)
        ContextCompat.startForegroundService(context, intent)
    }
}
