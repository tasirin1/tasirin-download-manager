package com.tasirin.httpdownloadmanager.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeMulDivTest {

    @Test
    fun `perkalian-bagi biasa tetap tepat`() {
        assertEquals(1_000_000L, safeMulDiv(8_000_000L, 1_000_000L, 8_000_000L))
        // 250 jam @ 4 Mbps = 450 GB (bagi-dulu tak menggeser hasil).
        assertEquals(450_000_000_000L, safeMulDiv(900_000_000_000L, 4_000_000L, 8_000_000L))
    }

    @Test
    fun `video normal hasilnya benar`() {
        // 1 jam @ 8 Mbps = 3,6 GB.
        assertEquals(3_600_000_000L, safeMulDiv(3_600_000_000L, 8_000_000L, 8_000_000L))
        // 10 jam @ 20 Mbps = 90 GB.
        assertEquals(90_000_000_000L, safeMulDiv(36_000_000_000L, 20_000_000L, 8_000_000L))
    }

    @Test
    fun `bandwidth jahat tak meluap negatif`() {
        // BANDWIDTH 1 Tbps dari playlist nakal x 10 detik: mentah 1e19 > Long.MAX.
        assertEquals(1_250_000_000_000L, safeMulDiv(10_000_000L, 1_000_000_000_000L, 8_000_000L))
    }

    @Test
    fun `operan raksasa jenuh bukan negatif`() {
        assertEquals(Long.MAX_VALUE, safeMulDiv(Long.MAX_VALUE, Long.MAX_VALUE, 1L))
        assertEquals(Long.MAX_VALUE, safeMulDiv(1L, Long.MAX_VALUE, 1L))
        assertTrue(safeMulDiv(10_000_000L, 1_000_000_000_000L, 8_000_000L) > 0)
    }

    @Test
    fun `nol dan negatif jadi nol`() {
        assertEquals(0L, safeMulDiv(0L, 8_000_000L, 8_000_000L))
        assertEquals(0L, safeMulDiv(100L, -5L, 8_000_000L))
        assertEquals(0L, safeMulDiv(100L, 5L, 0L))
    }

    @Test
    fun `jumlah jenuh`() {
        assertEquals(12L, saturatingAdd(5L, 7L))
        assertEquals(Long.MAX_VALUE, saturatingAdd(Long.MAX_VALUE, 1L))
        assertEquals(Long.MAX_VALUE, saturatingAdd(Long.MAX_VALUE, Long.MAX_VALUE))
    }
}
