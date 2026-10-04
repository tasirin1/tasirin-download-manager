package com.tasirin.httpdownloadmanager.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SseStreamTest {

    @Test
    fun `push saat antrean penuh menutup dan membangunkan pembaca`() {
        val s = SseStream()
        repeat(40) { s.push("data: $it\n\n") }
        assertTrue(s.isClosed)
        // Tanpa pil bangun, read() menunggu timeout 25 dtk dulu; kini -1 langsung.
        val t0 = System.currentTimeMillis()
        assertEquals(-1, s.read())
        assertTrue(System.currentTimeMillis() - t0 < 5000)
    }

    @Test
    fun `closeStream membangunkan poll tanpa menggantung`() {
        val s = SseStream()
        s.closeStream()
        assertTrue(s.isClosed)
        assertEquals(-1, s.read())
    }
}
