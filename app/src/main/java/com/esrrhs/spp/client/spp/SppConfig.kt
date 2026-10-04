package com.esrrhs.spp.client.spp

import kotlinx.serialization.Serializable

/**
 * SPP 连接配置，字段与 spp 命令行参数一一对应（见 README「SPP 参数映射」）。
 */
@Serializable
data class SppConfig(
    val serverHost: String = "",
    val serverPort: Int = 8888,
    val proto: String = "tcp",
    val key: String = "",
    val encrypt: String = "",
    val compress: Int = 0,
    /** 是否接管 IPv6（addRoute ::/0）；关闭时仅代理 IPv4。 */
    val enableIpv6: Boolean = false,
    /** [KIND_SPP] 或 [KIND_SOCKS5]。 */
    val kind: String = KIND_SPP,
    /** SOCKS5 用户名；为空则不认证。 */
    val username: String = "",
    /** SOCKS5 密码。 */
    val password: String = "",
) {
    val serverAddr: String
        get() = "$serverHost:$serverPort"

    val isSocks5: Boolean
        get() = kind == KIND_SOCKS5

    /** 列表上展示的协议名：SOCKS5 本身，或 SPP 的传输协议。 */
    val displayProto: String
        get() = if (isSocks5) KIND_SOCKS5 else proto

    /** 返回首个校验错误码；null 表示合法。 */
    fun validate(): ValidationError? = when {
        serverHost.isBlank() -> ValidationError.HOST_REQUIRED
        serverPort !in 1..65535 -> ValidationError.PORT_RANGE
        !isSocks5 && key.isBlank() -> ValidationError.KEY_REQUIRED
        else -> null
    }

    companion object {
        const val KIND_SPP = "spp"
        const val KIND_SOCKS5 = "socks5"
        val KINDS = listOf(KIND_SPP, KIND_SOCKS5)

        /**
         * spp 实际支持的传输协议（`./spp -h`）：
         * tcp rudp ricmp kcp quic rhttp。ricmp 需要 root，不提供。
         */
        val PROTOS = listOf("tcp", "rudp", "kcp", "quic", "rhttp")
    }
}
