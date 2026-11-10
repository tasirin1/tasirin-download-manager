package com.tasirin.httpdownloadmanager.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RepositoryProgressFileTest {

    @Test
    fun `sumber progres - berkas baru menang, warisan hanya saat baru kosong`() {
        // Nilai harapan dibuktikan port Python 1:1 (audit 14, port-1433.py).
        assertEquals(ProgressSource.NEW_FILE, progressSource(newHas = true, legacyHas = true))
        assertEquals(ProgressSource.NEW_FILE, progressSource(newHas = true, legacyHas = false))
        assertEquals(ProgressSource.LEGACY_MIGRATE, progressSource(newHas = false, legacyHas = true))
        assertEquals(ProgressSource.NONE, progressSource(newHas = false, legacyHas = false))
    }
}
