package com.esrrhs.spp.client.util

import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocketFactory

/**
 * SOCKS5 探针：经本地 socks5_client 访问外网，用于真实延迟测量与 DNS 触发。
 *
 * - [measure]：端到端实测——SOCKS 建连 + 远端解析 + TLS 握手 + HTTP GET
 *   www.google.com/generate_204 直到收到响应状态行，而非仅 SOCKS/TCP 握手；
 * - [connectOnly]：只做 CONNECT（供 DNS 泄漏探测触发远端解析用）。
 */
object SocksProbe {

    const val DEFAULT_TARGET_HOST = "www.google.com"
    const val DEFAULT_TARGET_PORT = 443
    const val DEFAULT_TARGET_PATH = "/generate_204"

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
     * 经隧道真实访问目标网站：TLS 握手 + HTTP GET，返回端到端耗时（ms）。
     * 取多次采样最小值；任一环节失败返回 -1。
     */
    fun measure(
        socksPort: Int,
        samples: Int = 2,
        targetHost: String = DEFAULT_TARGET_HOST,
        targetPort: Int = DEFAULT_TARGET_PORT,
        path: String = DEFAULT_TARGET_PATH,
        timeoutMs: Int = 8000,
    ): Int {
        var best = -1
        repeat(samples) {
            val ms = oneHttpSample(socksPort, targetHost, targetPort, path, timeoutMs)
            if (ms >= 0 && (best == -1 || ms < best)) best = ms
        }
        return best
    }

    private fun oneHttpSample(
        socksPort: Int,
        host: String,
        port: Int,
        path: String,
        timeoutMs: Int,
    ): Int = runCatching {
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.soTimeout = timeoutMs
            val start = System.nanoTime()
            socket.connect(InetSocketAddress("127.0.0.1", socksPort), timeoutMs)

            val out: OutputStream = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())

            if (!socksConnect(input, out, host, port)) return@runCatching -1

            // 在隧道 socket 上做真实 TLS 握手（SNI=host，证书校验走系统信任库）
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket(socket, host, port, true) as javax.net.ssl.SSLSocket
            ssl.soTimeout = timeoutMs
            ssl.use {
                it.startHandshake()
                val sslOut = it.getOutputStream()
                val sslIn = DataInputStream(it.getInputStream())
                sslOut.write(
                    ("GET $path HTTP/1.1\r\nHost: $host\r\n" +
                        "User-Agent: curl/8.0\r\nConnection: close\r\n\r\n")
                        .toByteArray(Charsets.US_ASCII),
                )
                sslOut.flush()
                val statusLine = sslIn.readLine() ?: return@use -1
                val code = Regex("HTTP/\\S+\\s+(\\d{3})").find(statusLine)?.groupValues?.get(1)
                val elapsed = ((System.nanoTime() - start) / 1_000_000).toInt()
                val ok = code != null &&
                    (code.startsWith("2") || code == "301" || code == "302")
                if (ok) elapsed else -1
            }
        }
    }.getOrDefault(-1)

    /** 仅完成 SOCKS5 CONNECT（含远端域名解析），成功返回 true。用于 DNS 探测触发。 */
    fun connectOnly(
        socksPort: Int,
        targetHost: String = DEFAULT_TARGET_HOST,
        targetPort: Int = DEFAULT_TARGET_PORT,
        timeoutMs: Int = 5000,
    ): Boolean = runCatching {
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress("127.0.0.1", socksPort), timeoutMs)
            socksConnect(
                DataInputStream(socket.getInputStream()),
                socket.getOutputStream(),
                targetHost,
                targetPort,
            )
        }
    }.getOrDefault(false)

    private fun socksConnect(
        input: DataInputStream,
        out: OutputStream,
        host: String,
        port: Int,
    ): Boolean {
        out.write(greeting())
        out.flush()
        if (input.readUnsignedByte() != 0x05) return false
        if (input.readUnsignedByte() != 0x00) return false

        out.write(connectDomain(host, port))
        out.flush()
        if (input.readUnsignedByte() != 0x05) return false
        if (input.readUnsignedByte() != 0x00) return false
        skipBoundAddress(input)
        return true
    }

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
