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

    /** Buang isi komentar + literal string/char dari sumber (issue
     *  #1086): analisis posisi gerbang wajib menilai KODE saja \u2014
     *  komentar yang menyebut nama gerbang dulu ikut terhitung sebagai
     *  titik panggil (uji sehat kandas), dan kurung kurawal di dalam
     *  string/komentar mengacaukan pencocokan badan fungsi. Komentar
     *  blok bertingkat Kotlin didukung; string mentah """...""" dan
     *  escape ditangani. Posisi antar-versi bergeser, tetapi seluruh
     *  analisis uji ini berjalan di atas hasil bersih yang sama. */
    private fun stripNonCode(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            when {
                c == '/' && i + 1 < n && src[i + 1] == '/' -> {
                    while (i < n && src[i] != '\n') i++
                }
                c == '/' && i + 1 < n && src[i + 1] == '*' -> {
                    i += 2
                    var depth = 1
                    while (i < n && depth > 0) {
                        if (src[i] == '/' && i + 1 < n && src[i + 1] == '*') {
                            depth++; i += 2
                        } else if (src[i] == '*' && i + 1 < n && src[i + 1] == '/') {
                            depth--; i += 2
                        } else {
                            i++
                        }
                    }
                    out.append(' ')
                }
                c == '"' && i + 2 < n && src[i + 1] == '"' && src[i + 2] == '"' -> {
                    i += 3
                    while (i < n && !(src[i] == '"' && i + 2 < n && src[i + 1] == '"' && src[i + 2] == '"')) {
                        i++
                    }
                    i = minOf(n, i + 3)
                    out.append("\"\"")
                }
                c == '"' -> {
                    i++
                    while (i < n && src[i] != '"') {
                        if (src[i] == '\\') i++
                        i++
                    }
                    i = minOf(n, i + 1)
                    out.append("\"\"")
                }
                c == '\'' -> {
                    i++
                    while (i < n && src[i] != '\'') {
                        if (src[i] == '\\') i++
                        i++
                    }
                    i = minOf(n, i + 1)
                    out.append("''")
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** Akhir badan fungsi: posisi kurung kurawal penutup yang cocok
     *  dengan kurung pembuka pertama sesudah [decl], pada sumber yang
     *  sudah dibersihkan. Menggantikan pencarian penanda visibilitas
     *  (issue #1086): penanda lama hanya mengenal private/internal/
     *  override, jadi gerbang yang terpindah ke fungsi sesudahnya
     *  yang berawalan fun/suspend/public/val tak terdeteksi keluar
     *  badan. Tanda tangan kedua fungsi jangkar uji ini bebas kurung
     *  kurawal sebelum badan, jadi kurung pertama memang pembuka badan. */
    private fun bodyEnd(src: String, decl: Int): Int {
        val open = src.indexOf('{', decl)
        if (open < 0) return src.length
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return src.length
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
            gate < bodyEnd(src, decl)
        )
    }

    @Test
    fun `gerbang resume utuh terpasang di dalam runDownload`() {
        val src = stripNonCode(engineSource())
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
        val src = stripNonCode(engineSource())
        assertGateInside(
            src,
            "fun downloadSegment(",
            "isSegmentComplete(segment.start, segment.end, downloaded)"
        )
        assertEquals(2, countOccurrences(src, "isSegmentComplete("))
    }
}
