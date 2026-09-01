package com.tasirin.httpdownloadmanager.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadItemTest {

    private fun item(bytesDownloaded: Long, totalBytes: Long) = DownloadItem(
        id = "1",
        url = "https://example.com/file.bin",
        fileName = "file.bin",
        state = DownloadState.DOWNLOADING,
        bytesDownloaded = bytesDownloaded,
        totalBytes = totalBytes
    )

    @Test
    fun `progressPercent - perhitungan normal`() {
        assertEquals(50, item(bytesDownloaded = 500, totalBytes = 1000).progressPercent)
        assertEquals(0, item(bytesDownloaded = 0, totalBytes = 1000).progressPercent)
        assertEquals(100, item(bytesDownloaded = 1000, totalBytes = 1000).progressPercent)
    }

    @Test
    fun `progressPercent - ukuran tidak diketahui tidak crash`() {
        assertEquals(0, item(bytesDownloaded = 500, totalBytes = 0).progressPercent)
    }

    @Test
    fun `progressPercentOverride - HLS memakai persen segmen bukan total palsu`() {
        val hls = item(bytesDownloaded = 1_000_000, totalBytes = 0).copy(progressPercentOverride = 56)
        // Bar & persen memakai override, totalBytes 0 (tanpa denominator palsu).
        assertEquals(56, hls.progressPercent)
        assertEquals(0, hls.totalBytes)
    }

    @Test
    fun `progressPercentOverride - default -1 fallback ke perhitungan total`() {
        assertEquals(50, item(bytesDownloaded = 500, totalBytes = 1000).progressPercent)
        val hls = item(bytesDownloaded = 500, totalBytes = 0).copy(progressPercentOverride = -1)
        assertEquals(0, hls.progressPercent)
    }

    @Test
    fun `nilai default tidak mengubah perilaku`() {
        val item = item(bytesDownloaded = 0, totalBytes = 0)
        assertEquals(DownloadState.DOWNLOADING, item.state)
        assertNull(item.error)
        assertEquals(false, item.nameIsCustom)
        assertEquals(0, item.priority)
        assertEquals(0, item.speedLimitKbps)
    }

    // ── Tambahan: edge-case progress ────────────────────────────────

    @Test
    fun `progressPercent - overshoot tetap hitung`() {
        // bytesDownloaded > totalBytes → persentase > 100
        assertEquals(150, item(bytesDownloaded = 1500, totalBytes = 1000).progressPercent)
    }

    @Test
    fun `progressPercentOverride - menang atas totalBytes`() {
        val overridden = item(bytesDownloaded = 500, totalBytes = 1000).copy(progressPercentOverride = 75)
        assertEquals(75, overridden.progressPercent)
    }

    @Test
    fun `progressPercentOverride - zero pakai totalBytes`() {
        val overridden = item(bytesDownloaded = 500, totalBytes = 1000).copy(progressPercentOverride = 0)
        assertEquals(50, overridden.progressPercent)
    }
}
