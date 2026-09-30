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
import com.esrrhs.spp.client.data.SettingsRepository
import com.esrrhs.spp.client.spp.PerAppMode
import com.esrrhs.spp.client.spp.Profile
import com.esrrhs.spp.client.spp.SppException
import com.esrrhs.spp.client.spp.SppProcess
import com.esrrhs.spp.client.tun.HevTunnel
import com.esrrhs.spp.client.util.CidrRoutes
import com.esrrhs.spp.client.util.CnRouteList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class SppVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 串行化 connect / teardown / reconnect。 */
    private val lifecycleMutex = Mutex()

    private var sppProcess: SppProcess? = null
    private var tunInterface: ParcelFileDescriptor? = null

    /** 本次会话（含重连）所属配置及统计。 */
    private var activeProfileId: String? = null
    private var baselineTx = 0L
    private var baselineRx = 0L
    private var sessionTxTotal = 0L
    private var sessionRxTotal = 0L

    private var reconnectJob: Job? = null

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
        return START_NOT_STICKY
    }

    /** 系统收回 VPN：停 hev / spp 并复位状态。 */
    override fun onRevoke() {
        Log.i(TAG, "vpn revoked by system")
        reconnectJob?.cancel()
        scope.launch { teardown(notifyDisconnected = true) }
    }

    override fun onDestroy() {
        reconnectJob?.cancel()
        runCatching { HevTunnel.stop() }
        tunInterface?.let { pfd -> runCatching { pfd.close() } }
        sppProcess?.stop()
        sppProcess = null
        activeProfileId = null
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
        // 用户主动断开：取消可能进行中的自动重连
        reconnectJob?.cancel()
        startAsForeground(getString(R.string.notif_disconnecting))
        VpnStateHolder.set(VpnState.Disconnecting)
        scope.launch { teardown(notifyDisconnected = true) }
    }

    private suspend fun connect() = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            try {
                val (repository, profile) = loadActiveProfile()
                sessionTxTotal = 0L
                sessionRxTotal = 0L
                establishSession(repository, profile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "connect failed: ${e.message}")
                stopDataPlaneAndCount()
                persistSessionTraffic()
                VpnStateHolder.set(VpnState.Error(e.message ?: "连接失败"))
                finishService()
            }
        }
    }

    /** 建立一次完整数据面（spp → TUN → hev）；成功后状态为 Connected。 */
    private suspend fun establishSession(repository: ConfigRepository, profile: Profile) {
        profile.validate()?.let { throw SppException(it) }
        val config = profile.config
        activeProfileId = profile.id

        // 1. 拉起本地 SOCKS5
        val spp = SppProcess(this)
        spp.onUnexpectedExit = {
            if (VpnStateHolder.state.value is VpnState.Connected &&
                activeProfileId == profile.id
            ) {
                scope.launch { handleTunnelLost(profile.id) }
            }
        }
        val socksPort = spp.start(config)
        Log.i(TAG, "spp socks5 listening on 127.0.0.1:$socksPort")
        sppProcess = spp

        // 2. 建立 TUN（含分应用 / 智能分流路由）
        val tun = buildTun(profile)
            .establish() ?: throw SppException(getString(R.string.error_establish))
        Log.i(TAG, "tun established")
        tunInterface = tun

        // 3. hev：getFd() 只传 int，PFD 所有权保留在本类
        val configFile = writeHevConfig(socksPort, config.enableIpv6)
        if (!HevTunnel.start(configFile.absolutePath, tun.fd)) {
            throw SppException("hev-socks5-tunnel 启动失败")
        }
        Log.i(TAG, "hev tunnel started")

        HevTunnel.stats()?.let { s ->
            baselineTx = s.getOrNull(1) ?: 0L
            baselineRx = s.getOrNull(3) ?: 0L
        }

        updateNotification(getString(R.string.notif_connected, config.serverAddr))
        VpnStateHolder.set(VpnState.Connected)
    }

    /** spp 意外退出：按设置自动重连，否则报错停止。 */
    private suspend fun handleTunnelLost(profileId: String) {
        lifecycleMutex.withLock {
            val autoReconnect = runCatching {
                SettingsRepository(this@SppVpnService).settings.first().autoReconnect
            }.getOrDefault(false)

            // 先把当前数据面计数结账
            stopDataPlaneAndCount()

            if (!autoReconnect) {
                persistSessionTraffic()
                VpnStateHolder.set(
                    VpnState.Error("SPP 连接已断开，请检查网络后重连"),
                )
                finishService()
                return
            }

            VpnStateHolder.set(VpnState.Connecting)
            updateNotification(getString(R.string.notif_reconnecting))
            for (attempt in 1..MAX_RECONNECT_ATTEMPTS) {
                delay(backoffMs(attempt))
                try {
                    val (repository, profile) = loadActiveProfile()
                    // 选中项已变化或已非本配置：不再重连
                    if (profile.id != profileId) {
                        persistSessionTraffic()
                        finishService()
                        return
                    }
                    establishSession(repository, profile)
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "reconnect attempt $attempt failed: ${e.message}")
                    stopDataPlaneAndCount()
                }
            }
            persistSessionTraffic()
            VpnStateHolder.set(VpnState.Error("多次重连失败，请检查网络或 Server"))
            finishService()
        }
    }

    private suspend fun teardown(
        errorMessage: String? = null,
        notifyDisconnected: Boolean = false,
    ) {
        lifecycleMutex.withLock {
            stopDataPlaneAndCount()
            persistSessionTraffic()
            when {
                errorMessage != null -> VpnStateHolder.set(VpnState.Error(errorMessage))
                notifyDisconnected &&
                    VpnStateHolder.state.value !is VpnState.Error ->
                    VpnStateHolder.set(VpnState.Disconnected)
            }
            finishService()
        }
    }

    /** 停数据面并把本段字节计入会话累计。 */
    private fun stopDataPlaneAndCount() {
        val finalStats = HevTunnel.stats()
        runCatching { HevTunnel.stop() }
        tunInterface?.let { pfd -> runCatching { pfd.close() } }
        tunInterface = null
        sppProcess?.stop()
        sppProcess = null
        if (finalStats != null) {
            sessionTxTotal += ((finalStats.getOrNull(1) ?: 0L) - baselineTx).coerceAtLeast(0)
            sessionRxTotal += ((finalStats.getOrNull(3)  ?: 0L) - baselineRx).coerceAtLeast(0)
        }
        baselineTx = 0L
        baselineRx = 0L
    }

    private suspend fun persistSessionTraffic() {
        val pid = activeProfileId
        if (pid != null && (sessionTxTotal > 0 || sessionRxTotal > 0)) {
            runCatching {
                ConfigRepository(this).addTraffic(pid, sessionTxTotal, sessionRxTotal)
            }
        }
        sessionTxTotal = 0L
        sessionRxTotal = 0L
        activeProfileId = null
    }

    private fun finishService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** 读取当前选中配置；不存在有效配置时抛错。 */
    private suspend fun loadActiveProfile(): Pair<ConfigRepository, Profile> {
        val repository = ConfigRepository(this)
        val profiles = repository.profiles.first()
        val activeId = repository.activeId.first()
        val profile = profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
        return if (profile == null) {
            throw SppException("请先添加配置")
        } else {
            repository to profile
        }
    }

    private fun buildTun(profile: Profile): Builder {
        val builder = Builder()
            .setSession(TunConfig.SESSION)
            .setMtu(TunConfig.MTU)
            .addAddress(TunConfig.TUN_ADDRESS, TunConfig.TUN_PREFIX)
            .addDnsServer(TunConfig.DNS_ADDRESS)

        if (profile.config.enableIpv6) {
            builder.addAddress(TunConfig.TUN_ADDRESS_V6, TunConfig.TUN_PREFIX_V6)
        }

        applyRouting(builder, profile)
        applyPerApp(builder, profile)
        return builder
    }

    /** IPv4/IPv6 路由：全局、仅公网（绕过私有网段）或 CN 直连。 */
    private fun applyRouting(builder: Builder, profile: Profile) {
        if (!profile.bypassLan && !profile.bypassCn) {
            builder.addRoute("0.0.0.0", 0)
            if (profile.config.enableIpv6) builder.addRoute("::", 0)
            return
        }

        val cnCidrs = if (profile.bypassCn) {
            CnRouteList.load(this)
        } else {
            emptyList()
        }
        // 精确模式：CN 段严格直连、其余公网地址全部代理，不做间隙填充
        CidrRoutes.publicCidrs(cnCidrs)
            .forEach { cidr -> builder.addRoute(cidr.address, cidr.prefix) }

        if (profile.config.enableIpv6) {
            // 智能分流时仅接管全球单播（2000::/3），ULA/link-local 直连；
            // IPv6 的 CN 过滤本轮不包含
            builder.addRoute("2000::", 3)
        }
    }

    /** 分应用代理规则；已卸载的包忽略。 */
    private fun applyPerApp(builder: Builder, profile: Profile) {
        when (profile.perAppMode) {
            PerAppMode.ALL ->
                runCatching { builder.addDisallowedApplication(packageName) }

            PerAppMode.ALLOWED ->
                profile.perAppPackages.distinct().forEach { pkg ->
                    runCatching { builder.addAllowedApplication(pkg) }
                }

            PerAppMode.DISALLOWED -> {
                runCatching { builder.addDisallowedApplication(packageName) }
                profile.perAppPackages.distinct().filter { it != packageName }.forEach { pkg ->
                    runCatching { builder.addDisallowedApplication(pkg) }
                }
            }
        }
    }

    private fun writeHevConfig(socksPort: Int, enableIpv6: Boolean): File {
        val yaml = buildString {
            appendLine("tunnel:")
            appendLine("  name: tun0")
            appendLine("  mtu: ${TunConfig.MTU}")
            appendLine("  ipv4: ${TunConfig.TUN_ADDRESS}")
            if (enableIpv6) appendLine("  ipv6: '${TunConfig.TUN_ADDRESS_V6}'")
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

    private fun backoffMs(attempt: Int): Long =
        (1000L shl (attempt - 1).coerceAtMost(4))

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
        private const val MAX_RECONNECT_ATTEMPTS = 5
    }
}
