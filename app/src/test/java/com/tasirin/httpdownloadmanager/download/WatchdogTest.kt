package com.tasirin.httpdownloadmanager.download

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class WatchdogTest {
    @Test fun lambatTapiBergerak_tidakDivonis() {
        val w = DownloadHealthWatchdog(0)
        var downloaded = 0L
        var t = 0L
        repeat(120) {
            t += 1000
            downloaded += 1500
            w.check(t, downloaded, 1_000_000L, 1500L)
        }
        assertTrue(downloaded > 0)
    }

    @Test fun macetDenganSpeedBasi_divonis20Detik() {
        val w = DownloadHealthWatchdog(0)
        var t = 0L
        repeat(19) {
            t += 1000
            w.check(t, 5000L, 1_000_000L, 1500L)
        }
        // Panggilan pertama (t=1000) tercatat sebagai progres sehingga
        // slowSince mulai di t=2000 -> vonis jatuh di t=22000.
        try {
            w.check(22_000L, 5000L, 1_000_000L, 1500L)
            fail("harus melempar IOException saat byte macet 20 detik")
        } catch (_: IOException) {
        }
    }

    @Test fun stallNol_divonis30Detik() {
        val w = DownloadHealthWatchdog(0)
        var t = 0L
        repeat(29) {
            t += 1000
            w.check(t, 5000L, 1_000_000L, 0L)
        }
        // Panggilan pertama (t=1000) tercatat sebagai progres sehingga
        // lastAt mulai di t=1000 -> vonis stall jatuh di t=31000.
        try {
            w.check(31_000L, 5000L, 1_000_000L, 0L)
            fail("harus melempar IOException saat stall 30 detik")
        } catch (_: IOException) {
        }
    }

    @Test fun limitUserRendah_bebasVonisLambat() {
        val w = DownloadHealthWatchdog(1)
        var t = 0L
        repeat(25) {
            t += 1000
            w.check(t, 5000L, 1_000_000L, 1000L)
        }
    }

    @Test fun selesaiAtauTotalNol_tidakDivonis() {
        val w = DownloadHealthWatchdog(0)
        w.check(100_000L, 1_000_000L, 1_000_000L, 0L)
        w.check(100_000L, 0L, 0L, 0L)
    }
}
