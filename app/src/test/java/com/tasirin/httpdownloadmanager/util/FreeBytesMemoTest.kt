package com.tasirin.httpdownloadmanager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #1458: kesegaran memo ruang bebas penjaga berjalan.
 *  Nilai harapan dibuktikan port Python 1:1 (audit-dm-r15/port-1458.py). */
class FreeBytesMemoTest {

    @Test
    fun `entri segar hanya di bawah satu detik`() {
        assertTrue(freeBytesMemoFresh(0))
        assertTrue(freeBytesMemoFresh(999))
        assertFalse(freeBytesMemoFresh(1_000))
        assertFalse(freeBytesMemoFresh(60_000))
    }

    @Test
    fun `umur negatif dianggap basi`() {
        assertFalse(freeBytesMemoFresh(-1))
    }
}
