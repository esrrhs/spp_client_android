package com.esrrhs.spp.client.proxy

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetSocketAddress

class RuleSocksServerTest {

    @Test
    fun upstreamRelayAddress_loopbackUses127001() {
        val server = RuleSocksServer(SocksUpstream("127.0.0.1", 1080), emptySet())
        val addr = server.upstreamRelayAddress("0.0.0.0", 50000)
        assertEquals(InetSocketAddress("127.0.0.1", 50000), addr)
    }

    @Test
    fun upstreamRelayAddress_remoteWithPrivateIp_fallsBackToUpstreamHost() {
        val server = RuleSocksServer(SocksUpstream("sh.esrrhs.xyz", 1080), emptySet())
        // 10.0.0.2 is a private IP returned by server behind NAT
        val addr = server.upstreamRelayAddress("10.0.0.2", 51880)
        assertEquals(InetSocketAddress("sh.esrrhs.xyz", 51880), addr)
    }

    @Test
    fun upstreamRelayAddress_remoteWithZeroOrBlank_fallsBackToUpstreamHost() {
        val server = RuleSocksServer(SocksUpstream("sh.esrrhs.xyz", 1080), emptySet())
        val addrZero = server.upstreamRelayAddress("0.0.0.0", 51880)
        assertEquals(InetSocketAddress("sh.esrrhs.xyz", 51880), addrZero)

        val addrBlank = server.upstreamRelayAddress("", 51880)
        assertEquals(InetSocketAddress("sh.esrrhs.xyz", 51880), addrBlank)
    }

    @Test
    fun upstreamRelayAddress_remoteWithPublicIp_usesBoundHost() {
        val server = RuleSocksServer(SocksUpstream("sh.esrrhs.xyz", 1080), emptySet())
        val addr = server.upstreamRelayAddress("1.1.1.1", 51880)
        assertEquals(InetSocketAddress("1.1.1.1", 51880), addr)
    }
}
