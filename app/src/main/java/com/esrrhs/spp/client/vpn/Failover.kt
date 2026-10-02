package com.esrrhs.spp.client.vpn

import com.esrrhs.spp.client.spp.Profile

/**
 * 故障切换的备用配置选择（纯逻辑，便于单测）。
 *
 * 优先选择延迟最低且已实测成功（pingMs >= 0）的其它配置；
 * 全都没测过时退化为列表中的第一个其它配置。
 */
object Failover {

    fun pickNext(profiles: List<Profile>, currentId: String): Profile? {
        val others = profiles.filter { it.id != currentId }
        return others.filter { it.pingMs >= 0 }.minByOrNull { it.pingMs }
            ?: others.firstOrNull()
    }
}
