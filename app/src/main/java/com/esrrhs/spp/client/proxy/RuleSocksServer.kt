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
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
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
                if (shouldDirect(endpoint.host, endpoint.atyp)) {
                    sendDirect(endpoint, packet.data, payloadOffset, payloadLen)
                } else {
                    sendUpstream(packet.data, packet.length)
                }
            }
        }

        fun close() {
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
        }

        /** 整包（仍带 SOCKS 头）转发上游 UDP 中继；上游未建立时懒建立。 */
        private fun sendUpstream(data: ByteArray, len: Int) {
            if (!ensureUpstream()) return
            val relay = upRelay ?: return
            val target = upstreamRelay ?: return
            try {
                relay.send(DatagramPacket(data.copyOf(len), len, target))
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "udp to-upstream: ${e.message}")
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
                } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "udp from-upstream: ${e.message}")
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

        /** 直连：域名走本地 DNS 解析（命中域名直连规则），IP 字面量直接用。 */
        private fun sendDirect(
            endpoint: Socks5Codec.UdpEndpoint,
            raw: ByteArray,
            payloadOffset: Int,
            payloadLen: Int,
        ) {
            if (payloadLen <= 0) return
            val address: InetAddress? = when (endpoint.atyp) {
                Socks5Codec.ATYP_DOMAIN.toInt() -> resolveHost(endpoint.host).firstOrNull()
                else -> runCatching { InetAddress.getByName(endpoint.host) }.getOrNull()
            }
            val socket = address?.let { obtainDirectSocket(it is Inet6Address) } ?: return
            try {
                val payload = ByteArray(payloadLen)
                System.arraycopy(raw, payloadOffset, payload, 0, payloadLen)
                socket.send(DatagramPacket(payload, payloadLen, InetSocketAddress(address, endpoint.port)))
            } catch (e: Exception) {
                if (running.get()) Log.v(TAG, "udp direct send ${endpoint.host}: ${e.message}")
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
                } catch (e: Exception) {
                    if (running.get()) Log.v(TAG, "udp direct recv: ${e.message}")
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

    private fun isDirectPenalized(host: String): Boolean {
        val expire = directPenaltyCache[host] ?: return false
        if (System.currentTimeMillis() < expire) return true
        directPenaltyCache.remove(host)
        return false
    }

    private fun markDirectPenalized(host: String) {
        directPenaltyCache[host] = System.currentTimeMillis() + DIRECT_PENALTY_MS
    }

    /**
     * 本地解析直连目标（App 自身被排除 VPN，走物理网络的系统解析器）。
     * 结果缓存 [DNS_CACHE_TTL_MS]；失败时在 TTL 内沿用上次的成功结果。
     * IPv4 排在前面（Happy Eyeballs 由调用方再做竞速）。
     */
    private fun resolveHost(host: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        dnsCache[host]?.let { cached ->
            if (now < cached.expireMs) return cached.addrs
        }
        val resolved = runCatching { InetAddress.getAllByName(host).toList() }.getOrNull().orEmpty()
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

        /** RFC 8305 Happy Eyeballs 先发优势窗口（毫秒）。 */
        const val HAPPY_EYEBALLS_HEAD_START_MS = 250L
        /** 上游代理预热连接池上限。 */
        const val PREWARM_POOL_SIZE = 4
        /** 本地 DNS 缓存有效时长（毫秒）。 */
        const val DNS_CACHE_TTL_MS = 60_000L

        fun isLoopback(host: String): Boolean =
            host == "127.0.0.1" || host == "localhost" || host == "::1"
    }
}
