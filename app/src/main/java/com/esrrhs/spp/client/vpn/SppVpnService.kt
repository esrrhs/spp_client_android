package com.esrrhs.spp.client.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.esrrhs.spp.client.MainActivity
import com.esrrhs.spp.client.R
import com.esrrhs.spp.client.data.ConfigRepository
import com.esrrhs.spp.client.spp.SppException
import com.esrrhs.spp.client.spp.SppProcess
import com.esrrhs.spp.client.tun.HevTunnel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class SppVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 串行化 connect / teardown，杜绝「正在连接时断开」导致的状态错乱。 */
    private val lifecycleMutex = Mutex()

    private var sppProcess: SppProcess? = null
    private var tunInterface: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> requestDisconnect()
            // ACTION_CONNECT / null（系统重建 Service）
            else -> requestConnect()
        }
        // Service 被系统杀掉后不自动重连（MVP 行为，见 IMPLEMENTATION_PLAN Phase 6）
        return START_NOT_STICKY
    }

    /**
     * 系统收回 VPN：用户在系统设置中断开、或被其它 VPN 抢占。
     * 必须停掉 hev / spp 并复位状态，否则 UI 会永远显示「已连接」。
     */
    override fun onRevoke() {
        Log.i(TAG, "vpn revoked by system")
        scope.launch { teardown(notifyDisconnected = true) }
    }

    override fun onDestroy() {
        // best-effort 同步清理（极端情况下协程已无法调度时的兜底）
        runCatching { HevTunnel.stop() }
        tunInterface?.let { pfd -> runCatching { pfd.close() } }
        sppProcess?.stop()
        sppProcess = null
        scope.cancel()
        super.onDestroy()
    }

    private fun requestConnect() {
        val current = VpnStateHolder.state.value
        if (current is VpnState.Connecting ||
            current is VpnState.Connected ||
            current is VpnState.Disconnecting
        ) {
            return
        }
        startAsForeground(getString(R.string.notif_connecting))
        VpnStateHolder.set(VpnState.Connecting)
        scope.launch { connect() }
    }

    private fun requestDisconnect() {
        val current = VpnStateHolder.state.value
        if (current is VpnState.Disconnected || current is VpnState.Disconnecting) {
            return
        }
        startAsForeground(getString(R.string.notif_disconnecting))
        VpnStateHolder.set(VpnState.Disconnecting)
        scope.launch { teardown(notifyDisconnected = true) }
    }

    private suspend fun connect() = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            try {
                val config = ConfigRepository(this@SppVpnService).config.first()
                config.validate()?.let { throw SppException(it) }

                // 1. 先拉起本地 SOCKS5（SPP socks5_client 子进程），等到端口监听
                val spp = SppProcess(this@SppVpnService)
                spp.onUnexpectedExit = {
                    // 仅在已连接状态下视为意外；连接中由 waitUntilListening 报错
                    if (VpnStateHolder.state.value is VpnState.Connected) {
                        scope.launch {
                            teardown(
                                errorMessage = "SPP 客户端进程意外退出，请检查网络或 Server 后重连",
                            )
                        }
                    }
                }
                val socksPort = spp.start(config)
                Log.i(TAG, "spp socks5 listening on 127.0.0.1:$socksPort")
                sppProcess = spp

                // 2. 建立 TUN；环路防护见 SppProcess 类注释
                val tun = Builder()
                    .setSession(TunConfig.SESSION)
                    .setMtu(TunConfig.MTU)
                    .addAddress(TunConfig.TUN_ADDRESS, TunConfig.TUN_PREFIX)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer(TunConfig.DNS_ADDRESS)
                    .apply {
                        if (config.enableIpv6) {
                            addAddress(TunConfig.TUN_ADDRESS_V6, TunConfig.TUN_PREFIX_V6)
                            addRoute("::", 0)
                        }
                    }
                    .addDisallowedApplication(packageName)
                    .establish() ?: throw SppException(getString(R.string.error_establish))
                Log.i(TAG, "tun established")
                tunInterface = tun

                // 3. fd 交给 hev-socks5-tunnel，全流量转成本地 SOCKS5。
                // 用 getFd() 只给 int、不转移所有权：hev 对外部传入的 fd 不会自行关闭
                // （tunnel_fini 里 tun_fd_local==0 直接 return），必须由本类在 teardown
                // 时 close() 这个 PFD——关闭后内核删除 tun0，框架收到 interfaceRemoved
                // 才会 unbind 服务、断开 VPN network agent。
                val configFile = writeHevConfig(socksPort, config.enableIpv6)
                if (!HevTunnel.start(configFile.absolutePath, tun.fd)) {
                    throw SppException("hev-socks5-tunnel 启动失败")
                }
                Log.i(TAG, "hev tunnel started")

                updateNotification(getString(R.string.notif_connected, config.serverAddr))
                VpnStateHolder.set(VpnState.Connected)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "connect failed: ${e.message}")
                VpnStateHolder.set(VpnState.Error(e.message ?: "连接失败"))
                teardownLocked()
            }
        }
    }

    /** 拆链：停 hev → 关 tun → 停 spp → 关通知。 */
    private suspend fun teardown(
        errorMessage: String? = null,
        notifyDisconnected: Boolean = false,
    ) {
        lifecycleMutex.withLock {
            teardownLocked(errorMessage, notifyDisconnected)
        }
    }

    private fun teardownLocked(
        errorMessage: String? = null,
        notifyDisconnected: Boolean = false,
    ) {
        try {
            // 顺序：停 hev → 关 tun（触发内核删接口、框架 unbind）→ 停 spp
            HevTunnel.stop()
            tunInterface?.let { pfd -> runCatching { pfd.close() } }
            tunInterface = null
            sppProcess?.stop()
            sppProcess = null
        } finally {
            when {
                errorMessage != null -> VpnStateHolder.set(VpnState.Error(errorMessage))
                notifyDisconnected &&
                    VpnStateHolder.state.value !is VpnState.Error ->
                    VpnStateHolder.set(VpnState.Disconnected)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun writeHevConfig(socksPort: Int, enableIpv6: Boolean): File {
        val yaml = buildString {
            appendLine("tunnel:")
            appendLine("  name: tun0")
            appendLine("  mtu: ${TunConfig.MTU}")
            appendLine("  ipv4: ${TunConfig.TUN_ADDRESS}")
            if (enableIpv6) {
                appendLine("  ipv6: '${TunConfig.TUN_ADDRESS_V6}'")
            }
            appendLine("socks5:")
            appendLine("  address: 127.0.0.1")
            appendLine("  port: $socksPort")
            appendLine("  udp: 'udp'")
            appendLine("mapdns:")
            appendLine("  address: ${TunConfig.DNS_ADDRESS}")
            appendLine("  port: 53")
            appendLine("  network: ${TunConfig.MAPDNS_NETWORK}")
            appendLine("  netmask: ${TunConfig.MAPDNS_NETMASK}")
            appendLine("  cache-size: 10000")
            appendLine("misc:")
            appendLine("  log-level: info")
            appendLine("  log-file: ${File(filesDir, "hev.log").absolutePath}")
        }
        return File(filesDir, "hev.yml").apply { writeText(yaml) }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startAsForeground(text: String) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(text),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_spp)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_CONNECT = "com.esrrhs.spp.client.action.CONNECT"
        const val ACTION_DISCONNECT = "com.esrrhs.spp.client.action.DISCONNECT"

        private const val CHANNEL_ID = "spp_vpn"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "SppVpnService"
    }
}
