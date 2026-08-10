package com.tasirin.httpdownloadmanager.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerLogTest {

    @Test
    fun `buffer dibatasi - baris terlama dibuang`() {
        val log = ServerLog(maxLines = 3)
        log.append("satu")
        log.append("dua")
        log.append("tiga")
        log.append("empat")
        val lines = log.snapshot().lines()
        assertEquals(3, lines.size)
        assertEquals("dua", lines[0].substringAfter(' '))
        assertEquals("empat", lines[2].substringAfter(' '))
    }

    @Test
    fun `baris panjang dipotong dengan ellipsis`() {
        val log = ServerLog(maxLineLength = 10)
        log.append("12345678901234567890")
        val line = log.snapshot().lines().single()
        assertTrue(line.endsWith("…"))
        // timestamp HH:mm:ss.SSS = 12 karakter + spasi
        assertEquals(12 + 1 + 10 + 1, line.length)
    }

    @Test
    fun `snapshot memakai format timestamp`() {
        val log = ServerLog()
        log.append("halo")
        val line = log.snapshot().lines().single()
        assertTrue(Regex("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3} halo$").matches(line))
    }

    @Test
    fun `clear mengosongkan buffer`() {
        val log = ServerLog()
        log.append("halo")
        log.clear()
        assertEquals("", log.snapshot())
    }
}
