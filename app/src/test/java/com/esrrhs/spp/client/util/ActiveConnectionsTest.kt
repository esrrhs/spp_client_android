package com.esrrhs.spp.client.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveConnectionsTest {

    @Test
    fun parseSessions_validLines() {
        // 原生现行格式为 9 字段（末字段 domain，无域名时为空）
        val text = """
            6|10.0.2.15|51234|142.250.151.119|443|1024|2048|1790900000000|
            17|10.0.2.15|55555|8.8.8.8|53|64|128|1790900001000|
        """.trimIndent()

        val rows = ActiveConnections.parseSessions(text)
        assertEquals(2, rows.size)

        val tcp = rows[0]
        assertEquals(6, tcp.proto)
        assertEquals("10.0.2.15", tcp.srcIp)
        assertEquals(51234, tcp.srcPort)
        assertEquals("142.250.151.119", tcp.dstIp)
        assertEquals(443, tcp.dstPort)
        assertEquals(1024L, tcp.upload)
        assertEquals(2048L, tcp.download)
        assertEquals(1790900000000L, tcp.createdMs)
        assertNull(tcp.domain)

        val udp = rows[1]
        assertEquals(17, udp.proto)
        assertEquals(53, udp.dstPort)
        assertTrue(udp.key.contains("8.8.8.8:53"))
        assertNull(udp.domain)
    }

    @Test
    fun parseSessions_parsesMappedDomain() {
        val text = "6|10.0.0.5|40001|198.18.0.7|443|11|22|1790900002000|www.example.com"

        val row = ActiveConnections.parseSessions(text).single()
        assertEquals("198.18.0.7", row.dstIp)
        assertEquals("www.example.com", row.domain)
    }

    @Test
    fun parseSessions_acceptsLegacyEightFields() {
        val text = "17|::1|5353|ff02::fb|5353|10|20|1790900002000"

        val row = ActiveConnections.parseSessions(text).single()
        assertEquals("::1", row.srcIp)
        assertNull(row.domain)
    }

    @Test
    fun parseSessions_skipsMalformedAndBlank() {
        val text = """

            1|1.2.3.4|1|5.6.7.8|2|0|0|1|
            6|bad|x|5.6.7.8|2|0|0|1|
            6|1.2.3.4|99999|5.6.7.8|2|0|0|1|
            6|1.2.3.4|1|5.6.7.8|2|oops|0|1|
            6|1.2.3.4|1|5.6.7.8|2|0|0|1|extra|fields
            17|::1|5353|ff02::fb|5353|10|20|1790900002000|dns.google
        """.trimIndent()

        val rows = ActiveConnections.parseSessions(text)
        // 仅最后一条 UDP 合法（协议号非法/端口越界/字段非法/字段过多均丢弃）
        assertEquals(1, rows.size)
        assertEquals("::1", rows[0].srcIp)
        assertEquals("ff02::fb", rows[0].dstIp)
        assertEquals(10L, rows[0].upload)
        assertEquals("dns.google", rows[0].domain)
    }

    @Test
    fun parseSessions_empty() {
        assertTrue(ActiveConnections.parseSessions("").isEmpty())
        assertTrue(ActiveConnections.parseSessions("\n\n").isEmpty())
    }

    @Test
    fun isDirect_matchesRuleSocksServerLogic() {
        val rules = setOf("hupu.com", "cn")
        // TCP + 命中域名（含子域名/大小写/尾点）→ 直连
        assertTrue(ActiveConnections.isDirect(6, "www.hupu.com", rules))
        assertTrue(ActiveConnections.isDirect(6, "HUPU.COM.", rules))
        assertTrue(ActiveConnections.isDirect(6, "a.b.cn", rules))
        // UDP/QUIC 即使域名命中也一律走代理
        assertTrue(!ActiveConnections.isDirect(17, "www.hupu.com", rules))
        // 无域名（IP 字面量）→ 代理
        assertTrue(!ActiveConnections.isDirect(6, null, rules))
        // 未命中域名 → 代理
        assertTrue(!ActiveConnections.isDirect(6, "www.google.com", rules))
        // 规则集合为空（域名直连关闭）→ 全部代理
        assertTrue(!ActiveConnections.isDirect(6, "www.hupu.com", emptySet()))
    }
}
