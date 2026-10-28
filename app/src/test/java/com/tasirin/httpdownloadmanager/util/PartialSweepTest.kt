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
    fun `item tanpa segmen basis plus temp HLS`() {
        // Basis + temp resume HLS (#409); tanpa segmen unduhan biasa.
        assertEquals(
            setOf(
                "video.mp4.part",
                "~1video.video.part", "~1video.video.part.segs", "~1video.video.part.spool",
                "~1video.audio.part", "~1video.audio.part.segs", "~1video.audio.part.spool"
            ),
            protectedPartialNames(item("video.mp4"))
        )
    }

    @Test
    fun `nama kapital basis temp HLS sama dengan basis engine`() {
        // Issue #529: engine membuang ekstensi tanpa peka kapital
        // (#522); protectedPartialNames harus menurunkan basis yang
        // sama, kalau tidak temp resume nama kapital tersapu yatim.
        assertEquals(
            setOf(
                "Film.MP4.part",
                "~1Film.video.part", "~1Film.video.part.segs", "~1Film.video.part.spool",
                "~1Film.audio.part", "~1Film.audio.part.segs", "~1Film.audio.part.spool"
            ),
            protectedPartialNames(item("Film.MP4"))
        )
    }

    @Test
    fun `pola sapu mencakup merge staging`() {
        assertTrue(isSweepablePartial("video.mp4.part"))
        assertTrue(isSweepablePartial("video.mp4.part.2"))
        assertTrue(isSweepablePartial("video.mp4.part.merge.123"))
        assertFalse(isSweepablePartial("video.mp4"))
        assertFalse(isSweepablePartial("notes.txt"))
    }

    @Test
    fun `temp HLS nama tepi mengikuti derivasi engine`() {
        // #443: derivasi harus meniru urutan engine (buang sufiks dari
        // nama mentah, safe() pada gabungan). Nama reserved: engine
        // menghasilkan "~1CON..." TANPA awalan "_" yang ditambahkan
        // safe() bila diterapkan pada nama item lebih dulu.
        val reserved = protectedPartialNames(item("CON.mp4"))
        assertTrue(reserved.contains("~1CON.video.part"))
        assertTrue(reserved.contains("~1CON.audio.part"))
        assertFalse(reserved.contains("~1_CON.video.part"))
        // Nama berakhiran spasi: sufiks .mp4 TIDAK terbuang oleh engine
        // (string berakhir spasi), jadi tempnya memuat ".mp4 " utuh.
        val trailing = protectedPartialNames(item("film.mp4 "))
        assertTrue(trailing.contains("~1film.mp4 .video.part"))
        assertTrue(trailing.contains("~1film.mp4 .video.part.segs"))
    }

    @Test
    fun `temp resume HLS ikut dilindungi`() {
        // Turunan DownloadEngine.downloadHls: tempBase = "~" + id8 + basis
        // tanpa ekstensi; id uji "1" sehingga tempBase "~1video".
        val names = protectedPartialNames(item("video.mp4"))
        assertTrue(names.contains("~1video.video.part"))
        assertTrue(names.contains("~1video.video.part.segs"))
        assertTrue(names.contains("~1video.video.part.spool"))
        assertTrue(names.contains("~1video.audio.part"))
        assertTrue(names.contains("~1video.audio.part.segs"))
        assertTrue(names.contains("~1video.audio.part.spool"))
    }
}
