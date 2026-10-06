package com.esrrhs.spp.client.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
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
import com.esrrhs.spp.client.spp.ValidationError
import com.esrrhs.spp.client.tun.HevTunnel
import com.esrrhs.spp.client.util.Cidr6Routes
import com.esrrhs.spp.client.util.CidrRoutes
import com.esrrhs.spp.client.util.CnRouteList
import com.esrrhs.spp.client.util.Formatters
import com.esrrhs.spp.client.util.TrafficMeter
import kotlinx.serialization.json.Json
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

    /**
     * 与 [connectQueue] 一起保护 CONNECT/DISCONNECT 的状态切换。
     * 调用方只在这段临界区内改 [VpnStateHolder]，避免「tun 已消失、状态仍是 Disconnecting」时丢掉下一次 CONNECT。
     */
    private val lifecycleGate = Any()
    private val connectQueue = VpnConnectQueue()

    private var sppProcess: SppProcess? = null
    private var tunInterface: ParcelFileDescriptor? = null
    private var ruleServer: com.esrrhs.spp.client.proxy.RuleSocksServer? = null

    /** 每次成功建会话自增，用于让旧 spp 的意外退出回调失效。 */
    private var sessionGeneration = 0

    private lateinit var networkWatchdog: NetworkWatchdog
    private lateinit var trustedWifiMonitor: TrustedWifiMonitor

    /** 设置快照，供网络回调即时判断可信规则。 */
    @Volatile
    private var cachedSettings = com.esrrhs.spp.client.data.AppSettings()

    /** 本次会话（含重连）所属配置及统计。 */
    private var activeProfileId: String? = null
    private var baselineTx = 0L
    private var baselineRx = 0L
    private var sessionTxTotal = 0L
    private var sessionRxTotal = 0L

    private var reconnectJob: Job? = null
    private var notifRateJob: Job? = null
    private val rateMeter = TrafficMeter()
    private var currentProfileName: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        networkWatchdog = NetworkWatchdog(
            context = applicationContext,
            onNetworkUpdate = { net ->
                updateUnderlyingNetwork(net)
            },
            onDefaultNetworkChanged = {
                val id = activeProfileId
                if (id != null && VpnStateHolder.state.value is VpnState.Connected) {
                    reconnectJob = scope.launch { recoverTunnel(id) }
                }
            },
        )
        trustedWifiMonitor = TrustedWifiMonitor(
            context = applicationContext,
            evaluate = { ssid ->
                val s = cachedSettings
                s.trustedWifiEnabled &&
                    com.esrrhs.spp.client.util.TrustedWifi.isTrusted(ssid, s.trustedWifiSsids)
            },
            onTrustedChanged = { trusted ->
                scope.launch {
                    if (trusted) pauseForTrustedWifi() else resumeFromTrustedWifi()
                }
            },
        )
        // 缓存全局设置供回调使用
        scope.launch {
            SettingsRepository(this@SppVpnService).settings.collect { cachedSettings = it }
        }
    }

    @Volatile
    private var overrideProfile: Profile? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                overrideProfile = null
                requestDisconnect()
            }
            // ACTION_CONNECT / null（系统重建 Service）
            else -> {
                val rawJson = intent?.getStringExtra(EXTRA_PROFILE_JSON)
                if (!rawJson.isNullOrBlank()) {
                    overrideProfile = runCatching {
                        profileJson.decodeFromString(Profile.serializer(), rawJson)
                    }.getOrNull()
                }
                requestConnect()
            }
        }
        return START_NOT_STICKY
    }

    /** 系统收回 VPN：停 hev / spp 并复位状态。收回后不再接上排队的 CONNECT。 */
    override fun onRevoke() {
        Log.i(TAG, "vpn revoked by system")
        reconnectJob?.cancel()
        synchronized(lifecycleGate) {
            connectQueue.clear()
            val current = VpnStateHolder.state.value
            if (current != VpnState.Disconnected && current != VpnState.Disconnecting) {
                VpnStateHolder.set(VpnState.Disconnecting)
            }
        }
        scope.launch { teardown(notifyDisconnected = true, honorQueue = false) }
    }

    override fun onDestroy() {
        stopRateNotificationUpdating()
        reconnectJob?.cancel()
        if (::networkWatchdog.isInitialized) networkWatchdog.stop()
        if (::trustedWifiMonitor.isInitialized) trustedWifiMonitor.stop()
        runCatching { HevTunnel.stop() }
        tunInterface?.let { pfd -> runCatching { pfd.close() } }
        sppProcess?.stop()
        sppProcess = null
        activeProfileId = null
        scope.cancel()
        super.onDestroy()
    }

    private fun requestConnect() {
        val command = synchronized(lifecycleGate) {
            val command = connectQueue.onConnect(VpnStateHolder.state.value)
            if (command == ConnectCommand.START) {
                VpnStateHolder.set(VpnState.Connecting)
            }
            command
        }
        when (command) {
            ConnectCommand.QUEUE ->
                Log.i(TAG, "connect queued until teardown finishes")
            ConnectCommand.IGNORE -> Unit
            ConnectCommand.START -> {
                startAsForeground(getString(R.string.notif_connecting))
                scope.launch { connect() }
            }
        }
    }

    private fun requestDisconnect() {
        val startTeardown = synchronized(lifecycleGate) {
            val start = connectQueue.onDisconnect(VpnStateHolder.state.value)
            if (start) VpnStateHolder.set(VpnState.Disconnecting)
            start
        }
        if (!startTeardown) return
        // 用户主动断开：取消可能进行中的自动重连
        reconnectJob?.cancel()
        startAsForeground(getString(R.string.notif_disconnecting))
        scope.launch { teardown(notifyDisconnected = true) }
    }

    private suspend fun connect() = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            if (VpnStateHolder.state.value !is VpnState.Connecting) {
                Log.i(TAG, "connect aborted, state=${VpnStateHolder.state.value}")
                return@withLock
            }
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
                VpnStateHolder.set(
                    VpnState.Error(e.message ?: getString(R.string.error_connect_failed)),
                )
                finishService()
            }
        }
    }

    /** 建立一次完整数据面（spp → TUN → hev）；成功后状态为 Connected。 */
    private suspend fun establishSession(repository: ConfigRepository, profile: Profile) {
        profile.validate()?.let { throw SppException(validationText(it)) }
        // prepare() 在已授权（含 appops ACTIVATE_VPN）时把本包登记为当前 VPN，
        // 否则 establish() 会直接返回 null。界面路径会先调用它；adb / 磁贴直启服务时也要补上。
        if (VpnService.prepare(this) != null) {
            throw SppException(getString(R.string.error_vpn_consent))
        }
        val config = profile.config
        activeProfileId = profile.id

        // 1. 拉起上游。SPP 在本机起 socks5_client；SOCKS5 直接连远端代理。
        val generation = ++sessionGeneration
        val socksPort = if (config.isSocks5) {
            Log.i(TAG, "socks5 upstream ${config.serverAddr}")
            null
        } else {
            val spp = SppProcess(this)
            spp.onUnexpectedExit = {
                if (generation == sessionGeneration &&
                    VpnStateHolder.state.value is VpnState.Connected &&
                    activeProfileId == profile.id
                ) {
                    reconnectJob = scope.launch { recoverTunnel(profile.id) }
                }
            }
            val port = spp.start(config)
            Log.i(TAG, "spp socks5 listening on 127.0.0.1:$port")
            sppProcess = spp
            port
        }

        // 1b. 域名直连规则：hev 先接本地分流器，再由其转发上游
        //     生效集合 = 内置大陆域名表 + 用户自定义规则
        //     SOCKS5 模式必须经过分流器，才能在这里完成用户名密码认证。
        val directRules: Set<String> = if (cachedSettings.domainDirectEnabled) {
            LinkedHashSet<String>(
                com.esrrhs.spp.client.util.BundledDirectDomains.load(this),
            ).apply {
                addAll(
                    com.esrrhs.spp.client.util.DomainRuleMatcher
                        .parse(cachedSettings.domainDirectRulesText),
                )
            }
        } else {
            emptySet()
        }
        val hevSocksPort = if (config.isSocks5 || cachedSettings.domainDirectEnabled) {
            if (cachedSettings.domainDirectEnabled) {
                Log.i(TAG, "domain direct rules: ${directRules.size} domains")
            }
            val upstream = if (config.isSocks5) {
                com.esrrhs.spp.client.proxy.SocksUpstream(
                    host = config.serverHost,
                    port = config.serverPort,
                    username = config.username,
                    password = config.password,
                )
            } else {
                com.esrrhs.spp.client.proxy.SocksUpstream("127.0.0.1", socksPort!!)
            }
            val server = com.esrrhs.spp.client.proxy.RuleSocksServer(upstream, directRules)
            server.start()
            ruleServer = server
            server.port ?: throw SppException(getString(R.string.error_rule_proxy))
        } else {
            socksPort!!
        }
        ActiveSession.socksPort = hevSocksPort

        // 2. 建立 TUN（含分应用 / 智能分流路由）
        val tun = buildTun(profile)
            .establish() ?: throw SppException(getString(R.string.error_establish))
        Log.i(TAG, "tun established")
        tunInterface = tun

        // 3. hev：getFd() 只传 int，PFD 所有权保留在本类
        val configFile = writeHevConfig(hevSocksPort, profile.config.enableIpv6)
        com.esrrhs.spp.client.util.RuntimeLogs.trim(File(filesDir, "hev.log"))
        val hevStarted = try {
            HevTunnel.start(configFile.absolutePath, tun.fd)
        } catch (e: IllegalStateException) {
            throw SppException(getString(R.string.error_hev_missing))
        }
        if (!hevStarted) {
            throw SppException(getString(R.string.error_hev_start))
        }
        Log.i(TAG, "hev tunnel started")

        HevTunnel.stats()?.let { s ->
            baselineTx = s.getOrNull(1) ?: 0L
            baselineRx = s.getOrNull(3) ?: 0L
        }

        updateNotification(getString(R.string.notif_connected, profile.name))
        startRateNotificationUpdating(profile.name)
        VpnStateHolder.set(VpnState.Connected)

        // 全局连接采集（当前连接页 + 单连接历史），与界面是否打开无关
        com.esrrhs.spp.client.util.ConnectionRecorder.start(
            scope = scope,
            context = this,
            profile = profile,
            directDomains = directRules,
        )

        // 4. 监视默认网络切换（WiFi↔蜂窝），切换后主动重建数据面
        networkWatchdog.start()
        updateUnderlyingNetwork(networkWatchdog.current)
        // 可信 WiFi：本次建链的网络作为基线，之后翻转才暂停/恢复
        trustedWifiMonitor.start()
    }

    /** 接入可信 WiFi：结账数据面并进入暂停态（Service 保留，监听离开后恢复）。 */
    private suspend fun pauseForTrustedWifi() {
        lifecycleMutex.withLock {
            if (VpnStateHolder.state.value !is VpnState.Connected) return
            sessionGeneration++
            stopDataPlaneAndCount()
            persistSessionTraffic()
            VpnStateHolder.set(VpnState.Paused)
            updateNotification(getString(R.string.notif_paused_trusted))
        }
    }

    /** 离开可信网络：从暂停态自动重连。 */
    private fun resumeFromTrustedWifi() {
        if (VpnStateHolder.state.value !is VpnState.Paused) return
        startAsForeground(getString(R.string.notif_connecting))
        VpnStateHolder.set(VpnState.Connecting)
        scope.launch { connect() }
    }

    /**
     * 数据面丢失恢复：spp 意外退出或默认网络切换共用。
     *
     * 自动重连**无次数上限**，退避 1s/2s/4s/5s/5s…，直到成功或被用户取消；
     * 开启故障切换时，每个配置连续失败 [FAILOVER_CYCLE] 次后轮换到下一个候选。
     */
    private suspend fun recoverTunnel(profileId: String) {
        lifecycleMutex.withLock {
            // 可能在等锁期间已被用户断开 / 已被其他恢复流程处理
            if (VpnStateHolder.state.value !is VpnState.Connected) return
            if (activeProfileId != profileId) return

            val settings = runCatching {
                SettingsRepository(this@SppVpnService).settings.first()
            }.getOrNull()

            // 主动停旧数据面（网络切换）前作废旧 spp 的退出回调，避免重复恢复
            sessionGeneration++
            stopDataPlaneAndCount()

            if (settings?.autoReconnect != true) {
                persistSessionTraffic()
                VpnStateHolder.set(
                    VpnState.Error(getString(R.string.error_tunnel_lost)),
                )
                finishService()
                return
            }

            // 候选：当前配置优先；开启故障切换时追加按延迟排序的全部备用配置
            val candidates = mutableListOf(profileId)
            if (settings.failover) {
                val profiles = ConfigRepository(this).profiles.first()
                Failover.orderedOthers(profiles, profileId).forEach { candidates.add(it.id) }
            }

            VpnStateHolder.set(VpnState.Connecting)
            updateNotification(getString(R.string.notif_reconnecting))

            var attempt = 1
            var index = 0
            while (true) {
                delay(ReconnectBackoff.delayMs(attempt))
                val candidateId = candidates[index]
                try {
                    val (repository, profile) = loadActiveProfile()
                    // 候选在重连期间被删除：从轮换列表移除
                    if (profile.id != candidateId) {
                        candidates.removeAt(index)
                        if (candidates.isEmpty()) {
                            persistSessionTraffic()
                            VpnStateHolder.set(VpnState.Error(getString(R.string.error_no_profile)))
                            finishService()
                            return
                        }
                        index %= candidates.size
                        continue
                    }
                    establishSession(repository, profile)
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "reconnect attempt $attempt (candidate=$candidateId) failed: ${e.message}")
                    stopDataPlaneAndCount()
                }
                attempt++
                // 当前候选连续失败一个周期：轮换到下一个配置（环状）
                if (candidates.size > 1 && (attempt - 1) % FAILOVER_CYCLE == 0) {
                    val next = (index + 1) % candidates.size
                    if (next != index) {
                        index = next
                        ConfigRepository(this).setActive(candidates[index])
                        Log.i(TAG, "failover to profile ${candidates[index]}")
                        updateNotification(getString(R.string.notif_failover))
                    }
                }
            }
        }
    }

    private suspend fun teardown(
        errorMessage: String? = null,
        notifyDisconnected: Boolean = false,
        honorQueue: Boolean = true,
    ) {
        val followUp = lifecycleMutex.withLock {
            trustedWifiMonitor.stop()
            stopDataPlaneAndCount()
            persistSessionTraffic()
            val follow = synchronized(lifecycleGate) {
                val follow = if (honorQueue) {
                    connectQueue.takeFollowUp(failed = errorMessage != null)
                } else {
                    connectQueue.clear()
                    false
                }
                if (follow) {
                    VpnStateHolder.set(VpnState.Connecting)
                } else {
                    when {
                        errorMessage != null -> VpnStateHolder.set(VpnState.Error(errorMessage))
                        notifyDisconnected &&
                            VpnStateHolder.state.value !is VpnState.Error ->
                            VpnStateHolder.set(VpnState.Disconnected)
                    }
                }
                follow
            }
            if (!follow) finishService()
            follow
        }
        if (followUp) {
            Log.i(TAG, "connect resumed after teardown")
            startAsForeground(getString(R.string.notif_connecting))
            connect()
        }
    }

    /** 停数据面并把本段字节计入会话累计。 */
    private suspend fun stopDataPlaneAndCount() {
        stopRateNotificationUpdating()
        // 先收尾全局连接采集（残留会话落历史库），再停数据面
        runCatching {
            com.esrrhs.spp.client.util.ConnectionRecorder.stop(this@SppVpnService)
        }
        if (::networkWatchdog.isInitialized) networkWatchdog.stop()
        val finalStats = HevTunnel.stats()
        runCatching { HevTunnel.stop() }
        tunInterface?.let { pfd -> runCatching { pfd.close() } }
        tunInterface = null
        ruleServer?.stop()
        ruleServer = null
        sppProcess?.stop()
        sppProcess = null
        ActiveSession.socksPort = null
        if (finalStats != null) {
            val segTx = ((finalStats.getOrNull(1) ?: 0L) - baselineTx).coerceAtLeast(0)
            val segRx = ((finalStats.getOrNull(3)  ?: 0L) - baselineRx).coerceAtLeast(0)
            sessionTxTotal += segTx
            sessionRxTotal += segRx
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
        val profile = overrideProfile ?: run {
            val profiles = repository.profiles.first()
            val activeId = repository.activeId.first()
            profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
        }
        return if (profile == null) {
            throw SppException(getString(R.string.error_no_profile))
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
            .addDnsServer(TunConfig.FALLBACK_DNS)

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

        val cnV4 = if (profile.bypassCn) CnRouteList.loadV4(this) else emptyList()
        val cnV6 = if (profile.bypassCn) CnRouteList.loadV6(this) else emptyList()

        // CN 段扩展到对齐 /21 块（v6 为 /26）以适配 Binder parcel 上限
        CidrRoutes.publicCidrs(cnV4, CN_V4_EXPAND_PREFIX)
            .forEach { cidr -> builder.addRoute(cidr.address, cidr.prefix) }

        if (profile.config.enableIpv6) {
            // IPv6：CN 段直连，其余全球单播走代理；ULA/link-local 直连。
            // 受 Binder parcel 上限所限，CN 段扩展到对齐 /26 块。
            Cidr6Routes.globalCidrs(cnV6, CN_V6_EXPAND_PREFIX)
                .forEach { cidr -> builder.addRoute(cidr.address, cidr.prefix) }
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

    private fun validationText(error: ValidationError): String = getString(
        when (error) {
            ValidationError.NAME_REQUIRED -> R.string.err_name_required
            ValidationError.APPS_REQUIRED -> R.string.err_apps_required
            ValidationError.HOST_REQUIRED -> R.string.err_host_required
            ValidationError.PORT_RANGE -> R.string.err_port_range
            ValidationError.KEY_REQUIRED -> R.string.err_key_required
        },
    )

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

    private fun startRateNotificationUpdating(profileName: String) {
        currentProfileName = profileName
        notifRateJob?.cancel()
        rateMeter.reset()
        HevTunnel.stats()?.let { s ->
            val tx = s.getOrNull(1) ?: 0L
            val rx = s.getOrNull(3) ?: 0L
            rateMeter.rebaseline(tx, rx, System.currentTimeMillis())
        }
        notifRateJob = scope.launch {
            while (true) {
                delay(RATE_NOTIF_INTERVAL_MS)
                if (VpnStateHolder.state.value !is VpnState.Connected) break
                val raw = HevTunnel.stats() ?: continue
                val now = System.currentTimeMillis()
                val tx = raw.getOrNull(1) ?: 0L
                val rx = raw.getOrNull(3) ?: 0L
                val rates = rateMeter.update(tx, rx, now)
                val txStr = Formatters.formatRate(rates.txBytesPerSec)
                val rxStr = Formatters.formatRate(rates.rxBytesPerSec)
                val text = getString(
                    R.string.notif_connected_with_rate,
                    currentProfileName.orEmpty(),
                    txStr,
                    rxStr,
                )
                updateNotification(text)
            }
        }
    }

    private fun stopRateNotificationUpdating() {
        notifRateJob?.cancel()
        notifRateJob = null
        currentProfileName = null
        rateMeter.reset()
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

    private fun updateUnderlyingNetwork(network: Network?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            val net = network ?: getSystemService(ConnectivityManager::class.java)?.activeNetwork
            val networks = if (net != null) arrayOf(net) else null
            runCatching {
                setUnderlyingNetworks(networks)
                Log.i(TAG, "setUnderlyingNetworks: $net")
            }.onFailure {
                Log.w(TAG, "setUnderlyingNetworks failed", it)
            }
        }
    }

    companion object {
        const val ACTION_CONNECT = "com.esrrhs.spp.client.action.CONNECT"
        const val ACTION_DISCONNECT = "com.esrrhs.spp.client.action.DISCONNECT"
        const val EXTRA_PROFILE_JSON = "com.esrrhs.spp.client.extra.PROFILE_JSON"
        private val profileJson = Json { ignoreUnknownKeys = true }

        private const val CHANNEL_ID = "spp_vpn"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "SppVpnService"

        /** 每个配置连续失败多少次后轮换到下一个候选。 */
        private const val FAILOVER_CYCLE = 5
        private const val RATE_NOTIF_INTERVAL_MS = 1000L

        /** CN IPv6 段扩展到对齐 /22 块，使路由数控制在 ~138 条内以彻底杜绝 Binder 跨进程 Parcel 溢出 (Android 15 NetworkMonitor)。 */
        private const val CN_V6_EXPAND_PREFIX = 22

        /** CN IPv4 段扩展到对齐 /12 块，使路由数控制在 ~480 条内以彻底杜绝 Binder 跨进程 Parcel 溢出 (Android 15 NetworkMonitor)。 */
        private const val CN_V4_EXPAND_PREFIX = 12
    }
}
