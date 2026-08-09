package com.tasirin.httpdownloadmanager.download

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedTrackerTest {

    @Test
    fun `sample pertama - kecepatan nol tanpa selisih waktu`() {
        var now = 0L
        val tracker = SpeedTracker { now }
        val (speed, eta) = tracker.sample("1", bytes = 100, total = 1000)
        assertEquals(0L, speed)
        assertEquals(0L, eta)
    }

    @Test
    fun `kecepatan instan dan ETA`() {
        var now = 0L
        val tracker = SpeedTracker { now }
        tracker.sample("1", bytes = 0, total = 1000)
        now = 1000 // 1 detik kemudian
        val (speed, eta) = tracker.sample("1", bytes = 500, total = 1000)
        assertEquals(500L, speed)
        assertEquals(1L, eta) // sisa 500 byte / 500 B/s = 1 detik
    }

    @Test
    fun `EMA menghaluskan lonjakan`() {
        var now = 0L
        val tracker = SpeedTracker { now }
        tracker.sample("1", bytes = 0, total = 10_000)
        now = 1000
        tracker.sample("1", bytes = 1_000, total = 10_000) // 1000 B/s
        now = 2000
        val (speed, _) = tracker.sample("1", bytes = 9_000, total = 10_000) // lonjakan 8000 B/s
        // EMA = 1000*0.8 + 8000*0.2 = 2400
        assertEquals(2400L, speed)
    }

    @Test
    fun `reset menghapus riwayat`() {
        var now = 0L
        val tracker = SpeedTracker { now }
        tracker.sample("1", bytes = 100, total = 1000)
        tracker.reset("1")
        now = 500
        val (speed, _) = tracker.sample("1", bytes = 200, total = 1000)
        assertEquals(0L, speed)
    }
}
