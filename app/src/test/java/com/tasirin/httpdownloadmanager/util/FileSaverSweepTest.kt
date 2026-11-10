package com.tasirin.httpdownloadmanager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSaverSweepTest {

    @Test
    fun `partial basis dan segmen sweepable`() {
        assertTrue(isSweepablePartial("movie.mp4.part"))
        assertTrue(isSweepablePartial("movie.mp4.part.0"))
        assertTrue(isSweepablePartial("movie.mp4.part.12"))
    }

    @Test
    fun `temp remux sweepable`() {
        assertTrue(isSweepablePartial("movie.video.part"))
        assertTrue(isSweepablePartial("movie.audio.part"))
        assertTrue(isSweepablePartial("movie.muxed.part"))
    }

    @Test
    fun `sidecar warisan spool dan segs sweepable sebagai yatim`() {
        // Issue #1437: penulis sidecar ini (mesin lama) sudah dihapus,
        // jadi sisa instalasi lama adalah sampah warisan yang WAJIB
        // tersapu — bukan lagi penanda resume yang dilindungi.
        assertTrue(isSweepablePartial("movie.video.part.spool"))
        assertTrue(isSweepablePartial("movie.video.part.segs"))
        assertTrue(isSweepablePartial("movie.audio.part.segs"))
        assertTrue(isSweepablePartial("movie.video.part.spool.3"))
        // Nama final yang kebetulan memuat kata itu di tengah tetap aman:
        // pola warisan terjangkar di ekor nama.
        assertFalse(isSweepablePartial("movie.spool.mp4"))
        assertFalse(isSweepablePartial("movie.segs.mp4"))
    }

    @Test
    fun `staging publish di folder luar hanya cocok untuk nama final dikenal`() {
        val known = setOf("film.mp4", "lagu.mp3")
        assertTrue(isPublishStagingName("film.mp4.part", known))
        assertTrue(isPublishStagingName("film.mp4 (1).part", known))
        assertTrue(isPublishStagingName("lagu.mp3 (12).part", known))
        // Bukan staging kita: nama asing, ekstensi lain, atau .part di tengah.
        assertFalse(isPublishStagingName("dokumen lain.mp4.part", known))
        assertFalse(isPublishStagingName("film.mp4.part.0", known))
        assertFalse(isPublishStagingName("film.mp4", known))
        assertFalse(isPublishStagingName("film (1).mp4.part", known))
        // Sufiks bertumpuk dari jalur kalah-lomba ikut cocok (issue #1193).
        assertTrue(isPublishStagingName("film.mp4 (1) (1).part", known))
        assertTrue(isPublishStagingName("film.mp4 (1) (2).part", setOf("film.mp4 (1)")))
        assertFalse(isPublishStagingName("lain.mp4 (1) (1).part", known))
    }

    @Test
    fun `staging merge sweepable`() {
        assertTrue(isSweepablePartial("movie.mp4.merge.123456789"))
    }

    @Test
    fun `file final bernama part di tengah TIDAK sweepable`() {
        // Regresi issue #137: pola longgar ".part." menyapu file selesai.
        assertFalse(isSweepablePartial("film.part.1.mp4"))
        assertFalse(isSweepablePartial("game.part.2.rar"))
        assertFalse(isSweepablePartial("movie.part.mp4"))
        assertFalse(isSweepablePartial("movie.mp4"))
    }

    @Test
    fun `ekor mirip tapi bukan partial TIDAK sweepable`() {
        assertFalse(isSweepablePartial("movie.mp4.part.bak"))
        assertFalse(isSweepablePartial("movie.mp4.merge.final"))
        assertFalse(isSweepablePartial("party.mp4"))
    }
}
