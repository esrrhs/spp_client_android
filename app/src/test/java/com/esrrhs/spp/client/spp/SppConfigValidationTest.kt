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
}
