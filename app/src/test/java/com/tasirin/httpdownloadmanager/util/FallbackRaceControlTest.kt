package com.tasirin.httpdownloadmanager.util

import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
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
    fun `stop memutuskan koneksi yang masih terdaftar, bukan yang sudah lepas`() {
        // Issue #1482: substansi #1448 adalah disconnect-nya, bukan
        // bendera — pencatat yang disuntik mengamatinya langsung.
        val disconnected = mutableListOf<HttpURLConnection>()
        val race = SocialMediaExtractor.FallbackRaceControl { conn -> disconnected.add(conn) }
        val a = unconnectedConn()
        val b = unconnectedConn()
        race.register(a)
        race.register(b)
        race.unregister(a)
        race.stop()
        assertEquals(listOf(b), disconnected)
    }

    @Test
    fun `stop memutuskan seluruh koneksi terdaftar`() {
        val disconnected = mutableListOf<HttpURLConnection>()
        val race = SocialMediaExtractor.FallbackRaceControl { conn -> disconnected.add(conn) }
        val a = unconnectedConn()
        val b = unconnectedConn()
        race.register(a)
        race.register(b)
        race.stop()
        assertEquals(setOf(a, b), disconnected.toSet())
    }

    @Test
    fun `register sesudah stop langsung memutuskan koneksi pendaftar`() {
        val disconnected = mutableListOf<HttpURLConnection>()
        val race = SocialMediaExtractor.FallbackRaceControl { conn -> disconnected.add(conn) }
        race.stop()
        val c = unconnectedConn()
        race.register(c)
        assertEquals(listOf(c), disconnected)
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
