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
    fun `panjang segmen terbuka jenuh, tidak membungkus`() {
        // Segmen terbuka [0, Long.MAX_VALUE] dari parseSegments (keluarga
        // #748/#789): panjang sebenarnya MAX+1, dijenuhkan ke MAX —
        // hitungan polos end - start + 1 membungkus ke Long.MIN_VALUE
        // dan segmen divonis tuntas tanpa satu byte pun (issue #812).
        // Nilai harapan dibuktikan dengan port Python 1:1.
        assertEquals(Long.MAX_VALUE, segmentLength(0, Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE - 4, segmentLength(5, Long.MAX_VALUE))
        assertEquals(10L, segmentLength(10, 19))
        assertEquals(1L, segmentLength(0, 0))
        assertTrue(isSegmentComplete(0, Long.MAX_VALUE, Long.MAX_VALUE))
        assertFalse(isSegmentComplete(0, Long.MAX_VALUE, Long.MAX_VALUE - 1))
    }

    @Test
    fun `resume single-stream sudah utuh - total diketahui dan catatan mencapai total`() {
        // Pas di total dan melebihi total (catatan basi) sama-sama utuh.
        assertTrue(isResumeAlreadyComplete(1000, 1000))
        assertTrue(isResumeAlreadyComplete(1001, 1000))
        // Kurang dari total -> tetap unduh; total tak diketahui -> tetap unduh.
        assertFalse(isResumeAlreadyComplete(999, 1000))
        assertFalse(isResumeAlreadyComplete(0, 1000))
        assertFalse(isResumeAlreadyComplete(1000, 0))
        assertFalse(isResumeAlreadyComplete(1000, -1))
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
