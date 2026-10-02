package com.esrrhs.spp.client.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReconnectBackoffTest {

    @Test
    fun delaysDoubleUntilCappedAt16Seconds() {
        assertEquals(1000L, ReconnectBackoff.delayMs(1))
        assertEquals(2000L, ReconnectBackoff.delayMs(2))
        assertEquals(4000L, ReconnectBackoff.delayMs(3))
        assertEquals(8000L, ReconnectBackoff.delayMs(4))
        assertEquals(16000L, ReconnectBackoff.delayMs(5))
        // 封顶 16s，不随尝试次数继续增长
        assertEquals(16000L, ReconnectBackoff.delayMs(6))
        assertEquals(16000L, ReconnectBackoff.delayMs(20))
    }

    @Test
    fun attemptZero_isRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ReconnectBackoff.delayMs(0)
        }
    }
}
