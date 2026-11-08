package com.tasirin.httpdownloadmanager.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsFrameGateTest {

    @Test
    fun `gerbang hitung frame hanya lolos bila ada durasi positif`() {
        // Issue #1245/#1262: daftar kosong dan semua-nol tak bernilai
        // dihitung (writeAll pasti membuangnya ke fallback PTS); satu
        // saja durasi positif membuat hitungan terpakai.
        assertFalse(HlsMp4Muxer.durationsWorthCounting(emptyList()))
        assertFalse(HlsMp4Muxer.durationsWorthCounting(listOf(0L, 0L, -5L)))
        assertTrue(HlsMp4Muxer.durationsWorthCounting(listOf(0L, 1L)))
        assertTrue(HlsMp4Muxer.durationsWorthCounting(listOf(4_000_000L)))
    }
}
