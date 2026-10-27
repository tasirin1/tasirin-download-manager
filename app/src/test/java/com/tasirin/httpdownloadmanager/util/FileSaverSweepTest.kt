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
    fun `temp HLS sweepable`() {
        assertTrue(isSweepablePartial("movie.video.part"))
        assertTrue(isSweepablePartial("movie.audio.part"))
        assertTrue(isSweepablePartial("movie.muxed.part"))
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
