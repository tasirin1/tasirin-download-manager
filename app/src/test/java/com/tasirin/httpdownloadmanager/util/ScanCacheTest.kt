package com.tasirin.httpdownloadmanager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanCacheTest {

    private val ttl = 15_000L

    @Test
    fun `scan tuntas - cache terpakai untuk limit berapa pun dalam TTL`() {
        // Galeri kecil: 77 item, total 77 — scan sebelumnya tuntas.
        assertTrue(MediaLibrary.scanCacheUsable(1_000, ttl, 77, 77, 200))
        assertTrue(MediaLibrary.scanCacheUsable(1_000, ttl, 77, 77, 3_000))
    }

    @Test
    fun `scan memuat cukup entry - cache terpakai`() {
        assertTrue(MediaLibrary.scanCacheUsable(1_000, ttl, 3_000, 5_000, 3_000))
        assertTrue(MediaLibrary.scanCacheUsable(1_000, ttl, 200, 5_000, 200))
    }

    @Test
    fun `cache kedaluwarsa - tidak terpakai`() {
        assertFalse(MediaLibrary.scanCacheUsable(15_000, ttl, 77, 77, 200))
        assertFalse(MediaLibrary.scanCacheUsable(20_000, ttl, 3_000, 5_000, 3_000))
    }

    @Test
    fun `scan terpotong dan belum cukup - tidak terpakai`() {
        // Scan lama terpotong (100 dari 5000) dan limit minta lebih banyak.
        assertFalse(MediaLibrary.scanCacheUsable(1_000, ttl, 100, 5_000, 3_000))
    }
}
