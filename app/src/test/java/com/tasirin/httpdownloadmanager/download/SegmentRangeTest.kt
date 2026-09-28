package com.tasirin.httpdownloadmanager.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentRangeTest {

    @Test
    fun `ujung range tak dikirim atau pas - diizinkan`() {
        assertTrue(isSegmentEndAllowed(null, 999))
        assertTrue(isSegmentEndAllowed(999, 999))
    }

    @Test
    fun `ujung lebih kecil - ditoleransi, lebih besar - ditolak`() {
        // Server boleh mempersempit respons; kekurangan tertangkap cek
        // incomplete lalu retry mengisi sisa.
        assertTrue(isSegmentEndAllowed(499, 999))
        // Kelebihan = byte korup bila diserap (verifySize membolehkan overrun).
        assertFalse(isSegmentEndAllowed(1000, 999))
        assertFalse(isSegmentEndAllowed(5000, 999))
    }

    @Test
    fun `tulis segmen dibatasi budget`() {
        assertEquals(100, cappedSegmentWrite(100, 1000))
        assertEquals(1000, cappedSegmentWrite(2000, 1000))
        // Budget habis/negatif -> nol: pemanggil wajib gagal eksplisit.
        assertEquals(0, cappedSegmentWrite(100, 0))
        assertEquals(0, cappedSegmentWrite(100, -50))
    }
}
