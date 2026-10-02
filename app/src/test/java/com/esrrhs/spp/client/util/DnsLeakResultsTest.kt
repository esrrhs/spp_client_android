package com.esrrhs.spp.client.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DnsLeakResultsTest {

    @Test
    fun parsesDnsResolvers() {
        val body = """
            [
              {"type":"ip","ip":"5.6.7.8","country_name":"US"},
              {"type":"dns","ip":"8.8.8.8","country_name":"US","asn_name":"Google"},
              {"type":"dns","ip":"1.1.1.1","country_name":"AU","asn_name":"Cloudflare"},
              {"type":"conclusion","value":"done"}
            ]
        """.trimIndent()
        val resolvers = DnsLeakResults.parse(body)
        assertEquals(2, resolvers.size)
        assertEquals("8.8.8.8", resolvers[0].ip)
        assertEquals("Google", resolvers[0].asnName)
        assertEquals("1.1.1.1", resolvers[1].ip)
        assertEquals("AU", resolvers[1].country)
    }

    @Test
    fun emptyOrInvalidJson_returnsEmpty() {
        assertEquals(emptyList<DnsResolver>(), DnsLeakResults.parse(""))
        assertEquals(emptyList<DnsResolver>(), DnsLeakResults.parse("not json"))
        assertEquals(emptyList<DnsResolver>(), DnsLeakResults.parse("[]"))
    }

    @Test
    fun dnsEntryWithoutIp_isSkipped() {
        val body = """[{"type":"dns","country_name":"US"}]"""
        assertEquals(emptyList<DnsResolver>(), DnsLeakResults.parse(body))
    }
}
