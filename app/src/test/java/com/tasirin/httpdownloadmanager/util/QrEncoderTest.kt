package com.tasirin.httpdownloadmanager.util

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Verifikasi encoder QR mandiri: encode lalu decode dengan zxing (test scope,
 *  tidak ikut APK) — kalau matriks salah, decode pasti gagal. */
class QrEncoderTest {

    private fun decode(matrix: QrEncoder.Matrix): String {
        val w = matrix.size
        val pixels = IntArray(w * w)
        for (y in 0 until w) {
            for (x in 0 until w) {
                pixels[y * w + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        val source = RGBLuminanceSource(w, w, pixels)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }

    @Test
    fun `encode-decode URL server pendek`() {
        val text = "http://192.168.1.10:8080/"
        val matrix = QrEncoder.encode(text)
        assertNotNull(matrix)
        assertEquals(text, decode(matrix!!))
    }

    @Test
    fun `encode-decode berbagai panjang di level L dan M`() {
        val samples = listOf(
            "a",
            "hello world",
            "https://example.com/path?q=1&r=2&s=3",
            "A".repeat(50),
            "B".repeat(100),
            "C".repeat(200),
            "D".repeat(400),
            "E".repeat(700),
            "F".repeat(1200),
            "G".repeat(2000)
        )
        for (s in samples) {
            for (ecc in listOf(QrEncoder.Ecc.L, QrEncoder.Ecc.M)) {
                val matrix = QrEncoder.encode(s, ecc)
                assertNotNull("$ecc len=${s.length}", matrix)
                assertEquals("$ecc len=${s.length}", s, decode(matrix!!))
            }
        }
    }

    @Test
    fun `encode-decode kapasitas maksimum level L`() {
        val s = "X".repeat(2900)
        val matrix = QrEncoder.encode(s, QrEncoder.Ecc.L)
        assertNotNull(matrix)
        assertEquals(s, decode(matrix!!))
    }

    @Test
    fun `teks utf-8 multibyte`() {
        val s = "kopi ☕ dan nasi goreng 🍛 — indonesia"
        val matrix = QrEncoder.encode(s)
        assertNotNull(matrix)
        assertEquals(s, decode(matrix!!))
    }

    @Test
    fun `teks kosong tetap menghasilkan matriks`() {
        assertNotNull(QrEncoder.encode(""))
    }

    @Test
    fun `melebihi kapasitas versi 40 mengembalikan null`() {
        assertNull(QrEncoder.encode("X".repeat(4000)))
        assertNull(QrEncoder.encode("Y".repeat(2400), QrEncoder.Ecc.M))
    }
}
