package com.esrrhs.spp.client.vpn

import com.esrrhs.spp.client.spp.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FailoverTest {

    private fun p(id: String, ping: Int = -1) = Profile(id = id, name = id, pingMs = ping)

    @Test
    fun noOtherProfile_returnsNull() {
        assertNull(Failover.pickNext(listOf(p("a")), "a"))
        assertNull(Failover.pickNext(emptyList(), "a"))
    }

    @Test
    fun prefersLowestMeasuredLatency() {
        val profiles = listOf(p("a", 100), p("b", 50), p("c", 500))
        assertEquals("b", Failover.pickNext(profiles, "a")?.id)
    }

    @Test
    fun neverPicksCurrentProfile() {
        val profiles = listOf(p("a", 1), p("b", 2))
        assertEquals("b", Failover.pickNext(profiles, "a")?.id)
        assertEquals("a", Failover.pickNext(profiles, "b")?.id)
    }

    @Test
    fun noneMeasured_fallsBackToFirstOther() {
        val profiles = listOf(p("a"), p("b"), p("c"))
        assertEquals("b", Failover.pickNext(profiles, "a")?.id)
    }

    @Test
    fun measuredProfileWinsOverUnmeasured() {
        val profiles = listOf(p("a"), p("b", 300), p("c"))
        assertEquals("b", Failover.pickNext(profiles, "a")?.id)
    }
}
