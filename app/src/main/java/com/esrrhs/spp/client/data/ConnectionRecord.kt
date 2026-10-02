package com.esrrhs.spp.client.data

import kotlinx.serialization.Serializable

/** 一次完整 VPN 会话的历史记录（含断线重连，不含重连期间切换走的配置）。 */
@Serializable
data class ConnectionRecord(
    val id: String,
    val profileId: String,
    /** 记录时的配置名快照（配置随后被改名/删除也能展示）。 */
    val profileName: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val txBytes: Long,
    val rxBytes: Long,
    /** 结束原因码，见 [HistoryReason]。 */
    val reason: String,
)

/** 会话结束原因码（UI 层映射为本地化文案）。 */
object HistoryReason {
    const val DISCONNECTED = "disconnected"
    const val ERROR = "error"
    const val PAUSED = "paused"
    const val FAILOVER = "failover"
}
