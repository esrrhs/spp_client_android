package com.esrrhs.spp.client.proxy

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream

/**
 * SOCKS5 报文编解码（纯逻辑 + 流读写），供本地分流代理使用。
 * RFC 1928：METHOD 协商、CONNECT/UDP ASSOCIATE 请求、BND 地址解析。
 */
object Socks5Codec {

    const val VER: Byte = 0x05
    const val CMD_CONNECT: Byte = 0x01
    const val CMD_UDP_ASSOCIATE: Byte = 0x03
    const val ATYP_IPV4: Byte = 0x01
    const val ATYP_DOMAIN: Byte = 0x03
    const val ATYP_IPV6: Byte = 0x04

    const val REP_SUCCESS: Byte = 0x00
    const val REP_GENERAL_FAILURE: Byte = 0x01
    const val REP_COMMAND_NOT_SUPPORTED: Byte = 0x07

    data class Request(
        val command: Int,
        val atyp: Int,
        /** 域名（ATYP=3）或 IP 文本（ATYP=1/4）。 */
        val host: String,
        val port: Int,
        /** 原始请求字节（VER 起到端口结束），可直接转发给上游。 */
        val raw: ByteArray,
    )

    fun greeting(): ByteArray = byteArrayOf(VER, 0x01, 0x00)

    /** 读取服务端侧的方法协商，仅接受 NO-AUTH；不支持时回复 0xFF 并返回 false。 */
    fun acceptMethod(input: DataInputStream, out: OutputStream): Boolean {
        val ver = input.readUnsignedByte()
        if (ver != 0x05) return false
        val n = input.readUnsignedByte()
        repeat(n) { input.readUnsignedByte() }
        // 始终回 NO-AUTH
        out.write(byteArrayOf(VER, 0x00))
        out.flush()
        return true
    }

    /** 解析 CONNECT / UDP ASSOCIATE 请求；报文非法抛 [IOException]。 */
    fun readRequest(input: DataInputStream): Request {
        val ver = input.readUnsignedByte()
        val cmd = input.readUnsignedByte()
        input.readUnsignedByte() // RSV
        val atyp = input.readUnsignedByte()
        require(ver == 0x05) { "bad socks version $ver" }
        val host = when (atyp) {
            0x01 -> {
                val b = ByteArray(4)
                input.readFully(b)
                b.joinToString(".") { (it.toInt() and 0xFF).toString() }
            }
            0x03 -> {
                val len = input.readUnsignedByte()
                val b = ByteArray(len)
                input.readFully(b)
                String(b, Charsets.US_ASCII)
            }
            0x04 -> {
                val b = ByteArray(16)
                input.readFully(b)
                (0..7).joinToString(":") { i ->
                    String.format(
                        "%02x%02x",
                        b[i * 2].toInt() and 0xFF,
                        b[i * 2 + 1].toInt() and 0xFF,
                    )
                }
            }
            else -> throw IOException("unsupported atyp $atyp")
        }
        val portHi = input.readUnsignedByte()
        val portLo = input.readUnsignedByte()
        val port = (portHi shl 8) or portLo

        // 重新序列化一份规整的请求用于上游转发
        val raw = buildRequest(cmd.toByte(), atyp, host, port)
        return Request(cmd, atyp, host, port, raw)
    }

    /** 构造请求报文（域名使用 ATYP=3，IP 按字面量判断 v4/v6）。 */
    fun buildRequest(cmd: Byte, atypHint: Int? = null, host: String, port: Int): ByteArray {
        val atyp = atypHint ?: when {
            host.contains(":") -> 0x04
            else -> 0x03
        }
        val head = byteArrayOf(VER, cmd, 0x00, atyp.toByte())
        val addr = when (atyp) {
            0x01 -> host.split(".").map { it.toInt().toByte() }.toByteArray()
            0x04 -> ipv6Bytes(host)
            else -> {
                val b = host.toByteArray(Charsets.US_ASCII)
                byteArrayOf(b.size.toByte()) + b
            }
        }
        return head + addr + byteArrayOf((port ushr 8).toByte(), port.toByte())
    }

    /** 解析 IPv6 文本（支持 :: 压缩）为 16 字节；解析失败返回 16 个 0。 */
    fun ipv6Bytes(text: String): ByteArray {
        val parts = arrayOfNulls<String>(8)
        val split = text.split("::")
        val head = if (split[0].isEmpty()) emptyList() else split[0].split(":")
        val tail = if (split.size == 2 && split[1].isNotEmpty()) split[1].split(":") else emptyList()
        head.forEachIndexed { i, g -> parts[i] = g }
        tail.forEachIndexed { i, g -> parts[7 - tail.size + 1 + i] = g }
        val out = ByteArray(16)
        for (i in 0..7) {
            val v = parts[i]?.toIntOrNull(16) ?: 0
            out[i * 2] = (v ushr 8).toByte()
            out[i * 2 + 1] = v.toByte()
        }
        return out
    }

    /** 构造成功回复（监听本地回环端口时使用）。 */
    fun successReply(boundHost: String, boundPort: Int): ByteArray {
        val addr = byteArrayOf(ATYP_IPV4) +
            boundHost.split(".").map { it.toInt().toByte() }.toByteArray()
        return byteArrayOf(VER, REP_SUCCESS, 0x00) + addr +
            byteArrayOf((boundPort ushr 8).toByte(), boundPort.toByte())
    }

    fun failureReply(rep: Byte): ByteArray =
        byteArrayOf(VER, rep, 0x00, ATYP_IPV4, 0, 0, 0, 0, 0, 0)

    /** 读取上游回复，返回 BND 地址（host, port）；REP 非 0 或报文非法时返回 null。 */
    fun readReply(input: DataInputStream): Pair<String, Int>? {
        if (input.readUnsignedByte() != 0x05) return null
        val rep = input.readUnsignedByte()
        input.readUnsignedByte()
        if (rep != 0) return null
        val atyp = input.readUnsignedByte()
        val host = when (atyp) {
            0x01 -> {
                val b = ByteArray(4); input.readFully(b)
                b.joinToString(".") { (it.toInt() and 0xFF).toString() }
            }
            0x03 -> {
                val len = input.readUnsignedByte()
                val b = ByteArray(len); input.readFully(b)
                String(b, Charsets.US_ASCII)
            }
            0x04 -> {
                val b = ByteArray(16); input.readFully(b)
                b.joinToString(":") { String.format("%02x%02x", b[0], b[1]) }
            }
            else -> return null
        }
        val port = (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
        return host to port
    }
}
