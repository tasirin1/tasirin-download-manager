package com.tasirin.httpdownloadmanager.download

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveProgressTest {

    @Test
    fun `total efektif adaptif hanya dari Content-Length riil`() {
        // Nilai harapan dibuktikan port Python 1:1 (audit 14, port-1428.py).
        // Berkas selesai + Content-Length berkas aktif = total gabungan.
        assertEquals(600L, muxEffectiveTotal(100, 500))
        assertEquals(500L, muxEffectiveTotal(0, 500))
        assertEquals(500L, muxEffectiveTotal(250, 250))
        // Tanpa Content-Length (0 atau -1): 0 = tak diketahui secara jujur,
        // menggantikan mesin estimasi segmen yatim yang mengunci 0%.
        assertEquals(0L, muxEffectiveTotal(100, 0))
        assertEquals(0L, muxEffectiveTotal(100, -1))
        assertEquals(0L, muxEffectiveTotal(0, 0))
    }
}
