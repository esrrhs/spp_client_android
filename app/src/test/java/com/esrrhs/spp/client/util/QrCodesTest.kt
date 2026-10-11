package com.esrrhs.spp.client.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仅调用纯 JVM 的 encodable（zxing core 无 Android 依赖）；
 * toBitmap 涉及 android.graphics.Bitmap，不在 JVM 单测覆盖。
 */
class QrCodesTest {

    @Test
    fun normalSharePayload_isEncodable() {
        // 典型 spp:// base64 配置（数百字节）
        val payload = "spp://" + "A".repeat(500)
        assertTrue(QrCodes.encodable(payload))
    }

    @Test
    fun emptyText_isEncodable() {
        assertTrue(QrCodes.encodable(""))
    }

    @Test
    fun asciiTextBeyondQrCapacity_isNotEncodable() {
        // QR 40-L 上限约 2953 字节，4000 个 ASCII 必超
        assertFalse(QrCodes.encodable("A".repeat(4000)))
    }

    @Test
    fun multibyteBeyondQrCapacity_isNotEncodable() {
        // 每个中文字符 UTF-8 占 3 字节，1500 字 ≈ 4500 字节
        assertFalse(QrCodes.encodable("中".repeat(1500)))
    }

    @Test
    fun nearButWithinCapacity_isEncodable() {
        // 2000 字节在 40-L 容量内
        assertTrue(QrCodes.encodable("A".repeat(2000)))
    }
}
