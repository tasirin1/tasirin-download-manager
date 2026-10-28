package com.tasirin.httpdownloadmanager.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SseStreamTest {

    @Test
    fun `push saat antrean penuh menutup dan frame terantre tetap tersaji`() {
        val s = SseStream()
        repeat(40) { s.push("data: $it\n\n") }
        assertTrue(s.isClosed)
        // Sesudah #288 frame yang terantre SEBELUM penutupan tetap
        // disajikan lebih dulu (uji lama mengharap -1 langsung —
        // perilaku pra-#288 yang memang diubah #288, ujinya tertinggal).
        // Pil tutup mengakhiri aliran sesudah antrean habis, tanpa
        // menunggu timeout 25 dtk.
        val t0 = System.currentTimeMillis()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = s.read(buf, 0, buf.size)
            if (n < 0) break
            total += n
        }
        assertTrue(total > 0)
        assertEquals(-1, s.read())
        assertTrue(System.currentTimeMillis() - t0 < 5000)
    }

    @Test
    fun `pushUrgent menyelipkan frame shutdown walau antrean penuh`() {
        val s = SseStream()
        repeat(32) { s.push("data: $it\n\n") }
        assertTrue(!s.isClosed)
        s.pushUrgent("data: {\"shutdown\":true}\n\n")
        s.closeStream()
        val out = StringBuilder()
        val buf = ByteArray(4096)
        while (true) {
            val n = s.read(buf, 0, buf.size)
            if (n < 0) break
            out.append(String(buf, 0, n, Charsets.UTF_8))
        }
        assertTrue(out.toString().contains("\"shutdown\":true"))
    }

    @Test
    fun `closeStream membangunkan poll tanpa menggantung`() {
        val s = SseStream()
        s.closeStream()
        assertTrue(s.isClosed)
        assertEquals(-1, s.read())
    }
}
