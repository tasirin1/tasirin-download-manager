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
    fun `item tanpa segmen - basis dan temp adaptif yang dilindungi`() {
        // Temp adaptif ikut dilindungi sejak menjadi bahan resume
        // (issue #1434); id uji "1" -> tempBase "~1video".
        assertEquals(
            setOf(
                "video.mp4.part",
                "~1video.muxed.part",
                "~1video.audioM4a.part",
                "~1video.videoMp4.part"
            ),
            protectedPartialNames(item("video.mp4"))
        )
    }

    private fun itemState(name: String, state: DownloadState) = DownloadItem(
        id = "1",
        url = "https://example.com/f",
        fileName = name,
        state = state,
        bytesDownloaded = 10,
        totalBytes = 100
    )

    @Test
    fun `derivasi nama temp adaptif persis tempBase engine`() {
        // Nilai harapan dibuktikan port Python 1:1
        // (bug-99/port-temp-policy.py).
        assertEquals(
            setOf("~1video.muxed.part", "~1video.audioM4a.part", "~1video.videoMp4.part"),
            adaptiveTempNames(item("video.mp4"))
        )
        // Sufiks peka huruf kecil: .MP4 ikut terkupas (lowercase endsWith).
        assertEquals(
            adaptiveTempNames(item("video.mp4")),
            adaptiveTempNames(item("video.MP4"))
        )
        // ".mp4" diuji SEBELUM ".webm" terkupas: basis ganda menyisakan
        // sufiks pertama — persis semantik buangSufiksEkstensi.
        assertEquals(
            setOf("~1clip.mp4.muxed.part", "~1clip.mp4.audioM4a.part", "~1clip.mp4.videoMp4.part"),
            adaptiveTempNames(item("clip.mp4.webm"))
        )
    }

    @Test
    fun `tabel status perlindungan dan bersih-bersih temp adaptif`() {
        // Nilai harapan dibuktikan port Python 1:1
        // (bug-99/port-temp-policy.py). Dilindungi hanya selama item
        // masih bisa resume; disposable hanya FAILED/CANCELLED;
        // COMPLETED bukan keduanya (jalur sukses membuang temp sendiri).
        assertTrue(adaptiveTempsProtected(DownloadState.PENDING))
        assertTrue(adaptiveTempsProtected(DownloadState.DOWNLOADING))
        assertTrue(adaptiveTempsProtected(DownloadState.PAUSED))
        assertFalse(adaptiveTempsProtected(DownloadState.COMPLETED))
        assertFalse(adaptiveTempsProtected(DownloadState.FAILED))
        assertFalse(adaptiveTempsProtected(DownloadState.CANCELLED))
        assertFalse(adaptiveTempsDisposable(DownloadState.PENDING))
        assertFalse(adaptiveTempsDisposable(DownloadState.DOWNLOADING))
        assertFalse(adaptiveTempsDisposable(DownloadState.PAUSED))
        assertFalse(adaptiveTempsDisposable(DownloadState.COMPLETED))
        assertTrue(adaptiveTempsDisposable(DownloadState.FAILED))
        assertTrue(adaptiveTempsDisposable(DownloadState.CANCELLED))
    }

    @Test
    fun `temp adaptif item FAILED dan CANCELLED tidak dilindungi sapuan`() {
        // Diagnosis bug-99 B2: sebelum ini protectedPartialNames
        // melindungi temp adaptif SEMUA item terdaftar tanpa memandang
        // status, jadi item FAILED terminal menahan ±satu video penuh
        // di data aplikasi tanpa batas waktu. Basis .part dan segmen
        // tetap dilindungi seperti sebelumnya.
        assertEquals(
            setOf("video.mp4.part"),
            protectedPartialNames(itemState("video.mp4", DownloadState.FAILED))
        )
        assertEquals(
            setOf("video.mp4.part"),
            protectedPartialNames(itemState("video.mp4", DownloadState.CANCELLED))
        )
        val failedSeg = DownloadItem(
            id = "1",
            url = "https://example.com/f",
            fileName = "video.mp4",
            state = DownloadState.FAILED,
            bytesDownloaded = 10,
            totalBytes = 100,
            segments = listOf(DownloadSegment(0, 0, 99, 10))
        )
        assertEquals(
            setOf("video.mp4.part", "video.mp4.part.0"),
            protectedPartialNames(failedSeg)
        )
        // Item yang masih bisa resume tetap dilindungi penuh.
        assertEquals(
            setOf(
                "video.mp4.part",
                "~1video.muxed.part", "~1video.audioM4a.part", "~1video.videoMp4.part"
            ),
            protectedPartialNames(itemState("video.mp4", DownloadState.DOWNLOADING))
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
    fun `pola sapu mencakup spool dan segs warisan, tanpa perlindungan`() {
        // Warisan mesin lama (issue #1437): pola namanya sweepable lagi
        // agar sisa instalasi lama tersapu, tetapi TIDAK dilindungi —
        // tak ada penulisnya lagi.
        assertTrue(isSweepablePartial("~1video.video.part.spool.0"))
        assertTrue(isSweepablePartial("~1video.video.part.spool.137"))
        assertTrue(isSweepablePartial("~1video.video.part.spool"))
        assertTrue(isSweepablePartial("~1video.video.part.segs"))
        // Ekor bukan digit / pola tidak di ekor: bukan sampah internal.
        assertFalse(isSweepablePartial("video.mp4.part.spool.abc"))
        assertFalse(isSweepablePartial("video.mp4.part.spool.3.mp4"))
        assertFalse(isSweepablePartial("film.part.1.mp4"))
        // Perlindungan tetap keanggotaan himpunan persis.
        val protected = protectedPartialNames(item("video.mp4"))
        assertTrue(isProtectedPartialName("video.mp4.part", protected))
        assertFalse(isProtectedPartialName("~1video.video.part.spool.0", protected))
        assertFalse(isProtectedPartialName("~1video.video.part.segs", protected))
    }

}
