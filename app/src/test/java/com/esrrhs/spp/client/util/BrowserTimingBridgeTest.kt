package com.esrrhs.spp.client.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserTimingBridgeTest {

    @Test
    fun testParseTimingJson() {
        val json = """
            [
                {
                    "name": "https://example.com/",
                    "initiatorType": "navigation",
                    "startTime": 0,
                    "responseEnd": 450.5,
                    "duration": 450.5,
                    "dnsStart": 10.0,
                    "dnsEnd": 55.0,
                    "connectStart": 55.0,
                    "connectEnd": 120.0,
                    "sslStart": 80.0,
                    "sslEnd": 120.0,
                    "requestStart": 125.0,
                    "responseStart": 280.0,
                    "transferSize": 1540,
                    "decodedBodySize": 3200,
                    "nextHopProtocol": "h2"
                }
            ]
        """.trimIndent()

        val list = BrowserTimingBridge.parseTimingJson(json)
        assertEquals(1, list.size)
        val item = list[0]
        assertEquals("https://example.com/", item.name)
        assertEquals("navigation", item.initiatorType)
        assertEquals(45.0, item.dnsDuration, 0.001)
        assertEquals(65.0, item.connectDuration, 0.001)
        assertEquals(40.0, item.sslDuration, 0.001)
        assertEquals(155.0, item.ttfb, 0.001)
        assertEquals(170.5, item.downloadDuration, 0.001)
        assertEquals("h2", item.nextHopProtocol)
    }

    @Test
    fun testPostLogLimits() {
        val bridge = BrowserTimingBridge()
        repeat(250) { i ->
            bridge.postLog("INFO", "message $i")
        }
        val logs = bridge.logs.value
        assertEquals(200, logs.size)
        assertTrue(logs.last().contains("message 249"))
    }

    @Test
    fun testRetainHistoryAcrossNavigation() {
        val bridge = BrowserTimingBridge()
        val jsonPage1 = """
            [
                { "name": "https://page1.com/index.html", "initiatorType": "navigation", "startTime": 0, "duration": 100 }
            ]
        """.trimIndent()
        val jsonPage2 = """
            [
                { "name": "https://page2.com/index.html", "initiatorType": "navigation", "startTime": 0, "duration": 150 }
            ]
        """.trimIndent()

        bridge.currentPageUrl = "https://page1.com"
        bridge.postTimingData(jsonPage1)
        assertEquals(1, bridge.records.value.size)

        bridge.currentPageUrl = "https://page2.com"
        bridge.postTimingData(jsonPage2)
        assertEquals(2, bridge.records.value.size)
        assertEquals("https://page1.com", bridge.records.value[0].pageUrl)
        assertEquals("https://page2.com", bridge.records.value[1].pageUrl)
    }
}
