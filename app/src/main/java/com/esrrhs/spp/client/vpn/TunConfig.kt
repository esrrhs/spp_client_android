package com.esrrhs.spp.client.vpn

/**
 * TUN 接口常量，与 hev-socks5-tunnel 示例配置对齐。
 *
 * DNS 使用 hev 的 mapdns（fake-ip）虚拟地址：DNS 查询由 hev 在本地应答，
 * 返回 100.64.0.0/10 段的映射 IP，后续连接经 SOCKS5 携带域名由服务端解析，
 * 因此系统 DNS 永远不会直连运营商。
 */
object TunConfig {
    const val SESSION = "SPP"

    /**
     * 必须同时满足两条上限：
     * 1. hev 回程 UDP 缓冲写死 1500 字节（含 SOCKS5 头），更大的报文会被截断；
     * 2. QUIC 禁止 IP 分片，服务端会把 UDP 载荷原样发出，公网路径大约只有 1500。
     * 1400 时 IPv4 载荷约 1372，加上域名型 SOCKS 头仍低于 1500，且不低于 QUIC 要求的 1200。
     * TCP 浏览不受影响：隧道里是字节流，由服务端按真实路径 MSS 重新分段。
     */
    const val MTU = 1400

    const val TUN_ADDRESS = "198.18.0.1"
    const val TUN_PREFIX = 30

    const val TUN_ADDRESS_V6 = "fdfe:dcba:9876::1"
    const val TUN_PREFIX_V6 = 126

    /** mapdns 虚拟 DNS 地址（hev 配置中的 mapdns.address）。 */
    const val DNS_ADDRESS = "198.18.0.2"
    const val MAPDNS_NETWORK = "100.64.0.0"
    const val MAPDNS_NETMASK = "255.192.0.0"
}
