package com.esrrhs.spp.client.spp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SppConfigTest {

    private fun validConfig() = SppConfig(
        serverHost = "example.com",
        serverPort = 8888,
        proto = "tcp",
        key = "secret-key",
    )

    @Test
    fun defaultConfig_isInvalidBecauseHostMissing() {
        assertEquals(ValidationError.HOST_REQUIRED, SppConfig().validate())
    }

    @Test
    fun hostFilledButKeyBlank_reportsKeyError() {
        val config = validConfig().copy(key = "")
        assertEquals(ValidationError.KEY_REQUIRED, config.validate())
    }

    @Test
    fun blankHost_reportsHostError() {
        val config = validConfig().copy(serverHost = "   ")
        assertEquals(ValidationError.HOST_REQUIRED, config.validate())
    }

    @Test
    fun portOutOfRange_isRejected() {
        assertEquals(ValidationError.PORT_RANGE, validConfig().copy(serverPort = 0).validate())
        assertEquals(ValidationError.PORT_RANGE, validConfig().copy(serverPort = -1).validate())
        assertEquals(ValidationError.PORT_RANGE, validConfig().copy(serverPort = 65536).validate())
    }

    @Test
    fun portBoundaries_areAccepted() {
        assertNull(validConfig().copy(serverPort = 1).validate())
        assertNull(validConfig().copy(serverPort = 65535).validate())
    }

    @Test
    fun validConfig_passesValidation() {
        assertNull(validConfig().validate())
    }

    @Test
    fun serverAddr_joinsHostAndPort() {
        assertEquals("example.com:8888", validConfig().serverAddr)
    }

    @Test
    fun defaults_areExpected() {
        val config = SppConfig()
        assertEquals("tcp", config.proto)
        assertEquals(8888, config.serverPort)
        assertTrue(config.enableIpv6)
    }

    @Test
    fun protos_excludeRootOnlyRicmp() {
        assertTrue(SppConfig.PROTOS.contains("tcp"))
        assertTrue(SppConfig.PROTOS.contains("quic"))
        assertFalse(SppConfig.PROTOS.contains("ricmp"))
    }
}
