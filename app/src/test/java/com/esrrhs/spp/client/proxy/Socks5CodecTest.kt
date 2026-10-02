package com.esrrhs.spp.client.proxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Socks5CodecTest {

    @Test
    fun greeting_isNoAuthOnly() {
        assertArrayEquals(byteArrayOf(0x05, 0x01, 0x00), Socks5Codec.greeting())
    }

    @Test
    fun domainRequest_roundTripsThroughBuilder() {
        val raw = Socks5Codec.buildRequest(
            Socks5Codec.CMD_CONNECT,
            Socks5Codec.ATYP_DOMAIN.toInt(),
            "example.com",
            443,
        )
        // VER CMD RSV ATYP LEN DOMAIN PORT
        assertEquals(0x05.toByte(), raw[0])
        assertEquals(0x01.toByte(), raw[1])
        assertEquals(0x00.toByte(), raw[2])
        assertEquals(0x03.toByte(), raw[3])
        assertEquals(11, raw[4].toInt())
        assertEquals(
            "example.com",
            raw.copyOfRange(5, 16).toString(Charsets.US_ASCII),
        )
        assertEquals(0x01.toByte(), raw[raw.size - 2])
        assertEquals(0xBB.toByte(), raw[raw.size - 1])
    }

    @Test
    fun ipv6Text_roundTripsToBytes() {
        val bytes = Socks5Codec.ipv6Bytes("2001:db8::1")
        assertEquals(16, bytes.size)
        assertEquals(0x20.toByte(), bytes[0])
        assertEquals(0x01.toByte(), bytes[1])
        assertEquals(0x0d.toByte(), bytes[2])
        assertEquals(0xb8.toByte(), bytes[3])
        assertEquals(0x00.toByte(), bytes[14])
        assertEquals(0x01.toByte(), bytes[15])
    }

    @Test
    fun ipv6FullAddress_encodesAllGroups() {
        val bytes = Socks5Codec.ipv6Bytes("2001:0db8:0000:0000:0000:0000:0000:0001")
        assertEquals(0x20.toByte(), bytes[0])
        assertEquals(0x01.toByte(), bytes[1])
        assertEquals(0x01.toByte(), bytes[15])
        // 中间全 0
        for (i in 4..13) assertEquals(0.toByte(), bytes[i])
    }

    @Test
    fun ipv4Request_encodesAddressBytes() {
        val raw = Socks5Codec.buildRequest(
            Socks5Codec.CMD_UDP_ASSOCIATE,
            Socks5Codec.ATYP_IPV4.toInt(),
            "0.0.0.0",
            0,
        )
        // VER CMD RSV ATYP 4B ADDR 2B PORT = 10
        assertEquals(10, raw.size)
        assertEquals(0x01.toByte(), raw[3])
        assertEquals(0x03.toByte(), raw[1])
    }
}
