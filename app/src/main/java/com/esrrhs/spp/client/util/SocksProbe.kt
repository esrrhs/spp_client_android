package com.esrrhs.spp.client.util

import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * SOCKS5 探针：经本地 socks5_client 发起 CONNECT，测量「SPP 隧道 + 远端解析 +
 * 到目标建连」的真实耗时，区别于只测 TCP 握手的 [ServerPing]。
 *
 * 请求使用域名（ATYP=0x03），远端解析 DNS，能同时验证隧道的 DNS 路径。
 */
object SocksProbe {

    /** 稳定的 anycast 目标；域名解析由远端完成。 */
    const val DEFAULT_TARGET_HOST = "www.gstatic.com"
    const val DEFAULT_TARGET_PORT = 443

    // SOCKS5 报文构造（纯函数，便于单测）

    /** 方法协商：仅支持「无需认证」。 */
    fun greeting(): ByteArray = byteArrayOf(0x05, 0x01, 0x00)

    /** CONNECT 请求，ATYP=0x03（域名）。 */
    fun connectDomain(host: String, port: Int): ByteArray {
        val bytes = host.toByteArray(Charsets.US_ASCII)
        require(bytes.size in 1..255) { "host length must be 1..255" }
        return byteArrayOf(
            0x05, 0x01, 0x00, 0x03, bytes.size.toByte(),
        ) + bytes + byteArrayOf((port ushr 8).toByte(), port.toByte())
    }

    /**
     * 经 127.0.0.1:[socksPort] 代理向 [targetHost]:[targetPort] 发起 CONNECT，
     * 返回建连耗时（ms）；协商/建连失败返回 -1。
     */
    fun measure(
        socksPort: Int,
        targetHost: String = DEFAULT_TARGET_HOST,
        targetPort: Int = DEFAULT_TARGET_PORT,
        timeoutMs: Int = 5000,
    ): Int = runCatching {
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.soTimeout = timeoutMs
            val start = System.nanoTime()
            socket.connect(InetSocketAddress("127.0.0.1", socksPort), timeoutMs)

            val out: OutputStream = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())

            out.write(greeting())
            out.flush()
            if (input.readUnsignedByte() != 0x05) return@runCatching -1
            if (input.readUnsignedByte() != 0x00) return@runCatching -1 // 不接受其它认证方式

            out.write(connectDomain(targetHost, targetPort))
            out.flush()
            if (input.readUnsignedByte() != 0x05) return@runCatching -1
            val reply = input.readUnsignedByte()
            if (reply != 0x00) return@runCatching -1 // REP != 0 即失败
            skipBoundAddress(input)

            ((System.nanoTime() - start) / 1_000_000).toInt()
        }
    }.getOrDefault(-1)

    /** 丢弃回复中的 BND.ADDR + BND.PORT。 */
    private fun skipBoundAddress(input: DataInputStream) {
        input.readUnsignedByte() // RSV
        val atyp = input.readUnsignedByte()
        val addrLen = when (atyp) {
            0x01 -> 4
            0x04 -> 16
            0x03 -> input.readUnsignedByte()
            else -> 0
        }
        repeat(addrLen + 2) { input.readUnsignedByte() } // 地址 + 端口
    }
}
