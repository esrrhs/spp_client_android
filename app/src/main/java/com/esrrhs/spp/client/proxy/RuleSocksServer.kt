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

/**
 * 本地 SOCKS5 分流代理，位于 hev-socks5-tunnel 与 SPP socks5_client 之间。
 *
 * - CONNECT 带域名且命中 [directDomains]：本进程直连（App 自身被排除在 VPN 外，
 *   socket 天然走物理网络），域名由本地解析；
 * - 其它 CONNECT（含所有 IP 字面量）：原样转发给上游 socks5_client，走 SPP 隧道；
 * - UDP ASSOCIATE：在本地与上游各建一个 UDP 中继，数据报文（含 DNS）统一转发上游。
 *
 * 纯阻塞 IO + 守护线程，生命周期与 VPN 数据面一致。
 */
class RuleSocksServer(
    private val upstreamPort: Int,
    private val directDomains: Set<String>,
) {
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private val children = mutableListOf<Socket>()
    private val relays = mutableListOf<DatagramSocket>()
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
        val direct = req.atyp == Socks5Codec.ATYP_DOMAIN.toInt() &&
            com.esrrhs.spp.client.util.DomainRuleMatcher.matches(req.host, directDomains)
        val started = System.currentTimeMillis()
        val target = try {
            if (direct) {
                // 直连分支：本地解析 + 物理网络出站。
                // 显式枚举地址并 IPv4 优先：国内双栈环境下大量 AAAA 地址
                // 虽能解析但 v6 路由不可达，Java Socket 不会自动回退 v4。
                openConnected(req.host, req.port)
            } else {
                Socket().apply {
                    tcpNoDelay = true
                    connect(
                        InetSocketAddress("127.0.0.1", upstreamPort),
                        CONNECT_TIMEOUT_MS,
                    )
                    val upOut = getOutputStream()
                    val upIn = DataInputStream(getInputStream())
                    upOut.write(Socks5Codec.greeting())
                    upOut.flush()
                    if (upIn.readUnsignedByte() != 0x05 || upIn.readUnsignedByte() != 0x00) {
                        recordEvent(req, direct, false, "upstream method rejected", started)
                        out.write(Socks5Codec.failureReply(Socks5Codec.REP_GENERAL_FAILURE))
                        return
                    }
                    upOut.write(req.raw)
                    upOut.flush()
                    if (Socks5Codec.readReply(upIn) == null) {
                        recordEvent(req, direct, false, "upstream CONNECT rejected/timeout", started)
                        out.write(Socks5Codec.failureReply(Socks5Codec.REP_GENERAL_FAILURE))
                        return
                    }
                }
            }
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "connect ${req.host}:${req.port} direct=$direct failed: $reason")
            recordEvent(req, direct, false, reason, started)
            runCatching {
                out.write(Socks5Codec.failureReply(Socks5Codec.REP_GENERAL_FAILURE))
                out.flush()
            }
            return
        }
        // pipe 内部两个方向结束时会各自关闭两端 socket；
        // 这里不能在 finally 里提前 close，否则转发线程刚启动就被掐断。
        recordEvent(req, direct, true, null, started)
        out.write(Socks5Codec.successReply("0.0.0.0", 0))
        out.flush()
        awaitPipe(client, target)
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
        val upControl = Socket()
        // 面向 hev 的中继（hev 把报文发到这里）
        val clientRelay = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        // 面向上游中继的 socket（上游回复也回到这里）
        val upRelay = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        try {
            upControl.tcpNoDelay = true
            upControl.connect(InetSocketAddress("127.0.0.1", upstreamPort), CONNECT_TIMEOUT_MS)
            val upOut = upControl.getOutputStream()
            val upIn = DataInputStream(upControl.getInputStream())
            upOut.write(Socks5Codec.greeting())
            upOut.flush()
            if (upIn.readUnsignedByte() != 0x05 || upIn.readUnsignedByte() != 0x00) {
                out.write(Socks5Codec.failureReply(Socks5Codec.REP_GENERAL_FAILURE))
                return
            }
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
            val upstreamRelay = InetSocketAddress("127.0.0.1", bound.second)

            // 2. 把本地中继地址回复给 hev
            out.write(Socks5Codec.successReply("127.0.0.1", clientRelay.localPort))
            out.flush()

            synchronized(relays) {
                relays.add(clientRelay)
                relays.add(upRelay)
            }
            relayUdp(clientRelay, upRelay, upstreamRelay, upControl, control)
        } catch (e: Exception) {
            Log.w(TAG, "udp associate failed: ${e.message}")
            runCatching {
                out.write(Socks5Codec.failureReply(Socks5Codec.REP_GENERAL_FAILURE))
            }
        } finally {
            runCatching { upControl.close() }
            runCatching { clientRelay.close() }
            runCatching { upRelay.close() }
            synchronized(relays) {
                relays.remove(clientRelay)
                relays.remove(upRelay)
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

        // hev -> 上游
        pool.execute {
            val buf = ByteArray(UDP_BUFFER_SIZE)
            while (running.get() && !upControl.isClosed && !clientControl.isClosed) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    clientRelay.soTimeout = TIMEOUT_MS
                    clientRelay.receive(packet)
                    (packet.socketAddress as? InetSocketAddress)?.let { peerHolder.set(it) }
                    val data = packet.data.copyOf(packet.length)
                    upRelay.send(DatagramPacket(data, data.size, upstreamRelay))
                } catch (_: Exception) {
                    // 超时后重新检查控制连接状态
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
                val data = packet.data.copyOf(packet.length)
                clientRelay.send(DatagramPacket(data, data.size, peer))
            } catch (_: Exception) {
                // 超时后重新检查控制连接状态
            }
        }
    }

    private companion object {
        const val TAG = "RuleSocksServer"
        const val CONNECT_TIMEOUT_MS = 8000
        /** 单个地址的连接尝试上限，保证 v4/v6 回退都有预算。 */
        const val ADDR_CONNECT_TIMEOUT_MS = 4000L
        const val TIMEOUT_MS = 1000
        const val BUFFER_SIZE = 32 * 1024
        const val UDP_BUFFER_SIZE = 64 * 1024
    }
}
