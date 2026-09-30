package com.esrrhs.spp.client.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** 二维码生成工具。 */
object QrCodes {

    /** 把文本编码为位图；size 为边长像素。 */
    fun toBitmap(text: String, size: Int = 800): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                bitmap.setPixel(
                    x, y,
                    if (matrix.get(x, y)) Color.BLACK else Color.WHITE,
                )
            }
        }
        return bitmap
    }
}
