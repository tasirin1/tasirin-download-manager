package com.tasirin.httpdownloadmanager.remote

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import com.tasirin.httpdownloadmanager.util.QrEncoder
import java.io.ByteArrayOutputStream

/** Generator QR PNG (dipakai endpoint /api/qr untuk tautan cepat ke server). */
object QrCode {

    fun generate(text: String): ByteArray? = runCatching {
        val size = 520
        val matrix = QrEncoder.encode(text) ?: return null
        val quiet = 1
        val dim = matrix.size + quiet * 2
        val scale = (size / dim).coerceAtLeast(1)
        val offset = (size - matrix.size * scale) / 2
        val bmp = createBitmap(size, size, Bitmap.Config.RGB_565)
        val pixels = IntArray(size * size)
        for (x in 0 until size) {
            val mx = (x - offset) / scale
            val inMatrix = mx in 0 until matrix.size
            for (y in 0 until size) {
                val my = (y - offset) / scale
                val dark = inMatrix && my in 0 until matrix.size && matrix.get(mx, my)
                pixels[x * size + y] = if (dark) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
        }
        bmp.set(0, 0, size, size, pixels)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        out.toByteArray()
    }.getOrNull()
}
