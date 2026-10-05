package com.esrrhs.spp.client.e2e

import com.esrrhs.spp.client.proxy.RuleSocksServer
import com.esrrhs.spp.client.proxy.Socks5Codec
import com.esrrhs.spp.client.proxy.SocksUpstream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 完整端到端 (E2E) 测试套件：
 *
 * 测试矩阵：
 *  - 代理方式：
 *      1. SOCKS5 (直连上游 SOCKS5 代理服务端，支持带认证)
 *      2. SPP (通过本地 SPP client 转发回路代理模拟)
 *  - 网络与传输协议：
 *      - TCP (IPv4 & IPv6)
 *      - UDP (IPv4 & IPv6)
 *      - HTTP (IPv4 & IPv6)
 *  - 高并发与性能指标：
 *      - 批量大量并发请求，断言 100% 成功率 (0 失败)
 *      - 吞吐量和建连时延在毫秒级合理范围内
 */
class ProxyE2ETest {

    // 目标服务器 (Target Servers)
    private lateinit var tcpTargetV4: MockTargetServers.TcpEchoServer
    private lateinit var tcpTargetV6: MockTargetServers.TcpEchoServer
    private lateinit var udpTargetV4: MockTargetServers.UdpEchoServer
    private lateinit var udpTargetV6: MockTargetServers.UdpEchoServer
    private lateinit var httpTargetV4: MockTargetServers.HttpEchoServer
    private lateinit var httpTargetV6: MockTargetServers.HttpEchoServer
    private lateinit var dnsTarget: MockTargetServers.DnsEchoServer
    private lateinit var wsTarget: MockTargetServers.WebSocketEchoServer
    private lateinit var grpcTarget: MockTargetServers.GrpcMockServer
    private lateinit var quicTarget: MockTargetServers.QuicMockServer

    // 上游代理 (Upstream Servers)
    private lateinit var upstreamSocks5: MockUpstreamSocks5Server
    private lateinit var upstreamSppMock: MockUpstreamSocks5Server

    // 被测本地分流与代理服务 (System Under Test)
    private lateinit var ruleSocksServerSocks5Mode: RuleSocksServer
    private lateinit var ruleSocksServerSppMode: RuleSocksServer

    @Before
    fun setUp() {
        // 1. 启动目标 Echo 服务端
        tcpTargetV4 = MockTargetServers.TcpEchoServer("127.0.0.1").start()
        tcpTargetV6 = MockTargetServers.TcpEchoServer("::1").start()
        udpTargetV4 = MockTargetServers.UdpEchoServer("127.0.0.1").start()
        udpTargetV6 = MockTargetServers.UdpEchoServer("::1").start()
        httpTargetV4 = MockTargetServers.HttpEchoServer("127.0.0.1").start()
        httpTargetV6 = MockTargetServers.HttpEchoServer("::1").start()
        dnsTarget = MockTargetServers.DnsEchoServer("127.0.0.1").start()
        wsTarget = MockTargetServers.WebSocketEchoServer("127.0.0.1").start()
        grpcTarget = MockTargetServers.GrpcMockServer("127.0.0.1").start()
        quicTarget = MockTargetServers.QuicMockServer("127.0.0.1").start()

        // 2. 启动上游 Mock 服务
        // 模式 A：上游 SOCKS5 (配置用户名密码验证)
        upstreamSocks5 = MockUpstreamSocks5Server("127.0.0.1", "testuser", "testpass")
        upstreamSocks5.start()

        // 模式 B：SPP 模式 (模拟本机本地监听无鉴权的 SPP socks5_client 端口)
        upstreamSppMock = MockUpstreamSocks5Server("127.0.0.1")
        upstreamSppMock.start()

        // 3. 启动被测客户端代理 (RuleSocksServer)
        ruleSocksServerSocks5Mode = RuleSocksServer(
            upstream = SocksUpstream("127.0.0.1", upstreamSocks5.port, "testuser", "testpass"),
            directDomains = emptySet(),
        )
        ruleSocksServerSocks5Mode.start()

        ruleSocksServerSppMode = RuleSocksServer(
            upstream = SocksUpstream("127.0.0.1", upstreamSppMock.port),
            directDomains = emptySet(),
        )
        ruleSocksServerSppMode.start()
    }

    @After
    fun tearDown() {
        runCatching { ruleSocksServerSocks5Mode.stop() }
        runCatching { ruleSocksServerSppMode.stop() }
        runCatching { upstreamSocks5.close() }
        runCatching { upstreamSppMock.close() }
        runCatching { tcpTargetV4.close() }
        runCatching { tcpTargetV6.close() }
        runCatching { udpTargetV4.close() }
        runCatching { udpTargetV6.close() }
        runCatching { httpTargetV4.close() }
        runCatching { httpTargetV6.close() }
        runCatching { dnsTarget.close() }
        runCatching { wsTarget.close() }
        runCatching { grpcTarget.close() }
        runCatching { quicTarget.close() }
    }

    // ==========================================
    // 矩阵 1: SOCKS5 代理方式测试 (TCP, UDP, IPv6, HTTP, DNS, WS, gRPC, QUIC)
    // ==========================================

    @Test
    fun testSocks5Mode_TcpIpv4() {
        val proxyPort = ruleSocksServerSocks5Mode.port!!
        verifyTcpEcho(proxyPort, "127.0.0.1", tcpTargetV4.port, "Hello_Socks5_TCP_IPv4")
    }

    @Test
    fun testSocks5Mode_TcpIpv6() {
        val proxyPort = ruleSocksServerSocks5Mode.port!!
        verifyTcpEcho(proxyPort, "::1", tcpTargetV6.port, "Hello_Socks5_TCP_IPv6")
    }

    @Test
    fun testSocks5Mode_UdpIpv4() {
        val proxyPort = ruleSocksServerSocks5Mode.port!!
        verifyUdpEcho(proxyPort, "127.0.0.1", udpTargetV4.port, "Hello_Socks5_UDP_IPv4")
    }

    @Test
    fun testSocks5Mode_UdpIpv6() {
        val proxyPort = ruleSocksServerSocks5Mode.port!!
        verifyUdpEcho(proxyPort, "::1", udpTargetV6.port, "Hello_Socks5_UDP_IPv6")
    }

    @Test
    fun testSocks5Mode_HttpIpv4() {
        val proxyPort = ruleSocksServerSocks5Mode.port!!
        verifyHttpEcho(proxyPort, "127.0.0.1", httpTargetV4.port)
    }

    @Test
    fun testSocks5Mode_HttpIpv6() {
        val proxyPort = ruleSocksServerSocks5Mode.port!!
        verifyHttpEcho(proxyPort, "[::1]", httpTargetV6.port)
    }

    @Test
    fun testSocks5Mode_Dns() {
        val proxyPort = ruleSocksServerSocks5Mode.port!!
        verifyDnsQuery(proxyPort, "127.0.0.1", dnsTarget.port)
    }

    @Test
    fun testSocks5Mode_WebSocket() {
        val proxyPort = ruleSocksServerSocks5Mode.port!!
        verifyWebSocketEcho(proxyPort, "127.0.0.1", wsTarget.port)
    }

    @Test
    fun testSocks5Mode_Grpc() {
        val proxyPort = ruleSocksServerSocks5Mode.port!!
        verifyGrpc(proxyPort, "127.0.0.1", grpcTarget.port)
    }

    @Test
    fun testSocks5Mode_Quic() {
        val proxyPort = ruleSocksServerSocks5Mode.port!!
        verifyQuic(proxyPort, "127.0.0.1", quicTarget.port)
    }

    // ==========================================
    // 矩阵 2: SPP 代理方式测试 (TCP, UDP, IPv6, HTTP, DNS, WS, gRPC, QUIC)
    // ==========================================

    @Test
    fun testSppMode_TcpIpv4() {
        val proxyPort = ruleSocksServerSppMode.port!!
        verifyTcpEcho(proxyPort, "127.0.0.1", tcpTargetV4.port, "Hello_SPP_TCP_IPv4")
    }

    @Test
    fun testSppMode_TcpIpv6() {
        val proxyPort = ruleSocksServerSppMode.port!!
        verifyTcpEcho(proxyPort, "::1", tcpTargetV6.port, "Hello_SPP_TCP_IPv6")
    }

    @Test
    fun testSppMode_UdpIpv4() {
        val proxyPort = ruleSocksServerSppMode.port!!
        verifyUdpEcho(proxyPort, "127.0.0.1", udpTargetV4.port, "Hello_SPP_UDP_IPv4")
    }

    @Test
    fun testSppMode_UdpIpv6() {
        val proxyPort = ruleSocksServerSppMode.port!!
        verifyUdpEcho(proxyPort, "::1", udpTargetV6.port, "Hello_SPP_UDP_IPv6")
    }

    @Test
    fun testSppMode_HttpIpv4() {
        val proxyPort = ruleSocksServerSppMode.port!!
        verifyHttpEcho(proxyPort, "127.0.0.1", httpTargetV4.port)
    }

    @Test
    fun testSppMode_HttpIpv6() {
        val proxyPort = ruleSocksServerSppMode.port!!
        verifyHttpEcho(proxyPort, "[::1]", httpTargetV6.port)
    }

    @Test
    fun testSppMode_Dns() {
        val proxyPort = ruleSocksServerSppMode.port!!
        verifyDnsQuery(proxyPort, "127.0.0.1", dnsTarget.port)
    }

    @Test
    fun testSppMode_WebSocket() {
        val proxyPort = ruleSocksServerSppMode.port!!
        verifyWebSocketEcho(proxyPort, "127.0.0.1", wsTarget.port)
    }

    @Test
    fun testSppMode_Grpc() {
        val proxyPort = ruleSocksServerSppMode.port!!
        verifyGrpc(proxyPort, "127.0.0.1", grpcTarget.port)
    }

    @Test
    fun testSppMode_Quic() {
        val proxyPort = ruleSocksServerSppMode.port!!
        verifyQuic(proxyPort, "127.0.0.1", quicTarget.port)
    }

    // ==========================================
    // 非功能性测试：高并发压力与速度时延验证
    // ==========================================

    @Test
    fun testHighConcurrencyStress_Socks5Mode() {
        val proxyPort = ruleSocksServerSocks5Mode.port!!
        runConcurrencyLoadTest(proxyPort, "127.0.0.1", tcpTargetV4.port, concurrency = 50, totalRequests = 100)
    }

    @Test
    fun testHighConcurrencyStress_SppMode() {
        val proxyPort = ruleSocksServerSppMode.port!!
        runConcurrencyLoadTest(proxyPort, "127.0.0.1", tcpTargetV4.port, concurrency = 50, totalRequests = 100)
    }

    @Test
    fun testThroughputAndLatency() {
        val proxyPort = ruleSocksServerSocks5Mode.port!!
        // 发送 1MB 大数据流进行吞吐压测
        val dataSize = 1024 * 1024 // 1MB
        val payload = ByteArray(dataSize) { (it % 127).toByte() }

        val start = System.currentTimeMillis()
        val received = sendTcpThroughProxy(proxyPort, "127.0.0.1", tcpTargetV4.port, payload)
        val elapsed = System.currentTimeMillis() - start

        assertArrayEquals("1MB payload must match exactly", payload, received)
        println("1MB transfer via proxy took $elapsed ms (speed: ${1000.0 / elapsed.coerceAtLeast(1)} MB/s)")
        // 断言传输耗时在合理范围内 (本地回环应远小于 3000ms)
        assertTrue("Transfer time should be under 3000ms (was $elapsed ms)", elapsed < 3000)
    }

    // ==========================================
    // 辅助验证逻辑
    // ==========================================

    private fun verifyTcpEcho(proxyPort: Int, targetHost: String, targetPort: Int, msg: String) {
        val payload = msg.toByteArray(Charsets.UTF_8)
        val received = sendTcpThroughProxy(proxyPort, targetHost, targetPort, payload)
        assertEquals(msg, String(received, Charsets.UTF_8))
    }

    private fun sendTcpThroughProxy(proxyPort: Int, targetHost: String, targetPort: Int, payload: ByteArray): ByteArray {
        val socket = Socket("127.0.0.1", proxyPort)
        socket.tcpNoDelay = true
        socket.soTimeout = 5000
        val `in` = DataInputStream(socket.getInputStream())
        val out = socket.getOutputStream()

        // 1. SOCKS5 握手 (No-Auth)
        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        assertEquals(0x05, `in`.readUnsignedByte())
        assertEquals(0x00, `in`.readUnsignedByte())

        // 2. SOCKS5 CONNECT
        val isIpv6 = targetHost.contains(":")
        val atyp = if (isIpv6) Socks5Codec.ATYP_IPV6 else Socks5Codec.ATYP_IPV4
        val rawReq = Socks5Codec.buildRequest(Socks5Codec.CMD_CONNECT, atyp.toInt(), targetHost, targetPort)
        out.write(rawReq)
        out.flush()

        val rep = Socks5Codec.readReply(`in`)
        assertTrue("CONNECT through proxy must succeed", rep != null)

        // 3. 发送数据
        out.write(payload)
        out.flush()

        // 4. 读取回显
        val received = ByteArray(payload.size)
        `in`.readFully(received)
        socket.close()
        return received
    }

    private fun verifyUdpEcho(proxyPort: Int, targetHost: String, targetPort: Int, msg: String) {
        // 1. 先通过 TCP 建立 UDP ASSOCIATE 控制连接
        val ctrlSocket = Socket("127.0.0.1", proxyPort)
        ctrlSocket.soTimeout = 5000
        val `in` = DataInputStream(ctrlSocket.getInputStream())
        val out = ctrlSocket.getOutputStream()

        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        assertEquals(0x05, `in`.readUnsignedByte())
        assertEquals(0x00, `in`.readUnsignedByte())

        // UDP ASSOCIATE
        val req = Socks5Codec.buildRequest(Socks5Codec.CMD_UDP_ASSOCIATE, Socks5Codec.ATYP_IPV4.toInt(), "0.0.0.0", 0)
        out.write(req)
        out.flush()

        val bound = Socks5Codec.readReply(`in`)
        assertTrue("UDP ASSOCIATE must succeed", bound != null)
        val relayPort = bound!!.second

        // 2. 通过 UDP 发送 SOCKS 封装的报文到 relayPort
        val clientUdp = DatagramSocket()
        clientUdp.soTimeout = 5000

        val isIpv6 = targetHost.contains(":")
        val header = Socks5UdpHeader.encode(targetHost, targetPort, isIpv6)
        val payload = msg.toByteArray(Charsets.UTF_8)
        val packetData = ByteArray(header.size + payload.size)
        System.arraycopy(header, 0, packetData, 0, header.size)
        System.arraycopy(payload, 0, packetData, header.size, payload.size)

        val packet = DatagramPacket(packetData, packetData.size, InetSocketAddress("127.0.0.1", relayPort))
        clientUdp.send(packet)

        // 3. 接收 Relay 回发的 UDP 报文
        val recvBuf = ByteArray(65535)
        val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
        clientUdp.receive(recvPacket)

        // 4. 解析回包中的 payload
        val rData = recvPacket.data
        val rLen = recvPacket.length
        assertTrue("Received UDP datagram should be larger than socks header", rLen > header.size)
        val receivedText = String(rData, header.size, rLen - header.size, Charsets.UTF_8)

        assertEquals(msg, receivedText)

        clientUdp.close()
        ctrlSocket.close()
    }

    private fun verifyHttpEcho(proxyPort: Int, targetHost: String, targetPort: Int) {
        val proxySelector = object : java.net.ProxySelector() {
            override fun select(uri: URI?): List<java.net.Proxy> =
                listOf(java.net.Proxy(java.net.Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", proxyPort)))

            override fun connectFailed(uri: URI?, sa: java.net.SocketAddress?, ioe: java.io.IOException?) {}
        }
        val client = HttpClient.newBuilder()
            .proxy(proxySelector)
            .connectTimeout(Duration.ofSeconds(3))
            .build()

        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://$targetHost:$targetPort/echo"))
            .POST(HttpRequest.BodyPublishers.ofString("E2E_HTTP_REQUEST_PAYLOAD"))
            .timeout(Duration.ofSeconds(3))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode())
        assertEquals("E2E_HTTP_REQUEST_PAYLOAD", response.body())
    }

    private fun runConcurrencyLoadTest(
        proxyPort: Int,
        targetHost: String,
        targetPort: Int,
        concurrency: Int,
        totalRequests: Int,
    ) {
        val executor = Executors.newFixedThreadPool(concurrency)
        val latch = CountDownLatch(totalRequests)
        val successCount = AtomicInteger(0)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val start = System.currentTimeMillis()

        for (i in 0 until totalRequests) {
            executor.submit {
                try {
                    val msg = "STRESS_MSG_$i"
                    val received = sendTcpThroughProxy(proxyPort, targetHost, targetPort, msg.toByteArray())
                    if (String(received) == msg) {
                        successCount.incrementAndGet()
                    } else {
                        errors.add(IllegalStateException("Payload mismatch for $i"))
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                } finally {
                    latch.countDown()
                }
            }
        }

        val completedInTime = latch.await(10, TimeUnit.SECONDS)
        val totalDuration = System.currentTimeMillis() - start
        executor.shutdown()

        assertTrue("Stress test should complete within 10 seconds", completedInTime)
        println("Completed $totalRequests concurrent requests in ${totalDuration}ms. Success: ${successCount.get()}/$totalRequests")

        if (errors.isNotEmpty()) {
            errors.first().printStackTrace()
        }
        assertEquals("100% of concurrent connections must succeed with 0 failures", totalRequests, successCount.get())
        assertTrue("Average latency per request should be healthy (< 200ms avg load)", (totalDuration.toDouble() / totalRequests) < 200)
    }

    private fun verifyDnsQuery(proxyPort: Int, targetHost: String, targetPort: Int) {
        // 构造标准 DNS 查询包：TxID=0x1234, Flags=0x0100 (Standard query), QDCOUNT=1, ANCOUNT=0
        val query = byteArrayOf(
            0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x07, 0x65, 0x78, 0x61, 0x6d, 0x70, 0x6c, 0x65, 0x03, 0x63, 0x6f, 0x6d, 0x00, // example.com
            0x00, 0x01, 0x00, 0x01 // Type A, Class IN
        )
        // 经 SOCKS5 UDP relay 发送 DNS 查询
        val ctrlSocket = Socket("127.0.0.1", proxyPort).apply { tcpNoDelay = true; soTimeout = 5000 }
        val `in` = DataInputStream(ctrlSocket.getInputStream())
        val out = ctrlSocket.getOutputStream()
        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        assertEquals(0x05, `in`.readUnsignedByte())
        assertEquals(0x00, `in`.readUnsignedByte())

        val req = byteArrayOf(0x05, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        out.write(req)
        out.flush()
        assertEquals(0x05, `in`.readUnsignedByte())
        assertEquals(0x00, `in`.readUnsignedByte())
        assertEquals(0x00, `in`.readUnsignedByte())
        val atyp = `in`.readUnsignedByte()
        val bndIp = ByteArray(if (atyp == 1) 4 else 16)
        `in`.readFully(bndIp)
        val bndPort = `in`.readUnsignedShort()
        val relayAddr = InetSocketAddress(InetAddress.getByAddress(bndIp), bndPort)

        val clientUdp = DatagramSocket()
        clientUdp.soTimeout = 5000
        val isIpv6 = targetHost.contains(":")
        val targetAtyp = if (isIpv6) Socks5Codec.ATYP_IPV6 else Socks5Codec.ATYP_IPV4
        val targetIpBytes = InetAddress.getByName(targetHost).address
        val header = ByteArray(4 + targetIpBytes.size)
        header[0] = 0x00; header[1] = 0x00; header[2] = 0x00; header[3] = targetAtyp
        System.arraycopy(targetIpBytes, 0, header, 4, targetIpBytes.size)
        val portBytes = byteArrayOf((targetPort shr 8).toByte(), (targetPort and 0xFF).toByte())
        val fullPacketData = header + portBytes + query

        clientUdp.send(DatagramPacket(fullPacketData, fullPacketData.size, relayAddr))

        val recvBuf = ByteArray(4096)
        val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
        clientUdp.receive(recvPacket)

        val rLen = recvPacket.length
        val offset = header.size + portBytes.size
        assertTrue("DNS response datagram should have data", rLen > offset)
        // 校验 TxID 与 Flags
        assertEquals(0x12.toByte(), recvPacket.data[offset])
        assertEquals(0x34.toByte(), recvPacket.data[offset + 1])
        assertEquals(0x81.toByte(), recvPacket.data[offset + 2]) // Response

        clientUdp.close()
        ctrlSocket.close()
    }

    private fun verifyWebSocketEcho(proxyPort: Int, targetHost: String, targetPort: Int) {
        val socket = Socket("127.0.0.1", proxyPort).apply { tcpNoDelay = true; soTimeout = 5000 }
        val `in` = DataInputStream(socket.getInputStream())
        val out = socket.getOutputStream()
        // SOCKS5 握手
        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        assertEquals(0x05, `in`.readUnsignedByte())
        assertEquals(0x00, `in`.readUnsignedByte())
        // SOCKS5 CONNECT
        val rawReq = Socks5Codec.buildRequest(Socks5Codec.CMD_CONNECT, Socks5Codec.ATYP_IPV4.toInt(), targetHost, targetPort)
        out.write(rawReq)
        out.flush()
        assertEquals(0x05, `in`.readUnsignedByte())
        assertEquals(0x00, `in`.readUnsignedByte())
        assertEquals(0x00, `in`.readUnsignedByte())
        val atyp = `in`.readUnsignedByte()
        val bnd = ByteArray(if (atyp == 1) 4 else 16)
        `in`.readFully(bnd)
        `in`.readUnsignedShort()

        // 1. 发送 HTTP Upgrade: websocket
        val handshakeReq = "GET /ws HTTP/1.1\r\n" +
                "Host: $targetHost:$targetPort\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n"
        out.write(handshakeReq.toByteArray(Charsets.UTF_8))
        out.flush()

        // 读取 101 Switching Protocols
        val lineBuf = ByteArray(1024)
        val readHandshake = `in`.read(lineBuf)
        val handshakeResp = String(lineBuf, 0, readHandshake, Charsets.UTF_8)
        assertTrue("Must receive 101 Switching Protocols", handshakeResp.contains("101 Switching Protocols"))

        // 2. 发送 WebSocket 文本帧 (0x81 | Masked)
        val payload = "E2E_WS_HELLO".toByteArray(Charsets.UTF_8)
        val mask = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        val maskedPayload = ByteArray(payload.size)
        for (i in payload.indices) {
            maskedPayload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        val frame = byteArrayOf(0x81.toByte(), (0x80 or payload.size).toByte()) + mask + maskedPayload
        out.write(frame)
        out.flush()

        // 3. 读取服务端回显的 WebSocket 文本帧 (0x81, unmasked)
        val op = `in`.readUnsignedByte()
        assertEquals(0x81, op)
        val len = `in`.readUnsignedByte() and 0x7F
        val echoPayload = ByteArray(len)
        `in`.readFully(echoPayload)
        assertEquals("E2E_WS_HELLO", String(echoPayload, Charsets.UTF_8))

        socket.close()
    }

    private fun verifyGrpc(proxyPort: Int, targetHost: String, targetPort: Int) {
        val socket = Socket("127.0.0.1", proxyPort).apply { tcpNoDelay = true; soTimeout = 5000 }
        val `in` = DataInputStream(socket.getInputStream())
        val out = socket.getOutputStream()
        // SOCKS5 握手
        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        assertEquals(0x05, `in`.readUnsignedByte())
        assertEquals(0x00, `in`.readUnsignedByte())
        val rawReq = Socks5Codec.buildRequest(Socks5Codec.CMD_CONNECT, Socks5Codec.ATYP_IPV4.toInt(), targetHost, targetPort)
        out.write(rawReq)
        out.flush()
        assertEquals(0x05, `in`.readUnsignedByte())
        assertEquals(0x00, `in`.readUnsignedByte())
        assertEquals(0x00, `in`.readUnsignedByte())
        val atyp = `in`.readUnsignedByte()
        val bnd = ByteArray(if (atyp == 1) 4 else 16)
        `in`.readFully(bnd)
        `in`.readUnsignedShort()

        // 1. 发送 HTTP/2 Connection Preface + SETTINGS frame (Type 0x04)
        val preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".toByteArray(Charsets.UTF_8)
        val settings = byteArrayOf(0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00)
        out.write(preface + settings)
        out.flush()

        // 2. 读取服务端 SETTINGS 帧
        val serverSettings = ByteArray(9)
        `in`.readFully(serverSettings)
        assertEquals(0x04.toByte(), serverSettings[3]) // Frame Type SETTINGS

        // 3. 发送 gRPC 请求头 (模拟 HEADERS 帧: Type 0x01, Flags 0x04 END_HEADERS, Stream 1)
        val grpcHeader = byteArrayOf(0x00, 0x00, 0x04, 0x01, 0x04, 0x00, 0x00, 0x00, 0x01, 0x47, 0x52, 0x50, 0x43)
        out.write(grpcHeader)
        out.flush()

        // 4. 读取服务端 gRPC 响应
        val grpcResp = ByteArray(13)
        `in`.readFully(grpcResp)
        assertEquals(0x01.toByte(), grpcResp[3]) // HEADERS frame
        assertEquals(0x01.toByte(), grpcResp[8]) // Stream ID 1

        socket.close()
    }

    private fun verifyQuic(proxyPort: Int, targetHost: String, targetPort: Int) {
        // 构造标准 QUIC Initial Packet Header: 0xC0 (Long Header), Version 0x00000001
        val quicPacket = byteArrayOf(
            0xC0.toByte(), 0x00, 0x00, 0x00, 0x01, 0x00, 0x08, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte()
        ) + "QUIC_CLIENT_INITIAL".toByteArray(Charsets.UTF_8)

        // 经 SOCKS5 UDP relay 穿透
        val ctrlSocket = Socket("127.0.0.1", proxyPort).apply { tcpNoDelay = true; soTimeout = 5000 }
        val `in` = DataInputStream(ctrlSocket.getInputStream())
        val out = ctrlSocket.getOutputStream()
        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        assertEquals(0x05, `in`.readUnsignedByte())
        assertEquals(0x00, `in`.readUnsignedByte())

        val req = byteArrayOf(0x05, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        out.write(req)
        out.flush()
        assertEquals(0x05, `in`.readUnsignedByte())
        assertEquals(0x00, `in`.readUnsignedByte())
        assertEquals(0x00, `in`.readUnsignedByte())
        val atyp = `in`.readUnsignedByte()
        val bndIp = ByteArray(if (atyp == 1) 4 else 16)
        `in`.readFully(bndIp)
        val bndPort = `in`.readUnsignedShort()
        val relayAddr = InetSocketAddress(InetAddress.getByAddress(bndIp), bndPort)

        val clientUdp = DatagramSocket()
        clientUdp.soTimeout = 5000
        val targetAtyp = Socks5Codec.ATYP_IPV4
        val targetIpBytes = InetAddress.getByName(targetHost).address
        val header = ByteArray(4 + targetIpBytes.size)
        header[0] = 0x00; header[1] = 0x00; header[2] = 0x00; header[3] = targetAtyp
        System.arraycopy(targetIpBytes, 0, header, 4, targetIpBytes.size)
        val portBytes = byteArrayOf((targetPort shr 8).toByte(), (targetPort and 0xFF).toByte())
        val fullPacketData = header + portBytes + quicPacket

        clientUdp.send(DatagramPacket(fullPacketData, fullPacketData.size, relayAddr))

        val recvBuf = ByteArray(4096)
        val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
        clientUdp.receive(recvPacket)

        val offset = header.size + portBytes.size
        val rLen = recvPacket.length
        assertTrue("QUIC response datagram must have data", rLen > offset + 5)
        // 校验返回的 QUIC Long Header 与 Version 1
        assertEquals(0xC0.toByte(), recvPacket.data[offset])
        assertEquals(0x01.toByte(), recvPacket.data[offset + 4])
        val ackStr = String(recvPacket.data, offset + 5, rLen - offset - 5, Charsets.UTF_8)
        assertEquals("QUIC_SERVER_HANDSHAKE_ACK", ackStr)

        clientUdp.close()
        ctrlSocket.close()
    }
}
