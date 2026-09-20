package com.tasirin.httpdownloadmanager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedOptionsTest {

    @Test
    fun `daftar opsi tidak kosong dan 0 berarti tanpa batas`() {
        assertTrue(SpeedOptions.SPEED_KBPS.isNotEmpty())
        assertEquals(0, SpeedOptions.SPEED_KBPS.first())
    }

    @Test
    fun `opsi terurut naik agar spinner konsisten`() {
        val opts = SpeedOptions.SPEED_KBPS
        assertEquals(opts.sortedArray().toList(), opts.toList())
    }
}
