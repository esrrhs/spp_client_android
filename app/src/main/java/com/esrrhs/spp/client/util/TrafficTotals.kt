package com.esrrhs.spp.client.util

import com.esrrhs.spp.client.spp.Profile

/** 跨配置的流量汇总（纯逻辑，便于单测）。 */
data class TrafficTotal(val txBytes: Long, val rxBytes: Long) {
    val totalBytes: Long get() = txBytes + rxBytes
}

object TrafficTotals {

    fun of(profiles: List<Profile>): TrafficTotal =
        TrafficTotal(
            txBytes = profiles.sumOf { it.txBytes },
            rxBytes = profiles.sumOf { it.rxBytes },
        )
}
