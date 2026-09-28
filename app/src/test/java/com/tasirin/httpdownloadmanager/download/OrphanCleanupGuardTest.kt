package com.tasirin.httpdownloadmanager.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrphanCleanupGuardTest {

    @Test
    fun `antrean kosong tapi blob tersimpan - lewati cleanup`() {
        assertTrue(DownloadEngine.shouldSkipOrphanCleanup(itemCount = 0, hadStored = true))
    }

    @Test
    fun `fresh install tanpa blob - tetap bersihkan`() {
        assertFalse(DownloadEngine.shouldSkipOrphanCleanup(itemCount = 0, hadStored = false))
    }

    @Test
    fun `ada item - tetap bersihkan`() {
        assertFalse(DownloadEngine.shouldSkipOrphanCleanup(itemCount = 3, hadStored = true))
        assertFalse(DownloadEngine.shouldSkipOrphanCleanup(itemCount = 1, hadStored = false))
    }
}
