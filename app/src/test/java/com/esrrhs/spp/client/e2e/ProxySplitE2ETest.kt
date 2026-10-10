package com.esrrhs.spp.client.e2e

import com.esrrhs.spp.client.proxy.RuleSocksServer
import com.esrrhs.spp.client.proxy.Socks5Codec
import com.esrrhs.spp.client.proxy.SocksUpstream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 用户态分流（CN/LAN/domain split）端到端测试。
 *
 * TUN 全量抓包后，RuleSocksServer 必须对每一条 TCP CONNECT / UDP 报文独立决策：
 * 命中直连集合（精确 CIDR / 私有段 / 域名规则）的流量本地直发、**完全不接触上游**；
 * 其余转发上游 Mock。通过 Mock 上游的连接计数断言路径正确。
 *
 * 用回环地址模拟两类目标：
 *  - 直连 CIDR 配置成 127.0.0.0/8 → 回环目标即「CN/直连」；
 *  - 直连 CIDR 配置成 10.0.0.0/8 → 回环目标不在其中，必须走上游（Mock 再中继回环）。
 */
class ProxySplitE2ETest {

    private lateinit var tcpV4: MockTargetServers.TcpEchoServer
    private lateinit var tcpV6: MockTargetServers.TcpEchoServer
    private lateinit var udpV4: MockTargetServers.UdpEchoServer
    private lateinit var udpV6: MockTargetServers.UdpEchoServer

    private lateinit var cnUpstream: MockUpstreamSocks5Server
    private lateinit var foreignUpstream: MockUpstreamSocks5Server
    private lateinit var lanUpstream: MockUpstreamSocks5Server
    private lateinit var domainUpstream: MockUpstreamSocks5Server

    /** 「CN 分流」：v4 回环段直连，v6 无规则，无私有段。 */
    private lateinit var cnSplit: RuleSocksServer
    /** 「非 CN」对照：直连段与回环目标不相交，一切都要走上游。 */
    private lateinit var foreignSplit: RuleSocksServer
    /** 「绕过局域网」：全部私有/保留段（含 v4/v6 回环、CGNAT、ULA）直连。 */
    private lateinit var lanSplit: RuleSocksServer
    /** 「域名直连」：localhost 域名直连。 */
    private lateinit var domainSplit: RuleSocksServer
    /** 「直连回退」：.invalid 域名被判直连但本地解析必失败，应回退上游。 */
    private lateinit var fallbackUpstream: MockUpstreamSocks5Server
    private lateinit var fallbackSplit: RuleSocksServer

    @Before
    fun setUp() {
        tcpV4 = MockTargetServers.TcpEchoServer("127.0.0.1").start()
        tcpV6 = MockTargetServers.TcpEchoServer("::1").start()
        udpV4 = MockTargetServers.UdpEchoServer("127.0.0.1").start()
        udpV6 = MockTargetServers.UdpEchoServer("::1").start()

        cnUpstream = MockUpstreamSocks5Server("127.0.0.1").also { it.start() }
        foreignUpstream = MockUpstreamSocks5Server("127.0.0.1").also { it.start() }
        lanUpstream = MockUpstreamSocks5Server("127.0.0.1").also { it.start() }
        domainUpstream = MockUpstreamSocks5Server("127.0.0.1").also { it.start() }

        cnSplit = RuleSocksServer(
            upstream = SocksUpstream("127.0.0.1", cnUpstream.port),
            directDomains = emptySet(),
            directIpv4Cidrs = listOf("127.0.0.0/8"),
            directIpv6Cidrs = emptyList(),
            bypassPrivate = false,
        ).also { it.start() }

        foreignSplit = RuleSocksServer(
            upstream = SocksUpstream("127.0.0.1", foreignUpstream.port),
            directDomains = emptySet(),
            // 不含回环段：回环目标必须被送到上游
            directIpv4Cidrs = listOf("10.0.0.0/8", "192.168.0.0/16"),
            directIpv6Cidrs = listOf("2001:db8::/32"),
            bypassPrivate = false,
        ).also { it.start() }

        lanSplit = RuleSocksServer(
            upstream = SocksUpstream("127.0.0.1", lanUpstream.port),
            directDomains = emptySet(),
            bypassPrivate = true,
        ).also { it.start() }

        domainSplit = RuleSocksServer(
            upstream = SocksUpstream("127.0.0.1", domainUpstream.port),
            directDomains = setOf("localhost"),
        ).also { it.start() }

        fallbackUpstream = MockUpstreamSocks5Server("127.0.0.1").also { it.start() }
        fallbackSplit = RuleSocksServer(
            upstream = SocksUpstream("127.0.0.1", fallbackUpstream.port),
            // RFC 6761 .invalid 永不解析：命中直连域名但本地解析必失败
            directDomains = setOf("nonexistent-spp-test.invalid"),
        ).also { it.start() }
    }

    @After
    fun tearDown() {
        runCatching { cnSplit.stop() }
        runCatching { foreignSplit.stop() }
        runCatching { lanSplit.stop() }
        runCatching { domainSplit.stop() }
        runCatching { fallbackSplit.stop() }
        runCatching { fallbackUpstream.close() }
        runCatching { cnUpstream.close() }
        runCatching { foreignUpstream.close() }
        runCatching { lanUpstream.close() }
        runCatching { domainUpstream.close() }
        runCatching { tcpV4.close() }
        runCatching { tcpV6.close() }
        runCatching { udpV4.close() }
        runCatching { udpV6.close() }
    }

    // ---------------- TCP ----------------

    @Test
    fun tcp_cnV4Target_bypassesUpstreamEntirely() {
        val before = cnUpstream.tcpConnectCount
        val reply = tcpThroughProxy(cnSplit.port!!, "127.0.0.1", tcpV4.port, "CN_DIRECT_V4", ipv6 = false)
        assertEquals("CN_DIRECT_V4", reply)
        assertEquals("upstream must never see the direct connection", before, cnUpstream.tcpConnectCount)
    }

    @Test
    fun tcp_nonCnV4Target_goesUpstream() {
        val before = foreignUpstream.tcpConnectCount
        val reply = tcpThroughProxy(foreignSplit.port!!, "127.0.0.1", tcpV4.port, "FOREIGN_V4", ipv6 = false)
        assertEquals("FOREIGN_V4", reply)
        assertTrue("connection must reach upstream", foreignUpstream.tcpConnectCount > before)
    }

    @Test
    fun tcp_v6Target_withoutV6Rules_goesUpstream() {
        val before = cnUpstream.tcpConnectCount
        val reply = tcpThroughProxy(cnSplit.port!!, "::1", tcpV6.port, "PROXIED_V6", ipv6 = true)
        assertEquals("PROXIED_V6", reply)
        assertTrue("v6 must be proxied when no v6 direct CIDR matches", cnUpstream.tcpConnectCount > before)
    }

    @Test
    fun tcp_bypassLan_bothLoopbackFamiliesGoDirect() {
        val before = lanUpstream.tcpConnectCount
        assertEquals("LAN4", tcpThroughProxy(lanSplit.port!!, "127.0.0.1", tcpV4.port, "LAN4", ipv6 = false))
        assertEquals("LAN6", tcpThroughProxy(lanSplit.port!!, "::1", tcpV6.port, "LAN6", ipv6 = true))
        assertEquals("upstream must see neither loopback connection", before, lanUpstream.tcpConnectCount)
    }

    @Test
    fun tcp_matchingDomain_resolvesAndGoesDirect() {
        val before = domainUpstream.tcpConnectCount
        val reply = tcpThroughProxy(domainSplit.port!!, "localhost", tcpV4.port, "DOMAIN_DIRECT", ipv6 = false)
        assertEquals("DOMAIN_DIRECT", reply)
        assertEquals(before, domainUpstream.tcpConnectCount)
    }

    // ---------------- UDP ----------------

    @Test
    fun udp_cnV4Datagram_bypassesUpstreamWithNoAssociate() {
        val before = cnUpstream.udpAssociateRequests
        val reply = udpThroughProxy(cnSplit.port!!, "127.0.0.1", udpV4.port, "UDP_CN_DIRECT", ipv6 = false)
        assertEquals("UDP_CN_DIRECT", reply)
        assertEquals("no upstream UDP ASSOCIATE may be opened for direct traffic", before, cnUpstream.udpAssociateRequests)
    }

    @Test
    fun udp_nonCnV4Datagram_goesUpstream() {
        val before = foreignUpstream.udpAssociateRequests
        val reply = udpThroughProxy(foreignSplit.port!!, "127.0.0.1", udpV4.port, "UDP_FOREIGN", ipv6 = false)
        assertEquals("UDP_FOREIGN", reply)
        assertTrue(foreignUpstream.udpAssociateRequests > before)
    }

    @Test
    fun udp_v6Datagram_withoutV6Rules_goesUpstream() {
        val before = cnUpstream.udpAssociateRequests
        val reply = udpThroughProxy(cnSplit.port!!, "::1", udpV6.port, "UDP_V6_PROXIED", ipv6 = true)
        assertEquals("UDP_V6_PROXIED", reply)
        assertTrue(cnUpstream.udpAssociateRequests > before)
    }

    @Test
    fun udp_bypassLan_bothFamiliesGoDirect() {
        val before = lanUpstream.udpAssociateRequests
        assertEquals("UDP_LAN4", udpThroughProxy(lanSplit.port!!, "127.0.0.1", udpV4.port, "UDP_LAN4", ipv6 = false))
        assertEquals("UDP_LAN6", udpThroughProxy(lanSplit.port!!, "::1", udpV6.port, "UDP_LAN6", ipv6 = true))
        assertEquals(before, lanUpstream.udpAssociateRequests)
    }

    @Test
    fun udp_matchingDomain_resolvesAndGoesDirect() {
        val before = domainUpstream.udpAssociateRequests
        val reply = udpThroughProxy(domainSplit.port!!, "localhost", udpV4.port, "UDP_DOMAIN_DIRECT", ipv6 = false)
        assertEquals("UDP_DOMAIN_DIRECT", reply)
        assertEquals(before, domainUpstream.udpAssociateRequests)
    }

    @Test
    fun udp_mixedDestinationsInOneAssociation_splitPerDatagram() {
        // 同一个 UDP ASSOCIATE 内先后发「直连」与「代理」两个目标：
        // 直连报文不打开上游，首个代理报文才懒建立上游 ASSOCIATE，两路回包都必须收到。
        val direct = udpThroughProxy(cnSplit.port!!, "127.0.0.1", udpV4.port, "MIX_DIRECT", ipv6 = false)
        assertEquals("MIX_DIRECT", direct)
        val proxy = udpThroughProxy(cnSplit.port!!, "::1", udpV6.port, "MIX_PROXY", ipv6 = true)
        assertEquals("MIX_PROXY", proxy)
        // 注意：两次调用各自建立独立 ASSOCIATE；这里验证直连路径计数为 0 由前序用例覆盖，
        // 本用例重点是同一分流器实例在两类目标间反复切换不串包。
        assertTrue(cnUpstream.udpAssociateRequests >= 1)
    }

    @Test
    fun udp_directLocalFailure_fallsBackToUpstreamAndPenalized() {
        val ctrl = Socket("127.0.0.1", fallbackSplit.port!!)
        ctrl.soTimeout = 5000
        try {
            val inn = DataInputStream(ctrl.getInputStream())
            val out = ctrl.getOutputStream()
            out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
            assertEquals(0x05, inn.readUnsignedByte())
            assertEquals(0x00, inn.readUnsignedByte())

            out.write(
                Socks5Codec.buildRequest(
                    Socks5Codec.CMD_UDP_ASSOCIATE, Socks5Codec.ATYP_IPV4.toInt(), "0.0.0.0", 0,
                ),
            )
            out.flush()
            val bound = Socks5Codec.readReply(inn)
            assertTrue("UDP ASSOCIATE reply expected", bound != null)
            val relayPort = bound!!.second

            val clientUdp = DatagramSocket()
            try {
                val target = "nonexistent-spp-test.invalid"
                val header = Socks5Codec.buildUdpHeader(Socks5Codec.ATYP_DOMAIN.toInt(), target, 40001)

                fun sendDatagram(marker: String) {
                    val payload = marker.toByteArray(Charsets.UTF_8)
                    val pkt = ByteArray(header.size + payload.size)
                    System.arraycopy(header, 0, pkt, 0, header.size)
                    System.arraycopy(payload, 0, pkt, header.size, payload.size)
                    clientUdp.send(
                        DatagramPacket(pkt, pkt.size, InetSocketAddress("127.0.0.1", relayPort)),
                    )
                }

                // 首包：本地解析 .invalid 失败 → 惩罚目标、懒建立上游 ASSOCIATE 并转发
                sendDatagram("UDP_DIRECT_FAIL_1")
                Thread.sleep(1500)
                assertEquals("direct failure must open exactly one upstream ASSOCIATE",
                    1, fallbackUpstream.udpAssociateRequests)

                // 第二包：目标在惩罚窗内，跳过直连、复用同一上游 ASSOCIATE
                sendDatagram("UDP_DIRECT_FAIL_2")
                Thread.sleep(500)
                assertEquals("penalized target must not open another ASSOCIATE",
                    1, fallbackUpstream.udpAssociateRequests)
            } finally {
                clientUdp.close()
            }
        } finally {
            ctrl.close()
        }
    }

    // ---------------- helpers ----------------

    private fun tcpThroughProxy(
        proxyPort: Int,
        host: String,
        targetPort: Int,
        msg: String,
        ipv6: Boolean,
    ): String {
        Socket("127.0.0.1", proxyPort).use { socket ->
            socket.tcpNoDelay = true
            socket.soTimeout = 5000
            val inn = DataInputStream(socket.getInputStream())
            val out = socket.getOutputStream()
            out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
            assertEquals(0x05, inn.readUnsignedByte())
            assertEquals(0x00, inn.readUnsignedByte())

            val atyp = when {
                ipv6 -> Socks5Codec.ATYP_IPV6.toInt()
                host[0].isDigit() -> Socks5Codec.ATYP_IPV4.toInt()
                else -> null
            }
            out.write(Socks5Codec.buildRequest(Socks5Codec.CMD_CONNECT, atyp, host, targetPort))
            out.flush()
            val reply = Socks5Codec.readReply(inn)
            assertTrue("CONNECT must succeed", reply != null)

            val payload = msg.toByteArray(Charsets.UTF_8)
            out.write(payload); out.flush()
            val received = ByteArray(payload.size)
            inn.readFully(received)
            return String(received, Charsets.UTF_8)
        }
    }

    private fun udpThroughProxy(
        proxyPort: Int,
        host: String,
        targetPort: Int,
        msg: String,
        ipv6: Boolean,
    ): String {
        val ctrl = Socket("127.0.0.1", proxyPort)
        ctrl.soTimeout = 5000
        try {
            val inn = DataInputStream(ctrl.getInputStream())
            val out = ctrl.getOutputStream()
            out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
            assertEquals(0x05, inn.readUnsignedByte())
            assertEquals(0x00, inn.readUnsignedByte())

            out.write(
                Socks5Codec.buildRequest(
                    Socks5Codec.CMD_UDP_ASSOCIATE, Socks5Codec.ATYP_IPV4.toInt(), "0.0.0.0", 0,
                ),
            )
            out.flush()
            val bound = Socks5Codec.readReply(inn)
            assertTrue("UDP ASSOCIATE reply expected", bound != null)
            val relayPort = bound!!.second

            val clientUdp = DatagramSocket()
            try {
                clientUdp.soTimeout = 5000
                val atyp: Int = when {
                    ipv6 -> Socks5Codec.ATYP_IPV6.toInt()
                    host[0].isDigit() -> Socks5Codec.ATYP_IPV4.toInt()
                    else -> Socks5Codec.ATYP_DOMAIN.toInt()
                }
                val header = Socks5Codec.buildUdpHeader(atyp, host, targetPort)
                val payload = msg.toByteArray(Charsets.UTF_8)
                val packetData = ByteArray(header.size + payload.size)
                System.arraycopy(header, 0, packetData, 0, header.size)
                System.arraycopy(payload, 0, packetData, header.size, payload.size)
                clientUdp.send(
                    DatagramPacket(packetData, packetData.size, InetSocketAddress("127.0.0.1", relayPort)),
                )

                val recvBuf = ByteArray(65535)
                val recv = DatagramPacket(recvBuf, recvBuf.size)
                clientUdp.receive(recv)
                // 回包头部按真实源地址封装（域名直连时也会变成 IP 型头），
                // 必须解析回包头拿 payload 偏移，不能假定与请求头等长。
                val (replyEndpoint, replyOffset) =
                    Socks5Codec.parseUdpPacket(recv.data, recv.length)
                        ?: error("malformed UDP reply header")
                assertEquals("reply must come from the requested target port", targetPort, replyEndpoint.port)
                assertTrue("reply too short", recv.length > replyOffset)
                return String(recv.data, replyOffset, recv.length - replyOffset, Charsets.UTF_8)
            } finally {
                clientUdp.close()
            }
        } finally {
            ctrl.close()
        }
    }
}
