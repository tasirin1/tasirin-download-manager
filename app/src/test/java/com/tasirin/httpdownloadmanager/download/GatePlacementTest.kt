package com.tasirin.httpdownloadmanager.download

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Penjaga struktural posisi gerbang anti-hilang-progres (issue #664).
 *
 * Uji aritmetika helper murninya ada di SegmentRangeTest; yang dijaga di
 * sini adalah POSISI titik panggilnya di jalur produksi: gerbang #630 harus
 * tetap di dalam downloadSegment dan gerbang #650 di dalam runDownload.
 * Menghapus, memindah keluar fungsi, atau menduplikasi salah satu gerbang
 * menggagalkan uji ini — tanpa penjaga ini refactor bisa menghidupkan lagi
 * bug #630/#650 sementara semua uji aritmetika tetap hijau.
 */
class GatePlacementTest {

    private fun engineSource(): String {
        val rel = "src/main/java/com/tasirin/httpdownloadmanager/download/DownloadEngine.kt"
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            val d = dir ?: return@repeat
            for (candidate in listOf(File(d, "app/$rel"), File(d, rel))) {
                if (candidate.isFile) return candidate.readText()
            }
            dir = d.parentFile
        }
        fail("DownloadEngine.kt tidak ditemukan dari user.dir=${System.getProperty("user.dir")}")
        error("unreachable")
    }

    /** Indeks anggota kelas berikutnya (indentasi 4 spasi) sesudah [from]. */
    private fun nextMemberIndex(src: String, from: Int): Int {
        var best = src.length
        for (marker in listOf("\n    private ", "\n    internal ", "\n    override ")) {
            val i = src.indexOf(marker, from)
            if (i >= 0 && i < best) best = i
        }
        return best
    }

    /** Hitung kemunculan [needle] tanpa kompilasi Pattern (pola split boros). */
    private fun countOccurrences(src: String, needle: String): Int {
        var count = 0
        var i = src.indexOf(needle)
        while (i >= 0) {
            count++
            i = src.indexOf(needle, i + needle.length)
        }
        return count
    }

    private fun assertGateInside(src: String, funAnchor: String, gateCall: String) {
        val decl = src.indexOf(funAnchor)
        assertTrue("deklarasi $funAnchor tidak ditemukan", decl >= 0)
        val gate = src.indexOf(gateCall, decl)
        assertTrue("gerbang $gateCall tidak ditemukan sesudah $funAnchor", gate >= 0)
        assertTrue(
            "gerbang $gateCall berada di luar badan $funAnchor",
            gate < nextMemberIndex(src, decl + funAnchor.length)
        )
    }

    @Test
    fun `gerbang resume utuh terpasang di dalam runDownload`() {
        val src = engineSource()
        assertGateInside(
            src,
            "fun runDownload(",
            "isResumeAlreadyComplete(resumeFrom, item.totalBytes)"
        )
        // Tepat satu titik panggil di seluruh berkas (selain definisinya).
        assertEquals(2, countOccurrences(src, "isResumeAlreadyComplete("))
    }

    @Test
    fun `gerbang segmen utuh terpasang di dalam downloadSegment`() {
        val src = engineSource()
        assertGateInside(
            src,
            "fun downloadSegment(",
            "isSegmentComplete(segment.start, segment.end, downloaded)"
        )
        assertEquals(2, countOccurrences(src, "isSegmentComplete("))
    }
}
