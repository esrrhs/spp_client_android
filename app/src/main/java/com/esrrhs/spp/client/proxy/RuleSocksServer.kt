package com.esrrhs.spp.client.proxy

import android.util.Log
import java.io.DataInputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
 * - CONNECT 带域名且命中 [directDomains]：本进程直连（App 自身被排除在 VPN 外，
 *   socket 天然走物理网络），域名由本地解析；
 * - 其它 CONNECT（含所有 IP 字面量）：转发给 [upstream]（本机 SPP 或远端 SOCKS5）；
 * - UDP ASSOCIATE：在本地与上游各建一个 UDP 中继，数据报文统一转发上游。
 *   远端 SOCKS5 的中继地址用服务器回复的 BND，不再假定 127.0.0.1。
 *
 * 纯阻塞 IO + 守护线程，生命周期与 VPN 数据面一致。
 */
class RuleSocksServer(
    private val upstream: SocksUpstream,
    private val directDomains: Set<String>,
) {
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private val relays = mutableListOf<DatagramSocket>()
    private val children = mutableListOf<Socket>()
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "rule-socks").apply { isDaemon = true }
    }

    val port: Int? get() = server?.localPort

    fun start() {
        if (running.getAndSet(true)) return
        val s = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        server = s
        pool.execute { acceptLoop(s) }
        Log.i(TAG, "rule socks proxy listening on 127.0.0.1:${s.localPort}, direct rules=${directDomains.size}")
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { server?.close() }
        synchronized(children) {
            children.toList().forEach { runCatching { it.close() } }
            children.clear()
        }
        synchronized(relays) {
            relays.toList().forEach { runCatching { it.close() } }
            relays.clear()
        }
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
                Socks5Codec.CMD_UDP_ASSOCIATE.toInt() -> handleUdpAssociate(req, out, client)
                else -> out.write(Socks5Codec.failureReply(Socks5Codec.REP_COMMAND_NOT_SUPPORTED))
            }
        } catch (e: Exception) {
            Log.v(TAG, "client ended: ${e.message}")
        } finally {
            runCatching { client.close() }
            synchronized(children) { children.remove(client) }
        }
    }

    private fun handleConnect(
        req: Socks5Codec.Request,
        input: DataInputStream,
        out: OutputStream,
        client: Socket,
    ) {
        val ruleDirect = req.atyp == Socks5Codec.ATYP_DOMAIN.toInt() &&
            com.esrrhs.spp.client.util.DomainRuleMatcher.matches(req.host, directDomains)
        val started = System.currentTimeMillis()

        // 优先按规则直连；直连失败（DNS 失败/目标不可达）时自动回退 SPP 隧道重试。
        // 否则域名表误标或该网络下直连不通会直接让 App 失败（如系统联网探测域名）。
        var directFailReason: String? = null
        var target: Socket? = null
        if (ruleDirect) {
            try {
                target = openConnected(req.host, req.port)
            } catch (e: Exception) {
                directFailReason = e.message ?: e.javaClass.simpleName
                Log.i(TAG, "direct ${req.host}:${req.port} failed, fallback to proxy: $directFailReason")
            }
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

    /** 连接上游并完成 SOCKS5 CONNECT；失败抛异常。 */
    private fun connectUpstream(req: Socks5Codec.Request): Socket {
        val socket = openUpstream()
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

    /** 双向转发并阻塞至任一端结束，再统一关闭两端。 */
    private fun awaitPipe(a: Socket, b: Socket) {
        val latch = java.util.concurrent.CountDownLatch(2)
        val oneSide = { from: Socket, to: Socket ->
            try {
                copyCatching(from, to)
            } finally {
                latch.countDown()
            }
        }
        pool.execute { oneSide(a, b) }
        pool.execute { oneSide(b, a) }
        latch.await()
        runCatching { a.close() }
        runCatching { b.close() }
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
     * 解析 [host] 的全部地址并逐个尝试连接（IPv4 优先，IPv6 兜底），
     * 任一成功即返回已连接的 Socket；全部失败抛出最后一个异常。
     */
    private fun openConnected(host: String, port: Int): Socket {
        val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull()
            ?: throw java.net.UnknownHostException(host)
        val ordered = addresses.sortedBy { if (it is Inet6Address) 1 else 0 }
        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
        var lastError: Exception? = null
        for (addr in ordered) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            // 单个地址最多等一半预算，保证后续地址（v4↔v6）还有尝试机会
            val perTimeout = remaining.coerceAtMost(ADDR_CONNECT_TIMEOUT_MS)
            val attempt = Socket()
            try {
                attempt.tcpNoDelay = true
                attempt.connect(InetSocketAddress(addr, port), perTimeout.toInt())
                return attempt
            } catch (e: Exception) {
                lastError = e
                runCatching { attempt.close() }
            }
        }
        throw lastError ?: java.io.IOException("cannot connect to $host:$port")
    }

    private fun copyCatching(from: Socket, to: Socket) {
        try {
            val buf = ByteArray(BUFFER_SIZE)
            val input = from.getInputStream()
            val out = to.getOutputStream()
            while (running.get()) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                out.flush()
            }
        } catch (_: Exception) {
            // 对端关闭
        } finally {
            // 半关闭，通知对端
            runCatching { to.shutdownOutput() }
            runCatching { from.close() }
            runCatching { to.close() }
        }
    }

    private fun handleUdpAssociate(
        req: Socks5Codec.Request,
        out: OutputStream,
        control: Socket,
    ) {
        // 1. 与上游建立 UDP ASSOCIATE
        var upControl: Socket? = null
        var clientRelay: DatagramSocket? = null
        var upRelay: DatagramSocket? = null
        try {
            upControl = openUpstream()
            // 面向 hev 的中继（hev 把报文发到这里）
            clientRelay = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
            // 面向上游中继。远端 SOCKS5 的 BND 不在回环上，不能绑 127.0.0.1，
            // 否则发往公网的 UDP 会被内核丢掉。本进程已被排除在 VPN 外。
            upRelay = if (isLoopback(upstream.host)) {
                DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
            } else {
                DatagramSocket()
            }
            val upOut = upControl.getOutputStream()
            val upIn = DataInputStream(upControl.getInputStream())
            upOut.write(
                Socks5Codec.buildRequest(
                    Socks5Codec.CMD_UDP_ASSOCIATE,
                    Socks5Codec.ATYP_IPV4.toInt(),
                    "0.0.0.0",
                    0,
                ),
            )
            upOut.flush()
            val bound = Socks5Codec.readReply(upIn) ?: run {
                out.write(Socks5Codec.failureReply(Socks5Codec.REP_GENERAL_FAILURE))
                return
            }
            val upstreamRelay = upstreamRelayAddress(bound.first, bound.second)

            // 2. 把本地中继地址回复给 hev
            out.write(Socks5Codec.successReply("127.0.0.1", clientRelay.localPort))
            out.flush()

            // hev 回程按 1500 字节整包接收（含 SOCKS 头）。本地回环能发出更大的包，
            // 但超限后 hev 会截断，QUIC 校验失败。把缓冲加大，避免突发时在中继处丢包。
            clientRelay.receiveBufferSize = UDP_SOCKET_BUFFER
            upRelay.receiveBufferSize = UDP_SOCKET_BUFFER
            synchronized(relays) {
                relays.add(clientRelay)
                relays.add(upRelay)
            }
            Log.i(
                TAG,
                "udp relay 127.0.0.1:${clientRelay.localPort} -> ${upstreamRelay.hostString}:${upstreamRelay.port}",
            )
            relayUdp(clientRelay, upRelay, upstreamRelay, upControl, control)
        } catch (e: Exception) {
            Log.w(TAG, "udp associate failed: ${e.message}")
            runCatching {
                out.write(Socks5Codec.failureReply(Socks5Codec.REP_GENERAL_FAILURE))
            }
        } finally {
            runCatching { upControl?.close() }
            runCatching { clientRelay?.close() }
            runCatching { upRelay?.close() }
            synchronized(relays) {
                clientRelay?.let { relays.remove(it) }
                upRelay?.let { relays.remove(it) }
            }
        }
    }

    /** 在 hev ↔ 上游 SOCKS UDP 中继之间转发报文（头部格式相同，原样透传）。 */
    private fun relayUdp(
        clientRelay: DatagramSocket,
        upRelay: DatagramSocket,
        upstreamRelay: InetSocketAddress,
        upControl: Socket,
        clientControl: Socket,
    ) {
        val peerHolder = java.util.concurrent.atomic.AtomicReference<InetSocketAddress>()
        val oversizedLogged = AtomicBoolean(false)

        // hev -> 上游
        pool.execute {
            val buf = ByteArray(UDP_BUFFER_SIZE)
            while (running.get() && !upControl.isClosed && !clientControl.isClosed) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    clientRelay.soTimeout = TIMEOUT_MS
                    clientRelay.receive(packet)
                    (packet.socketAddress as? InetSocketAddress)?.let { peerHolder.set(it) }
                    warnIfOversized(packet.length, "to-upstream", oversizedLogged)
                    val data = packet.data.copyOf(packet.length)
                    upRelay.send(DatagramPacket(data, data.size, upstreamRelay))
                } catch (_: java.net.SocketTimeoutException) {
                    // 超时后重新检查控制连接状态
                } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "udp to-upstream: ${e.message}")
                    break
                }
            }
        }

        // 上游 -> hev（发回最近一个客户端地址）
        val buf = ByteArray(UDP_BUFFER_SIZE)
        while (running.get() && !upControl.isClosed && !clientControl.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                upRelay.soTimeout = TIMEOUT_MS
                upRelay.receive(packet)
                val peer = peerHolder.get() ?: continue
                // 这条才是 hev 用 1500 字节缓冲接收的方向，超限会被截成坏包。
                warnIfOversized(packet.length, "to-hev", oversizedLogged)
                val data = packet.data.copyOf(packet.length)
                clientRelay.send(DatagramPacket(data, data.size, peer))
            } catch (_: java.net.SocketTimeoutException) {
                // 超时后重新检查控制连接状态
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "udp to-hev: ${e.message}")
                break
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

    private companion object {
        const val TAG = "RuleSocksServer"
        const val CONNECT_TIMEOUT_MS = 8000
        /** 单个地址的连接尝试上限，保证 v4/v6 回退都有预算。 */
        const val ADDR_CONNECT_TIMEOUT_MS = 4000L
        const val TIMEOUT_MS = 1000
        const val BUFFER_SIZE = 32 * 1024
        const val UDP_BUFFER_SIZE = 64 * 1024
        /** 与 hev-socks5-tunnel 的 UDP_BUF_SIZE 一致，回程整包（含 SOCKS 头）不能超过它。 */
        const val HEV_UDP_RECV_LIMIT = 1500
        const val UDP_SOCKET_BUFFER = 512 * 1024

        fun isLoopback(host: String): Boolean =
            host == "127.0.0.1" || host == "localhost" || host == "::1"
    }
}
