package com.tasirin.httpdownloadmanager.data

import org.junit.Assert.assertEquals
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
    fun `nilai default tidak mengubah perilaku`() {
        val item = item(bytesDownloaded = 0, totalBytes = 0)
        assertEquals(DownloadState.DOWNLOADING, item.state)
        assertEquals("", item.error)
        assertEquals(false, item.nameIsCustom)
        assertEquals(0, item.priority)
        assertEquals(0, item.speedLimitKbps)
    }
}
