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
    fun `segmen utuh dikenali dari hitungan tercatatnya`() {
        // Rentang [0, 999] panjangnya 1000 byte (issue #630).
        assertTrue(isSegmentComplete(0, 999, 1000))
        // Kelebihan catatan (paranoia) tetap utuh.
        assertTrue(isSegmentComplete(0, 999, 1001))
        // Kurang satu byte saja belum utuh: Range sisanya masih sah.
        assertFalse(isSegmentComplete(0, 999, 999))
        assertFalse(isSegmentComplete(0, 999, 0))
        // Segmen bergeser: yang dihitung panjang rentang, bukan posisi akhir.
        assertTrue(isSegmentComplete(1000, 1999, 1000))
        assertFalse(isSegmentComplete(1000, 1999, 999))
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
