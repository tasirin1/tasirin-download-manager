package com.tasirin.httpdownloadmanager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageCleanupTest {

    @Test
    fun `throttle - run pertama setelah boot langsung boleh`() {
        // Uptime kecil (baru boot) + belum pernah jalan = terbuka.
        // Nilai awal lastRunElapsed wajib -MIN_INTERVAL_MS agar kasus ini lolos.
        assertTrue(StorageCleanup.isThrottleOpen(60_000L, -5 * 60 * 1000L))
        assertTrue(StorageCleanup.isThrottleOpen(0L, -5 * 60 * 1000L))
    }

    @Test
    fun `throttle - run beruntun diblokir`() {
        assertFalse(StorageCleanup.isThrottleOpen(6 * 60 * 1000L, 5 * 60 * 1000L + 1))
        assertFalse(StorageCleanup.isThrottleOpen(100_000L, 100_000L))
    }

    @Test
    fun `throttle - run lama dibuka lagi`() {
        assertTrue(StorageCleanup.isThrottleOpen(10 * 60 * 1000L, 5 * 60 * 1000L))
        assertTrue(StorageCleanup.isThrottleOpen(10 * 60 * 1000L + 1, 5 * 60 * 1000L))
    }
}
