package com.esrrhs.spp.client.spp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SppConfigValidationTest {

    private fun spp(
        host: String = "example.com",
        port: Int = 8888,
        key: String = "secret",
        proto: String = "tcp",
        kind: String = SppConfig.KIND_SPP,
    ) = SppConfig(
        serverHost = host,
        serverPort = port,
        key = key,
        proto = proto,
        kind = kind,
    )

    @Test
    fun validSppConfigsForEverySupportedProto() {
        SppConfig.PROTOS.forEach { proto ->
            assertNull("proto=$proto should be valid", spp(proto = proto).validate())
        }
    }

    @Test
    fun socks5NeedsNeitherKeyNorSppProto() {
        // SOCKS5 配置不校验 key（无 SPP 认证）与 proto（无 SPP 传输协议）
        val socks5 = SppConfig(
            serverHost = "example.com",
            serverPort = 1080,
            kind = SppConfig.KIND_SOCKS5,
        )
        assertNull(socks5.validate())
    }

    @Test
    fun unknownKindRejected() {
        // 外部导入（扫码/文件）可携带任意 kind 字符串
        assertEquals(ValidationError.KIND_UNSUPPORTED, spp(kind = "wireguard").validate())
    }

    @Test
    fun unknownProtoRejectedForSpp() {
        // 白名单外的 proto 只能来自编辑器之外的导入，不应拼给 spp 子进程
        assertEquals(ValidationError.PROTO_UNSUPPORTED, spp(proto = "http").validate())
    }

    @Test
    fun unknownProtoIgnoredForSocks5() {
        // SOCKS5 模式没有 SPP 传输协议，proto 字段无意义
        val socks5 = SppConfig(
            serverHost = "example.com",
            serverPort = 1080,
            kind = SppConfig.KIND_SOCKS5,
            proto = "bogus",
        )
        assertNull(socks5.validate())
    }

    @Test
    fun basicFieldChecksStillFire() {
        assertEquals(ValidationError.HOST_REQUIRED, spp(host = " ").validate())
        assertEquals(ValidationError.PORT_RANGE, spp(port = 0).validate())
        assertEquals(ValidationError.PORT_RANGE, spp(port = 70000).validate())
        assertEquals(ValidationError.KEY_REQUIRED, spp(key = "").validate())
    }

    @Test
    fun kindCheckedFirstEvenWhenOtherFieldsAlsoBad() {
        val both = SppConfig(serverHost = "", kind = "nope")
        assertEquals(ValidationError.KIND_UNSUPPORTED, both.validate())
    }

    @Test
    fun socks5CredentialsAtByteLimitAreValid() {
        // RFC 1929：用户名/密码各最多 255 字节
        val socks5 = SppConfig(
            serverHost = "example.com",
            serverPort = 1080,
            kind = SppConfig.KIND_SOCKS5,
            username = "a".repeat(255),
            password = "b".repeat(255),
        )
        assertNull(socks5.validate())
    }

    @Test
    fun socks5CredentialsOverByteLimitRejected() {
        val longPassword = SppConfig(
            serverHost = "example.com",
            serverPort = 1080,
            kind = SppConfig.KIND_SOCKS5,
            password = "b".repeat(256),
        )
        assertEquals(ValidationError.CREDENTIALS_TOO_LONG, longPassword.validate())

        val longUsername = SppConfig(
            serverHost = "example.com",
            serverPort = 1080,
            kind = SppConfig.KIND_SOCKS5,
            username = "u".repeat(256),
        )
        assertEquals(ValidationError.CREDENTIALS_TOO_LONG, longUsername.validate())
    }

    @Test
    fun socks5CredentialLimitCountsUtf8BytesNotChars() {
        // 每个中文字符 UTF-8 占 3 字节：85 字 = 255 合法，86 字 = 258 超限
        val atLimit = SppConfig(
            serverHost = "example.com",
            serverPort = 1080,
            kind = SppConfig.KIND_SOCKS5,
            password = "中".repeat(85),
        )
        assertNull(atLimit.validate())

        val overLimit = atLimit.copy(password = "中".repeat(86))
        assertEquals(ValidationError.CREDENTIALS_TOO_LONG, overLimit.validate())
    }

    @Test
    fun credentialLengthIgnoredForSppKind() {
        // username/password 字段只对 SOCKS5 模式有意义，SPP 模式不检查其长度
        val sppLong = spp().copy(username = "u".repeat(300), password = "p".repeat(300))
        assertNull(sppLong.validate())
    }
}
