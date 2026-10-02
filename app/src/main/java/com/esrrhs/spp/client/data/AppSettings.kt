package com.esrrhs.spp.client.data

/** 全局设置（独立于具体配置）。 */
data class AppSettings(
    /** 开机/重启后自动连接上次配置。 */
    val bootStart: Boolean = false,
    /** 意外断线时自动重连（退避重试）。 */
    val autoReconnect: Boolean = false,
    /** 重连失败后自动切换到延迟最低的其它配置。 */
    val failover: Boolean = false,
    /** 新建配置默认绕过局域网。 */
    val defaultBypassLan: Boolean = false,
    /** 接入可信 WiFi 时暂停 VPN，离开后自动恢复。 */
    val trustedWifiEnabled: Boolean = false,
    /** 可信 WiFi SSID 集合（精确匹配，去掉系统返回的引号）。 */
    val trustedWifiSsids: Set<String> = emptySet(),
)
