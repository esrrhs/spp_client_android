package com.esrrhs.spp.client.proxy

import android.util.Log
import com.esrrhs.spp.client.util.DirectClassifier
import java.io.DataInputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** 上游 SOCKS5。SPP 模式是本机 socks5_client；SOCKS5 模式是远端代理。 */
data class SocksUpstream(
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = "",
)

/**
 * 本地 SOCKS5 分流代理，位于 hev-socks5-tunnel 与上游 SOCKS5 之间。
 *
 * TUN 始终全量抓包（0.0.0.0/0），所有「直连 vs 代理」决策都在本类用户态完成，
 * 因此不存在 VpnService 路由表 Binder parcel 尺寸限制（Android 15 NetworkMonitor
 * TransactionTooLargeException），CN 集合零近似、零膨胀：
 *
 * - CONNECT 携带域名且命中 [directDomains]：本进程直连（App 自身被排除在 VPN 外，
 *   socket 天然走物理网络），域名由本地解析；
 * - CONNECT 携带 IP 字面量（App 自带 DoH/自建 DNS 解析后直连的场景）：命中
 *   [directIpv4Cidrs]/[directIpv6Cidrs]（精确 chnroute）或 [bypassPrivate] 的
 *   私有/CGNAT 段时本进程直连；
 * - 其它 CONNECT：转发给 [upstream]（本机 SPP 或远端 SOCKS5），域名型请求仍由
 *   服务端解析（mapdns 防 DNS 泄漏链路不变）；
 * - UDP ASSOCIATE：逐报文解析 SOCKS5 目标，直连目标由本进程 DatagramSocket 直发，
 *   其余经上游 UDP 中继；上游中继按需懒建立，纯直连会话零上游连接。
 *   直连本地失败（域名解析失败/socket 异常）时与 TCP 一样短期惩罚并立即改经上游，
 *   不静默丢报文。上游 ASSOCIATE 失败则进入短暂冷却窗（防突发流量逐包建链风暴），
 *   冷却期后自动重试；上游中继失效（发送异常）即拆链，下一包懒重建自愈。
 *   域名直连的本地 DNS 解析在专用有界单线程上异步执行，弱网下慢解析不会阻塞
 *   run() 单循环而冻住其它 UDP 流量；解析队列堆积时新报文直接回退上游。
 *   系统解析无原生超时，统一以 2.5s 预算兜底（TCP 快速回退代理、UDP 惩罚并回退）；
 *   同一 host 在解析期间只有一个 owner 任务，突发报文合并待发、解析后排空，
 *   防止一个慢 host 占满解析队列连累其它域名直连。
 *
 * 纯阻塞 IO + 守护线程，生命周期与 VPN 数据面一致。
 */
class RuleSocksServer(
    private val upstream: SocksUpstream,
    private val directDomains: Set<String>,
    /** 命中即本地直连的 IPv4 CIDR（精确 chnroute，无块扩展）。 */
    private val directIpv4Cidrs: List<String> = emptyList(),
    /** 命中即本地直连的 IPv6 CIDR（精确 chnroute）。 */
    private val directIpv6Cidrs: List<String> = emptyList(),
    /** 把私有/保留网段（RFC1918、CGNAT、ULA 等）也纳入直连（绕过局域网）。 */
    private val bypassPrivate: Boolean = false,
) {
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private val relays = mutableListOf<DatagramSocket>()
    private val children = mutableListOf<Socket>()
    private val prewarmPool = ConcurrentLinkedQueue<Socket>()
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "rule-socks").apply { isDaemon = true }
    }

    /**
     * 承载阻塞式系统 DNS 查询的线程池：查询本身无法设置超时（getaddrinfo 不响应中断），
     * 用独立线程 + Future 预算来限制等待；弱网下超时的查询线程会作为 daemon 残留到自行返回。
     */
    private val dnsLookupPool = Executors.newCachedThreadPool { r ->
        Thread(r, "dns-lookup").apply { isDaemon = true }
    }

    private val classifier = DirectClassifier(
        directDomains = directDomains,
        directIpv4Cidrs = directIpv4Cidrs,
        directIpv6Cidrs = directIpv6Cidrs,
        bypassPrivate = bypassPrivate,
    )

    /** 直连失败短期惩罚缓存（实例级：网络切换/会话停止随本对象一起丢弃）。 */
    private val directPenaltyCache = ConcurrentHashMap<String, Long>()

    /** 直连域名 DNS 缓存（实例级，TTL 60s；网络切换后随会话重建清空）。 */
    private val dnsCache = ConcurrentHashMap<String, DnsCacheEntry>()

    /** 上游失败冷却截止时间（实例级，切网重建即清零；0 = 无冷却）。 */
    private val upstreamCooldownUntilMs = AtomicLong(0)

    val port: Int? get() = server?.localPort

    fun start() {
        if (running.getAndSet(true)) return
        val s = ServerSocket(0, 512, InetAddress.getByName("127.0.0.1"))
        server = s
        pool.execute { acceptLoop(s) }
        replenishPrewarmPool()
        Log.i(TAG, "rule socks proxy listening on 127.0.0.1:${s.localPort}, ${classifier.summary()}")
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { server?.close() }
        while (prewarmPool.isNotEmpty()) {
            runCatching { prewarmPool.poll()?.close() }
        }
        synchronized(children) {
            children.toList().forEach { runCatching { it.close() } }
            children.clear()
        }
        synchronized(relays) {
            relays.toList().forEach { runCatching { it.close() } }
            relays.clear()
        }
        directPenaltyCache.clear()
        dnsCache.clear()
        dnsLookupPool.shutdownNow()
        pool.shutdownNow()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running.get()) {
            val client = try {
                server.accept()
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "accept failed: ${e.message}")
                return
            }
            synchronized(children) { children.add(client) }
            pool.execute { handleClient(client) }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.tcpNoDelay = true
            val input = DataInputStream(client.getInputStream())
            val out = client.getOutputStream()
            if (!Socks5Codec.acceptMethod(input, out)) return
            val req = try {
                Socks5Codec.readRequest(input)
            } catch (e: Exception) {
                Log.w(TAG, "bad request: ${e.message}")
                return
            }
            when (req.command) {
                Socks5Codec.CMD_CONNECT.toInt() -> handleConnect(req, input, out, client)
                Socks5Codec.CMD_UDP_ASSOCIATE.toInt() -> handleUdpAssociate(out, client)
                else -> out.write(Socks5Codec.failureReply(Socks5Codec.REP_COMMAND_NOT_SUPPORTED))
            }
        } catch (e: Exception) {
            Log.v(TAG, "client ended: ${e.message}")
        } finally {
            runCatching { client.close() }
            synchronized(children) { children.remove(client) }
        }
    }

    /**
     * SOCKS5 请求目标是否应该本地直连（域名规则 / 精确 CIDR / 私有段）。
     * internal 以便单测直接锁定分流判定，而不必依赖真实连通性。
     */
    internal fun shouldDirect(endpointHost: String, atyp: Int): Boolean = when (atyp) {
        Socks5Codec.ATYP_DOMAIN.toInt() -> classifier.isDirectDomain(endpointHost)
        Socks5Codec.ATYP_IPV4.toInt(),
        Socks5Codec.ATYP_IPV6.toInt() -> classifier.isDirectIp(endpointHost)
        else -> false
    }

    private fun handleConnect(
        req: Socks5Codec.Request,
        input: DataInputStream,
        out: OutputStream,
        client: Socket,
    ) {
        val ruleDirect = shouldDirect(req.host, req.atyp)
        val started = System.currentTimeMillis()

        val inDirectPenalty = ruleDirect && isDirectPenalized(req.host)
        var directFailReason: String? = null
        var target: Socket? = null
        if (ruleDirect && !inDirectPenalty) {
            try {
                target = openConnected(req.host, req.port)
            } catch (e: Exception) {
                directFailReason = e.message ?: e.javaClass.simpleName
                markDirectPenalized(req.host)
                Log.i(TAG, "direct ${req.host}:${req.port} failed, penalized for ${DIRECT_PENALTY_MS / 1000}s, fallback to proxy: $directFailReason")
            }
        } else if (inDirectPenalty) {
            directFailReason = "penalized (previous direct failed)"
        }
        val usedDirect = target != null

        if (target == null) {
            target = try {
                connectUpstream(req)
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "connect ${req.host}:${req.port} (direct=$ruleDirect) failed: $reason")
                recordEvent(
                    req,
                    usedDirect,
                    false,
                    directFailReason?.let { "direct: $it; " }.orEmpty() + reason,
                    started,
                )
                runCatching {
                    out.write(Socks5Codec.failureReply(Socks5Codec.REP_GENERAL_FAILURE))
                    out.flush()
                }
                return
            }
        }

        // pipe 内部两个方向结束时会各自关闭两端 socket；
        // 这里不能在 finally 里提前 close，否则转发线程刚启动就被掐断。
        recordEvent(
            req,
            usedDirect,
            true,
            directFailReason?.let { "direct failed -> proxy ($it)" },
            started,
        )
        out.write(Socks5Codec.successReply("0.0.0.0", 0))
        out.flush()
        awaitPipe(client, target)
    }

    /** 连上上游并完成认证；失败抛异常，调用方负责后续 CONNECT / UDP ASSOCIATE。 */
    private fun openUpstream(): Socket {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.sendBufferSize = TCP_SOCKET_BUFFER
            socket.receiveBufferSize = TCP_SOCKET_BUFFER
            socket.connect(InetSocketAddress(upstream.host, upstream.port), CONNECT_TIMEOUT_MS)
            val upOut = socket.getOutputStream()
            val upIn = DataInputStream(socket.getInputStream())
            if (!Socks5Codec.authenticateClient(upIn, upOut, upstream.username, upstream.password)) {
                throw java.io.IOException("upstream auth rejected")
            }
            return socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    /** 补充上游预热池（最多 PREWARM_POOL_SIZE 条空闲认证就绪连接）。 */
    private fun replenishPrewarmPool() {
        if (!running.get()) return
        pool.execute {
            while (running.get() && prewarmPool.size < PREWARM_POOL_SIZE) {
                try {
                    val prewarmed = openUpstream()
                    if (running.get()) {
                        prewarmPool.offer(prewarmed)
                    } else {
                        runCatching { prewarmed.close() }
                        break
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    /** 从预热池取出一个可用连接，或实时建连。 */
    private fun acquireUpstream(): Socket {
        while (prewarmPool.isNotEmpty()) {
            val sock = prewarmPool.poll() ?: break
            if (!sock.isClosed && sock.isConnected) {
                replenishPrewarmPool()
                return sock
            }
            runCatching { sock.close() }
        }
        val sock = openUpstream()
        replenishPrewarmPool()
        return sock
    }

    /** 连接上游并完成 SOCKS5 CONNECT；优先复用已预热认证的连接。 */
    private fun connectUpstream(req: Socks5Codec.Request): Socket {
        var socket = acquireUpstream()
        try {
            val upOut = socket.getOutputStream()
            val upIn = DataInputStream(socket.getInputStream())
            upOut.write(req.raw)
            upOut.flush()
            if (Socks5Codec.readReply(upIn) == null) {
                throw java.io.IOException("upstream CONNECT rejected/timeout")
            }
            return socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            // 预热连接可能已被服务器因空闲超时断开，重试一次新建连
            socket = openUpstream()
            val upOut = socket.getOutputStream()
            val upIn = DataInputStream(socket.getInputStream())
            upOut.write(req.raw)
            upOut.flush()
            if (Socks5Codec.readReply(upIn) == null) {
                runCatching { socket.close() }
                throw java.io.IOException("upstream CONNECT rejected/timeout")
            }
            return socket
        }
    }

    /**
     * 双通道独立转发：
     * client -> target 和 target -> client 拆为两个完全独立的单向通道。
     * 当任一方向读到 EOF (FIN) 时，立即将对端输出半关闭 (shutdownOutput())，
     * 但保留反向继续传输的能力（严格兼容 HTTP/2 和 gRPC 双向流）。
     * 当双向转发全部完成，或者发生底层异常时，统一关闭两端 Socket。
     */
    private fun awaitPipe(a: Socket, b: Socket) {
        runCatching {
            a.sendBufferSize = TCP_SOCKET_BUFFER
            a.receiveBufferSize = TCP_SOCKET_BUFFER
            b.sendBufferSize = TCP_SOCKET_BUFFER
            b.receiveBufferSize = TCP_SOCKET_BUFFER
        }
        val latch = java.util.concurrent.CountDownLatch(2)

        val forwardDirection = { from: Socket, to: Socket ->
            try {
                copyDirection(from, to)
            } finally {
                // 该方向读取结束，通知目标端此方向已无新数据 (发送 TCP FIN)
                runCatching {
                    if (!to.isClosed && !to.isOutputShutdown) {
                        to.shutdownOutput()
                    }
                }
                latch.countDown()
            }
        }

        pool.execute { forwardDirection(a, b) }
        pool.execute { forwardDirection(b, a) }

        // 等待两个单向通道全部完成（或异常退出）
        try {
            latch.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            runCatching { a.close() }
            runCatching { b.close() }
        }
    }

    private fun recordEvent(
        req: Socks5Codec.Request,
        direct: Boolean,
        success: Boolean,
        reason: String?,
        startedMs: Long,
    ) {
        runCatching {
            ProxyEventBus.record(
                host = req.host,
                port = req.port,
                direct = direct,
                success = success,
                reason = reason,
                durationMs = (System.currentTimeMillis() - startedMs).toInt(),
            )
        }
    }

    /**
     * 解析 [host] 的全部地址并并发尝试连接（RFC 8305 Happy Eyeballs 竞速）。
     * 首选 IPv4，若首个地址在 250ms 内未连接成功，立即发起下一个地址的尝试。
     * 任一成功即返回已连接的 Socket，其余连接被快速取消并关闭。
     */
    private fun openConnected(host: String, port: Int): Socket {
        val addresses = resolveHost(host)
        if (addresses.isEmpty()) {
            throw java.net.UnknownHostException(host)
        }
        if (addresses.size == 1) {
            val addr = addresses[0]
            val s = Socket()
            s.tcpNoDelay = true
            s.sendBufferSize = TCP_SOCKET_BUFFER
            s.receiveBufferSize = TCP_SOCKET_BUFFER
            s.connect(InetSocketAddress(addr, port), ADDR_CONNECT_TIMEOUT_MS.toInt())
            return s
        }

        // 多地址：Happy Eyeballs 并发竞速
        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
        val completionQueue = java.util.concurrent.LinkedBlockingQueue<Result<Socket>>()
        val activeSockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
        var dispatched = 0
        var completed = 0

        for (addr in addresses) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0 && dispatched > 0) break

            val s = Socket()
            activeSockets.add(s)
            dispatched++

            pool.execute {
                try {
                    s.tcpNoDelay = true
                    s.sendBufferSize = TCP_SOCKET_BUFFER
                    s.receiveBufferSize = TCP_SOCKET_BUFFER
                    val timeout = (deadline - System.currentTimeMillis()).coerceIn(100, ADDR_CONNECT_TIMEOUT_MS)
                    s.connect(InetSocketAddress(addr, port), timeout.toInt())
                    completionQueue.offer(Result.success(s))
                } catch (e: Exception) {
                    runCatching { s.close() }
                    activeSockets.remove(s)
                    completionQueue.offer(Result.failure(e))
                }
            }

            // 给予先发地址 250ms 优势窗口；如果 250ms 内有任一成功，直接返回
            val res = completionQueue.poll(HAPPY_EYEBALLS_HEAD_START_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (res != null) {
                completed++
                if (res.isSuccess) {
                    val winner = res.getOrThrow()
                    activeSockets.remove(winner)
                    activeSockets.forEach { runCatching { it.close() } }
                    return winner
                }
            }
        }

        // 等待所有已派发连接出结果
        var lastError: Exception? = null
        while (completed < dispatched) {
            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1)
            val res = completionQueue.poll(remaining, java.util.concurrent.TimeUnit.MILLISECONDS) ?: break
            completed++
            if (res.isSuccess) {
                val winner = res.getOrThrow()
                activeSockets.remove(winner)
                activeSockets.forEach { runCatching { it.close() } }
                return winner
            } else {
                lastError = res.exceptionOrNull() as? Exception
            }
        }

        activeSockets.forEach { runCatching { it.close() } }
        throw lastError ?: java.io.IOException("cannot connect to $host:$port (Happy Eyeballs timeout)")
    }

    private fun copyDirection(from: Socket, to: Socket) {
        try {
            val buf = ByteArray(BUFFER_SIZE)
            val input = from.getInputStream()
            val out = to.getOutputStream()
            while (running.get() && !from.isClosed && !to.isClosed) {
                val n = input.read(buf)
                if (n < 0) break // 读到 EOF
                out.write(buf, 0, n)
                out.flush()
            }
        } catch (_: Exception) {
            // 对端或本地连接异常中断，快速关闭以唤醒对向读写
            runCatching { from.close() }
            runCatching { to.close() }
        }
    }

    // ============================== UDP ==============================

    private fun handleUdpAssociate(out: OutputStream, control: Socket) {
        // 面向 hev 的中继（hev 把报文发到这里）；上游/直连中继在 UdpSplitter 内按需建立
        val clientRelay = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val splitter = UdpSplitter(clientRelay, control)
        synchronized(relays) { relays.add(clientRelay) }
        try {
            clientRelay.receiveBufferSize = UDP_SOCKET_BUFFER
            out.write(Socks5Codec.successReply("127.0.0.1", clientRelay.localPort))
            out.flush()
            Log.i(TAG, "udp splitter relay on 127.0.0.1:${clientRelay.localPort}, upstream=$upstream")
            splitter.run()
        } catch (e: Exception) {
            Log.w(TAG, "udp associate failed: ${e.message}")
        } finally {
            splitter.close()
            synchronized(relays) { relays.remove(clientRelay) }
            runCatching { clientRelay.close() }
        }
    }

    /**
     * 单个 UDP ASSOCIATE 会话的分流中继。
     *
     * - hev -> 本进程：解析 SOCKS5 UDP 头，按 [shouldDirect] 判定：
     *   直连报文经本地 wildcard DatagramSocket 直发物理网络（App 已被排除 VPN），
     *   代理报文整包转发上游 UDP 中继（上游中继懒建立，纯直连会话不产生上游连接）；
     * - 目标/上游 -> 本进程：封装（直连）或透传（上游已封装）SOCKS5 UDP 头后回给 hev。
     */
    private inner class UdpSplitter(
        private val clientRelay: DatagramSocket,
        private val control: Socket,
    ) {
        private val gate = Any()
        private var upControl: Socket? = null
        private var upRelay: DatagramSocket? = null
        private var upstreamRelay: InetSocketAddress? = null
        private var directV4: DatagramSocket? = null
        private var directV6: DatagramSocket? = null
        private val oversizeLogged = AtomicBoolean(false)

        /**
         * 域名直连解析的专用有界单线程执行器（懒创建：纯代理/纯 IP 会话零线程）。
         * 1 线程串行 + 有界队列天然限流，避免慢解析堆积也避免线程爆炸。
         */
        private var resolveExecutor: ThreadPoolExecutor? = null

        /**
         * 正在解析中的 host → 合并等待的原始报文队列（首个报文为 owner，自行提交解析任务）。
         * owner 完成后先摘除本标记再排空队列，避免新到达报文滞留。
         */
        private val inflightHosts =
            ConcurrentHashMap<String, ConcurrentLinkedQueue<ByteArray>>()

        /** RFC 1928：只与发起 ASSOCIATE 的客户端地址通信，首个报文锁定。 */
        private val peer = AtomicReference<InetSocketAddress>()

        fun run() {
            val buf = ByteArray(UDP_BUFFER_SIZE)
            while (running.get() && !control.isClosed && !clientRelay.isClosed) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    clientRelay.soTimeout = TIMEOUT_MS
                    clientRelay.receive(packet)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "udp from-hev: ${e.message}")
                    break
                }

                val sender = packet.socketAddress as? InetSocketAddress ?: continue
                val lockedPeer = peer.get()
                if (lockedPeer != null && lockedPeer != sender) continue

                val parsed = Socks5Codec.parseUdpPacket(packet.data, packet.length) ?: continue
                if (lockedPeer == null) peer.set(sender)

                val (endpoint, payloadOffset) = parsed
                val payloadLen = packet.length - payloadOffset

                // 与 TCP 对称：命中直连且不在惩罚窗内才走本地直发；
                // 直连本地失败或此前已被惩罚时，立即改经上游，不丢报文。
                val ruleDirect = shouldDirect(endpoint.host, endpoint.atyp)
                val skipDirect = !ruleDirect ||
                    isDirectPenalizedKey(udpDirectKey(endpoint.host, endpoint.port))

                if (skipDirect) {
                    sendUpstream(packet.data, packet.length)
                } else if (endpoint.atyp == Socks5Codec.ATYP_DOMAIN.toInt()) {
                    // 域名解析在弱网下可能慢：不做在 run() 单循环上（见 round6），
                    // 且同一 host 在解析期间只允许一个 owner 任务——突发的其余报文合并入队，
                    // 解析完成后统一派发，避免一个慢 host 占满有界解析队列、连累其它域名。
                    val copy = packet.data.copyOf(packet.length)
                    val pending = inflightHosts.putIfAbsent(endpoint.host, ConcurrentLinkedQueue())
                    if (pending == null) {
                        dispatchResolveOwner(endpoint.host, copy)
                    } else if (pending.size < PENDING_PER_HOST) {
                        pending.add(copy)
                    } else {
                        sendUpstream(copy, copy.size) // 单 host 待发堆积超限，溢出走上游
                    }
                } else {
                    // IP 字面量：本地 send 立即返回，保持同步快速路径。
                    if (!sendDirect(endpoint, packet.data, payloadOffset, payloadLen)) {
                        sendUpstream(packet.data, packet.length)
                    }
                }
            }
        }

        fun close() {
            inflightHosts.clear() // ASSOCIATE 拆除：合并等待的报文随之放弃
            runCatching { upControl?.close() }
            runCatching { upRelay?.close() }
            runCatching { directV4?.close() }
            runCatching { directV6?.close() }
            synchronized(relays) {
                upRelay?.let { relays.remove(it) }
                directV4?.let { relays.remove(it) }
                directV6?.let { relays.remove(it) }
            }
            upControl = null
            upRelay = null
            directV4 = null
            directV6 = null
            upstreamRelay = null
            // 关闭解析执行器：正在进行的解析被中断，排队任务丢弃（ASSOCIATE 已拆除，无后续报文）。
            resolveExecutor?.let { exec ->
                resolveExecutor = null
                exec.shutdownNow()
                runCatching { exec.awaitTermination(200, TimeUnit.MILLISECONDS) }
            }
        }

        /** 懒创建域名直连解析执行器（gate 内）；失败返回 null（调用方直接走上游）。 */
        private fun obtainResolveExecutor(): ThreadPoolExecutor? = synchronized(gate) {
            resolveExecutor?.let { return it }
            if (!running.get()) return null
            val exec = runCatching {
                ThreadPoolExecutor(
                    1, 1, 0L, TimeUnit.MILLISECONDS,
                    ArrayBlockingQueue(RESOLVE_QUEUE_CAPACITY),
                    { r -> Thread(r, "udp-direct-resolve").apply { isDaemon = true } },
                    ThreadPoolExecutor.AbortPolicy(),
                )
            }.getOrNull() ?: return null
            resolveExecutor = exec
            exec
        }

        /**
         * 提交 host 的 owner 解析任务：任务内解析+直发首包（失败回退上游），
         * 随后先摘除 inflight 标记、再排空合并队列逐条走相同决策。
         * 提交被拒（解析队列满）或执行器不可用时放弃 owner 身份，首包直接走上游。
         */
        private fun dispatchResolveOwner(host: String, ownerCopy: ByteArray) {
            val executor = obtainResolveExecutor()
            var accepted = false
            if (executor != null) {
                try {
                    executor.execute {
                        try {
                            val parsed = Socks5Codec.parseUdpPacket(ownerCopy, ownerCopy.size)
                            if (parsed != null) {
                                val (ep, off) = parsed
                                if (!sendDirect(ep, ownerCopy, off, ownerCopy.size - off)) {
                                    sendUpstream(ownerCopy, ownerCopy.size)
                                }
                            }
                        } finally {
                            val queue = inflightHosts.remove(host)
                            queue?.let { drainPending(it) }
                        }
                    }
                    accepted = true
                } catch (_: RejectedExecutionException) {
                    accepted = false
                }
            }
            if (!accepted) {
                // 拒绝窗口内可能已有报文合并进旧队列：摘标记并全部回退上游，不丢包。
                inflightHosts.remove(host)?.let { q ->
                    while (true) {
                        val raw = q.poll() ?: break
                        sendUpstream(raw, raw.size)
                    }
                }
                sendUpstream(ownerCopy, ownerCopy.size)
            }
        }

        private fun drainPending(queue: ConcurrentLinkedQueue<ByteArray>) {
            while (true) {
                val raw = queue.poll() ?: return
                val parsed = Socks5Codec.parseUdpPacket(raw, raw.size) ?: continue
                val (ep, off) = parsed
                if (!sendDirect(ep, raw, off, raw.size - off)) {
                    sendUpstream(raw, raw.size)
                }
            }
        }

        /** 整包（仍带 SOCKS 头）转发上游 UDP 中继；上游未建立时懒建立。 */
        private fun sendUpstream(data: ByteArray, len: Int) {
            if (System.currentTimeMillis() < upstreamCooldownUntilMs.get()) {
                // 上游刚失败过（冷却窗内）：快速丢弃，避免突发流量逐包触发建链风暴
                return
            }
            if (!ensureUpstream()) return
            val relay = upRelay ?: return
            val target = upstreamRelay ?: return
            try {
                relay.send(DatagramPacket(data.copyOf(len), len, target))
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "udp to-upstream: ${e.message}")
                // 上游会话已失效：拆链（receiveUpstreamLoop 随 socket 关闭退出），下一包懒重建自愈
                resetUpstream()
            }
        }

        /** 拆除上游 ASSOCIATE 会话；仅在失败/失效路径调用，成功路径不复位。 */
        private fun resetUpstream() {
            synchronized(gate) {
                runCatching { upControl?.close() }
                runCatching { upRelay?.close() }
                synchronized(relays) { upRelay?.let { relays.remove(it) } }
                upControl = null
                upRelay = null
                upstreamRelay = null
            }
        }

        /**
         * 监视上游控制连接：远端关闭（EOF）或读异常即拆链重建。
         * 不进冷却——上游重启不是建链失败，会话应立即自愈而非惩罚 5s。
         * close()/resetUpstream 已本地关 ctrl 时，read 抛异常但守卫不触发。
         */
        private fun watchUpstreamControl(ctrl: Socket, ctrlIn: DataInputStream) {
            try {
                // 控制连接在 ASSOCIATE 后不应再有数据；个别服务器发 keepalive 时忽略，
                // 持续读到真 EOF（远端关闭）或本地关闭导致的异常。
                while (ctrlIn.read() >= 0) { /* keepalive 字节，忽略 */ }
            } catch (_: Exception) {
            }
            if (running.get() && !ctrl.isClosed && upControl === ctrl) {
                Log.i(TAG, "udp upstream control closed by peer, rebuilding association on demand")
                resetUpstream()
            }
        }

        private fun ensureUpstream(): Boolean {
            upstreamRelay?.let { return true }
            synchronized(gate) {
                upstreamRelay?.let { return true }
                var ctrl: Socket? = null
                var relay: DatagramSocket? = null
                try {
                    ctrl = openUpstream()
                    // 远端 SOCKS5 的 BND 不在回环上时不能绑 127.0.0.1（公网回包会被内核丢弃）
                    relay = if (isLoopback(upstream.host)) {
                        DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
                    } else {
                        DatagramSocket()
                    }
                    relay.receiveBufferSize = UDP_SOCKET_BUFFER
                    val ctrlOut = ctrl.getOutputStream()
                    val ctrlIn = DataInputStream(ctrl.getInputStream())
                    ctrlOut.write(
                        Socks5Codec.buildRequest(
                            Socks5Codec.CMD_UDP_ASSOCIATE,
                            Socks5Codec.ATYP_IPV4.toInt(),
                            "0.0.0.0",
                            0,
                        ),
                    )
                    ctrlOut.flush()
                    val bound = Socks5Codec.readReply(ctrlIn)
                        ?: throw java.io.IOException("upstream UDP ASSOCIATE rejected")

                    upControl = ctrl
                    upRelay = relay
                    upstreamRelay = upstreamRelayAddress(bound.first, bound.second)
                    synchronized(relays) { relays.add(relay) }
                    pool.execute { receiveUpstreamLoop(relay, ctrl) }
                    // 看门狗：远端关控制连接（上游重启/网络切换）即拆链，下一包懒重建。
                    // UDP relay 本地 send 永远"成功"，控制连接 EOF 是上游静默死亡的唯一信号。
                    pool.execute { watchUpstreamControl(ctrl, ctrlIn) }
                    Log.i(
                        TAG,
                        "udp upstream relay 127.0.0.1:${relay.localPort} -> " +
                            "${upstreamRelay?.hostString}:${upstreamRelay?.port}",
                    )
                    return true
                } catch (e: Exception) {
                    Log.w(TAG, "udp upstream associate failed: ${e.message}")
                    runCatching { ctrl?.close() }
                    runCatching { relay?.close() }
                    upControl = null
                    upRelay = null
                    upstreamRelay = null
                    // 冷却窗：防突发流量逐包重试建链（TCP CONNECT 逐包排队会雪崩）
                    upstreamCooldownUntilMs.set(System.currentTimeMillis() + UPSTREAM_COOLDOWN_MS)
                    return false
                }
            }
        }

        private fun receiveUpstreamLoop(relay: DatagramSocket, ctrl: Socket) {
            val buf = ByteArray(UDP_BUFFER_SIZE)
            while (running.get() && !ctrl.isClosed && !relay.isClosed) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    relay.soTimeout = TIMEOUT_MS
                    relay.receive(packet)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } catch (_: java.net.PortUnreachableException) {
                    // ICMP port-unreachable：只针对刚刚中继的那个目标，socket 对其它流仍然可用，
                    // 绝不能拆链（否则一个关端口的目标会连累整个 ASSOCIATE 重建）。
                    continue
                } catch (e: Exception) {
                    // 非本地关闭的异常：中继已死但引用还在，sendUpstream 会一直"成功"却无回包；
                    // 拆链让下一包懒重建（与 watchUpstreamControl 对称）。
                    if (running.get() && !ctrl.isClosed && upRelay === relay) {
                        Log.w(TAG, "udp upstream relay died, rebuilding: ${e.message}")
                        resetUpstream()
                    } else if (running.get()) {
                        Log.v(TAG, "udp from-upstream: ${e.message}")
                    }
                    break
                }
                val target = peer.get() ?: continue
                // 上游回复本身已带 SOCKS UDP 头；防御性校验后透传给 hev
                if (packet.length < 4 ||
                    packet.data[0] != 0.toByte() || packet.data[1] != 0.toByte()
                ) {
                    continue
                }
                warnIfOversized(packet.length, "to-hev", oversizeLogged)
                try {
                    clientRelay.send(DatagramPacket(packet.data.copyOf(packet.length), packet.length, target))
                } catch (_: Exception) {
                    break
                }
            }
        }

        /**
         * 直连：域名走本地 DNS 解析（命中域名直连规则），IP 字面量直接用。
         * 返回是否已交给本地直连路径；本地解析/socket/发送失败时惩罚该目标并返回 false，
         * 调用方随即回退上游（UDP 无连接，远端不可达无法在此感知，不惩罚）。
         */
        private fun sendDirect(
            endpoint: Socks5Codec.UdpEndpoint,
            raw: ByteArray,
            payloadOffset: Int,
            payloadLen: Int,
        ): Boolean {
            if (payloadLen <= 0) return false
            val key = udpDirectKey(endpoint.host, endpoint.port)
            val address: InetAddress? = when (endpoint.atyp) {
                Socks5Codec.ATYP_DOMAIN.toInt() -> resolveHost(endpoint.host).firstOrNull()
                else -> runCatching { InetAddress.getByName(endpoint.host) }.getOrNull()
            }
            if (address == null) {
                penalizeDirectKey(key)
                Log.i(TAG, "udp direct ${endpoint.host}:${endpoint.port} unresolved, penalized, via upstream")
                return false
            }
            val socket = obtainDirectSocket(address is Inet6Address)
            if (socket == null) {
                penalizeDirectKey(key)
                return false
            }
            try {
                val payload = ByteArray(payloadLen)
                System.arraycopy(raw, payloadOffset, payload, 0, payloadLen)
                socket.send(DatagramPacket(payload, payloadLen, InetSocketAddress(address, endpoint.port)))
                return true
            } catch (e: Exception) {
                penalizeDirectKey(key)
                Log.i(TAG, "udp direct ${endpoint.host}:${endpoint.port} send failed, penalized, via upstream: ${e.message}")
                return false
            }
        }

        private fun obtainDirectSocket(useV6: Boolean): DatagramSocket? = synchronized(gate) {
            val existing = if (useV6) directV6 else directV4
            if (existing != null) return existing
            val socket = runCatching {
                DatagramSocket().apply { receiveBufferSize = UDP_SOCKET_BUFFER }
            }.getOrNull() ?: return null
            if (useV6) directV6 = socket else directV4 = socket
            synchronized(relays) { relays.add(socket) }
            pool.execute { receiveDirectLoop(socket, useV6) }
            socket
        }

        private fun receiveDirectLoop(socket: DatagramSocket, useV6: Boolean) {
            val buf = ByteArray(UDP_BUFFER_SIZE)
            while (running.get() && !control.isClosed && !socket.isClosed) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.soTimeout = TIMEOUT_MS
                    socket.receive(packet)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } catch (_: java.net.PortUnreachableException) {
                    continue // 某直发目标的 ICMP port-unreachable，非致命，socket 仍可用于其它流
                } catch (e: Exception) {
                    // 非本地关闭的异常：清掉字段并关 socket，下一次 sendDirect 的
                    // obtainDirectSocket 会新建（否则直发仍"成功"但回包永不再来）。
                    if (running.get() && !control.isClosed && !socket.isClosed) {
                        Log.w(TAG, "udp direct relay died, recreating: ${e.message}")
                        synchronized(gate) {
                            if (useV6) {
                                if (directV6 === socket) directV6 = null
                            } else {
                                if (directV4 === socket) directV4 = null
                            }
                        }
                        runCatching { socket.close() }
                        synchronized(relays) { relays.remove(socket) }
                    } else if (running.get()) {
                        Log.v(TAG, "udp direct recv: ${e.message}")
                    }
                    break
                }
                val target = peer.get() ?: continue
                val src = packet.socketAddress as? InetSocketAddress ?: continue
                val host = src.address?.hostAddress?.substringBefore('%') ?: continue
                // 这条才是 hev 用 1500 字节缓冲接收的方向，超限会被截成坏包。
                warnIfOversized(packet.length, "to-hev", oversizeLogged)
                val atyp = if (useV6) Socks5Codec.ATYP_IPV6.toInt()
                else Socks5Codec.ATYP_IPV4.toInt()
                val header = Socks5Codec.buildUdpHeader(atyp, host, src.port)
                val out = ByteArray(header.size + packet.length)
                System.arraycopy(header, 0, out, 0, header.size)
                System.arraycopy(packet.data, 0, out, header.size, packet.length)
                try {
                    clientRelay.send(DatagramPacket(out, out.size, target))
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    /**
     * SOCKS5 UDP ASSOCIATE 服务器回复的中继地址：
     * - 本机 SPP 固定走 127.0.0.1；
     * - 远端 SOCKS5：若服务器回复了通配地址（0.0.0.0 / ::）、本地回环（127.x）
     *   或私有网段（10.x, 172.16-31.x, 192.168.x 等内网 NAT 地址），
     *   手机作为公网客户端无法直连该私有地址，必须改用与控制连接相同的 [upstream.host]。
     */
    internal fun upstreamRelayAddress(boundHost: String, boundPort: Int): InetSocketAddress {
        if (isLoopback(upstream.host)) {
            return InetSocketAddress("127.0.0.1", boundPort)
        }
        val useUpstreamHost = boundHost.isBlank() ||
            boundHost == "0.0.0.0" ||
            boundHost == "::" ||
            isUnroutableFromRemote(boundHost)
        val host = if (useUpstreamHost) upstream.host else boundHost
        return InetSocketAddress(host, boundPort)
    }

    private fun isUnroutableFromRemote(host: String): Boolean {
        val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return true
        return addr.isAnyLocalAddress ||
            addr.isLoopbackAddress ||
            addr.isSiteLocalAddress ||
            addr.isLinkLocalAddress
    }

    /** hev 回程缓冲是 1500（含 SOCKS 头）。每条中继只打一次，避免 QUIC 刷屏。 */
    private fun warnIfOversized(length: Int, dir: String, logged: AtomicBoolean) {
        if (length <= HEV_UDP_RECV_LIMIT || !logged.compareAndSet(false, true)) return
        Log.w(TAG, "udp $dir datagram ${length}B > $HEV_UDP_RECV_LIMIT; hev will truncate QUIC")
    }

    private fun isDirectPenalized(key: String): Boolean {
        val expire = directPenaltyCache[key] ?: return false
        if (System.currentTimeMillis() < expire) return true
        directPenaltyCache.remove(key)
        return false
    }

    private fun markDirectPenalized(key: String) {
        directPenaltyCache[key] = System.currentTimeMillis() + DIRECT_PENALTY_MS
    }

    // ---- UDP 直连惩罚（与 TCP 共用缓存；udp: 前缀避免与 TCP 的裸 host 键冲突） ----

    internal fun udpDirectKey(host: String, port: Int): String = "udp:$host:$port"

    internal fun isDirectPenalizedKey(key: String): Boolean = isDirectPenalized(key)

    internal fun penalizeDirectKey(key: String) = markDirectPenalized(key)

    /**
     * 本地解析直连目标（App 自身被排除 VPN，走物理网络的系统解析器）。
     * 结果缓存 [DNS_CACHE_TTL_MS]；失败时在 TTL 内沿用上次的成功结果。
     * IPv4 排在前面（Happy Eyeballs 由调用方再做竞速）。
     *
     * 系统解析没有原生超时：弱网/无响应 DNS 下 getaddrinfo 可能阻塞数十秒，
     * 这里用 [DNS_RESOLVE_BUDGET_MS] 预算兜底——超时按失败处理（TCP 快速回退
     * 代理、UDP 惩罚目标并回退），不让任何一条流程被解析无限期拖住。
     */
    private fun resolveHost(host: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        dnsCache[host]?.let { cached ->
            if (now < cached.expireMs) return cached.addrs
        }
        val resolved = runWithBudget(dnsLookupPool, DNS_RESOLVE_BUDGET_MS) {
            InetAddress.getAllByName(host).toList()
        }.orEmpty()
        if (resolved.isNotEmpty()) {
            val ordered = resolved.sortedBy { if (it is Inet6Address) 1 else 0 }
            dnsCache[host] = DnsCacheEntry(ordered, now + DNS_CACHE_TTL_MS)
            return ordered
        }
        return dnsCache[host]?.addrs.orEmpty()
    }

    private data class DnsCacheEntry(val addrs: List<InetAddress>, val expireMs: Long)

    private companion object {
        const val TAG = "RuleSocksServer"
        const val CONNECT_TIMEOUT_MS = 3000
        /** 单个地址的连接尝试上限，保证失败时秒级回退代理，不让用户等待。 */
        const val ADDR_CONNECT_TIMEOUT_MS = 1500L
        const val TIMEOUT_MS = 1000
        const val BUFFER_SIZE = 64 * 1024
        const val UDP_BUFFER_SIZE = 64 * 1024
        /** 与 hev-socks5-tunnel 的 UDP_BUF_SIZE 一致，回程整包（含 SOCKS 头）不能超过它。 */
        const val HEV_UDP_RECV_LIMIT = 1500
        const val UDP_SOCKET_BUFFER = 512 * 1024
        const val TCP_SOCKET_BUFFER = 256 * 1024

        /** 直连失败的惩罚时长（毫秒）：此时间内该目标直接走代理，消除并发或连续请求的重复回退等待。 */
        const val DIRECT_PENALTY_MS = 180_000L // 3 分钟

        /** 上游 UDP ASSOCIATE 失败后的冷却时长（毫秒）：冷却期内报文直接丢弃，防逐包建链风暴。 */
        const val UPSTREAM_COOLDOWN_MS = 5_000L

        /** 域名直连解析任务的排队上限：超出即回退上游，避免慢解析无限堆积。 */
        const val RESOLVE_QUEUE_CAPACITY = 16

        /** RFC 8305 Happy Eyeballs 先发优势窗口（毫秒）。 */
        const val HAPPY_EYEBALLS_HEAD_START_MS = 250L
        /** 上游代理预热连接池上限。 */
        const val PREWARM_POOL_SIZE = 4
        /** 本地 DNS 缓存有效时长（毫秒）。 */
        const val DNS_CACHE_TTL_MS = 60_000L

        /** 单次系统 DNS 查询的等待预算（毫秒）：超时按解析失败处理，快速回退代理。 */
        const val DNS_RESOLVE_BUDGET_MS = 2_500L

        /** UDP 在途解析合并时，单个 host 缓存待发报文的上限，超出直接走上游。 */
        const val PENDING_PER_HOST = 32

        /**
         * 在 [executor] 上执行 [work]，最多等待 [timeoutMs]；成功返回结果，
         * 超时取消任务并返回 null（不响应中断的阻塞调用其线程会残留），
         * work 抛异常或等待被中断也返回 null。internal 以便单测锁定预算行为。
         */
        internal fun <T> runWithBudget(
            executor: java.util.concurrent.ExecutorService,
            timeoutMs: Long,
            work: () -> T,
        ): T? {
            val future = executor.submit(work)
            return try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: java.util.concurrent.TimeoutException) {
                future.cancel(true)
                null
            } catch (_: java.util.concurrent.ExecutionException) {
                null
            } catch (_: InterruptedException) {
                future.cancel(true)
                Thread.currentThread().interrupt()
                null
            }
        }

        fun isLoopback(host: String): Boolean =
            host == "127.0.0.1" || host == "localhost" || host == "::1"
    }
}
