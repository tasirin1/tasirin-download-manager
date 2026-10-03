package com.tasirin.httpdownloadmanager.util

import com.tasirin.httpdownloadmanager.data.DownloadItem
import com.tasirin.httpdownloadmanager.data.DownloadSegment
import com.tasirin.httpdownloadmanager.data.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialSweepTest {

    private fun item(name: String, segments: List<DownloadSegment> = emptyList()) = DownloadItem(
        id = "1",
        url = "https://example.com/f",
        fileName = name,
        state = DownloadState.PAUSED,
        bytesDownloaded = 10,
        totalBytes = 100,
        segments = segments
    )

    @Test
    fun `basis selalu dilindungi walau item bersegmen`() {
        val seg = item("video.mp4", listOf(DownloadSegment(0, 0, 99, 10)))
        val names = protectedPartialNames(seg)
        assertTrue(names.contains("video.mp4.part"))
        assertTrue(names.contains("video.mp4.part.0"))
    }

    @Test
    fun `item tanpa segmen hanya basis`() {
        assertEquals(setOf("video.mp4.part"), protectedPartialNames(item("video.mp4")))
    }

    @Test
    fun `pola sapu mencakup merge staging`() {
        assertTrue(isSweepablePartial("video.mp4.part"))
        assertTrue(isSweepablePartial("video.mp4.part.2"))
        assertTrue(isSweepablePartial("video.mp4.part.merge.123"))
        assertFalse(isSweepablePartial("video.mp4"))
        assertFalse(isSweepablePartial("notes.txt"))
    }
}
