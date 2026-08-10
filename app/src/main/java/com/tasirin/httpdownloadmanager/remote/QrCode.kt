package com.tasirin.httpdownloadmanager.remote

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.ByteArrayOutputStream

/** Generator QR PNG (dipakai endpoint /api/qr untuk tautan cepat ke server). */
object QrCode {

    fun generate(text: String): ByteArray? = runCatching {
        val size = 520
        val matrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1)
        )
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        out.toByteArray()
    }.getOrNull()
}
