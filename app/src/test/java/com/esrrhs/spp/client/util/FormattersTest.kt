package com.esrrhs.spp.client.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun bytesBelowKilo_useBUnit() {
        assertEquals("0B", Formatters.formatBytes(0))
        assertEquals("512B", Formatters.formatBytes(512))
        assertEquals("1023B", Formatters.formatBytes(1023))
    }

    @Test
    fun bytesAtKiloBoundary_useKb() {
        assertEquals("1.0KB", Formatters.formatBytes(1024))
        assertEquals("1.5KB", Formatters.formatBytes(1536))
    }

    @Test
    fun largerUnits_areSelected() {
        assertEquals("1.0MB", Formatters.formatBytes(1024L * 1024))
        assertEquals("1.0GB", Formatters.formatBytes(1024L * 1024 * 1024))
        assertEquals("1.0TB", Formatters.formatBytes(1024L * 1024 * 1024 * 1024))
    }
}
