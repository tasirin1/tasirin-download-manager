package com.tasirin.httpdownloadmanager.util

import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackRaceControlTest {

    /** Koneksi yang belum pernah terhubung: openConnection() tidak
     *  menyambung dan disconnect() padanya aman (no-op) di JVM. */
    private fun unconnectedConn(): HttpURLConnection =
        URL("http://127.0.0.1/race").openConnection() as HttpURLConnection

    @Test
    fun `kontrol mulai tidak berhenti dan stop membalik bendera`() {
        val race = SocialMediaExtractor.FallbackRaceControl()
        assertFalse(race.stopped)
        race.stop()
        assertTrue(race.stopped)
        // stop idempoten: panggilan kedua tidak melempar.
        race.stop()
        assertTrue(race.stopped)
    }

    @Test
    fun `register dan unregister aman di semua urutan terhadap stop`() {
        val race = SocialMediaExtractor.FallbackRaceControl()
        val a = unconnectedConn()
        val b = unconnectedConn()
        race.register(a)
        race.register(b)
        race.unregister(a)
        // Yang masih terdaftar diputuskan stop(); yang sudah lepas tidak.
        race.stop()
        assertTrue(race.stopped)
        // Register sesudah stop: koneksi langsung diputuskan, tak menggantung.
        val c = unconnectedConn()
        race.register(c)
        race.unregister(c)
    }
}
