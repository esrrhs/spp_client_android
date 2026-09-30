package com.esrrhs.spp.client.data

/** 全局设置（独立于具体配置）。 */
data class AppSettings(
    /** 开机/重启后自动连接上次配置。 */
    val bootStart: Boolean = false,
    /** 意外断线时自动重连（退避重试）。 */
    val autoReconnect: Boolean = false,
    /** 新建配置默认绕过局域网。 */
    val defaultBypassLan: Boolean = false,
)
