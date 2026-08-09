package com.tasirin.httpdownloadmanager.download

import com.tasirin.httpdownloadmanager.data.DownloadItem
import com.tasirin.httpdownloadmanager.data.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadQueueTest {

    private fun item(id: String, priority: Int = 0, totalBytes: Long = 0) = DownloadItem(
        id = id,
        url = "https://example.com/$id",
        fileName = id,
        state = DownloadState.PENDING,
        bytesDownloaded = 0,
        totalBytes = totalBytes,
        priority = priority
    )

    @Test
    fun `prioritas tertinggi didahulukan`() {
        val sorted = listOf(item("a", priority = 1), item("b", priority = 5), item("c", priority = 3))
            .sortedWith(downloadQueueOrder(smallFirst = false))
        assertEquals(listOf("b", "c", "a"), sorted.map { it.id })
    }

    @Test
    fun `smallFirst - file kecil didahulukan`() {
        val sorted = listOf(
            item("big", totalBytes = 500),
            item("small", totalBytes = 10),
            item("mid", totalBytes = 100)
        ).sortedWith(downloadQueueOrder(smallFirst = true))
        assertEquals(listOf("small", "mid", "big"), sorted.map { it.id })
    }

    @Test
    fun `smallFirst - ukuran belum diketahui dimajukan ke akhir`() {
        val sorted = listOf(item("unknown"), item("known", totalBytes = 50))
            .sortedWith(downloadQueueOrder(smallFirst = true))
        assertEquals(listOf("known", "unknown"), sorted.map { it.id })
    }

    @Test
    fun `smallFirst mati - urutan awal dipertahankan (stabil)`() {
        val items = listOf(item("x", totalBytes = 500), item("y", totalBytes = 10), item("z", totalBytes = 100))
        val sorted = items.sortedWith(downloadQueueOrder(smallFirst = false))
        assertEquals(listOf("x", "y", "z"), sorted.map { it.id })
    }

    @Test
    fun `prioritas menang atas smallFirst`() {
        val sorted = listOf(
            item("p1-big", priority = 2, totalBytes = 999),
            item("p0-small", priority = 0, totalBytes = 1)
        ).sortedWith(downloadQueueOrder(smallFirst = true))
        assertEquals(listOf("p1-big", "p0-small"), sorted.map { it.id })
    }
}
