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
        } catch (_: IOException) { // audit-ignore: kotlin_empty_catch (pola uji: tangkapan IOException adalah jalur sukses yang diharapkan)
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
        } catch (_: IOException) { // audit-ignore: kotlin_empty_catch (pola uji: tangkapan IOException adalah jalur sukses yang diharapkan)
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

    @Test fun batasLimitedLow_tepatDiAmbang() {
        // isLimitedLow murni (issue #1509): 2 KB/s tepat di ambang
        // 2048 B/s (<=) sehingga bebas vonis; 3 KB/s di atasnya.
        // Ekspektasi dibuktikan port Python 1:1 (port_1509_watchdog.py).
        assertTrue(!DownloadHealthWatchdog.isLimitedLow(0))
        assertTrue(DownloadHealthWatchdog.isLimitedLow(1))
        assertTrue(DownloadHealthWatchdog.isLimitedLow(2))
        assertTrue(!DownloadHealthWatchdog.isLimitedLow(3))
        assertTrue(!DownloadHealthWatchdog.isLimitedLow(-1))
    }

    @Test fun limitTurunKeRendahDiTengah_membebaskanVonisLambat() {
        // Issue #1509 arah A: watchdog dibuat TANPA limit, lalu pengguna
        // menurunkan limit ke 1 KB/s di tengah attempt (fitur #747
        // berlaku langsung). Vonis dinilai dari limit TERKINI, jadi
        // kecepatan rendah sesudahnya adalah akibat limit yang sah —
        // watchdog beku ala lama akan memvonis palsu di t=22000.
        var limit = 0
        val w = DownloadHealthWatchdog { limit }
        var t = 0L
        repeat(10) {
            t += 1000
            w.check(t, 5000L, 1_000_000L, 1500L)
        }
        limit = 1
        repeat(30) {
            t += 1000
            w.check(t, 5000L, 1_000_000L, 1000L)
        }
    }

    @Test fun limitDihapusDiTengah_vonisLambatAktifLagi() {
        // Issue #1509 arah B: limit 1 KB/s membebaskan vonis selama
        // aktif; sesudah pengguna menghapus limit di tengah attempt,
        // kecepatan rendah bukan lagi akibat limit dan vonis lambat
        // 20 detik wajib hidup lagi (slowSince mulai dari peralihan).
        var limit = 1
        val w = DownloadHealthWatchdog { limit }
        var t = 0L
        repeat(10) {
            t += 1000
            w.check(t, 5000L, 1_000_000L, 1000L)
        }
        limit = 0
        repeat(19) {
            t += 1000
            w.check(t, 5000L, 1_000_000L, 1000L)
        }
        // slowSince mulai di t=11000 (panggilan pertama sesudah limit
        // dihapus) -> vonis jatuh di t=31000.
        try {
            w.check(31_000L, 5000L, 1_000_000L, 1000L)
            fail("harus melempar IOException: vonis lambat aktif lagi sesudah limit dihapus")
        } catch (_: IOException) { // audit-ignore: kotlin_empty_catch (pola uji: tangkapan IOException adalah jalur sukses yang diharapkan)
        }
    }

    @Test fun selesaiAtauTotalNol_tidakDivonis() {
        val w = DownloadHealthWatchdog(0)
        w.check(100_000L, 1_000_000L, 1_000_000L, 0L)
        w.check(100_000L, 0L, 0L, 0L)
    }

    @Test fun melewatiTotalLaluMacet_divonisStall() {
        // Issue #618: total dinyatakan 100_000 tetapi 150_000 byte
        // terkirim (server under-declare, ditoleransi #170) lalu
        // koneksi macet total — vonis macet wajib tetap hidup dengan
        // memperlakukan total sebagai tak diketahui.
        val w = DownloadHealthWatchdog(0)
        w.check(1_000L, 150_000L, 100_000L, 0L)
        try {
            w.check(31_000L, 150_000L, 100_000L, 0L)
            fail("harus melempar IOException saat byte macet 30 detik sesudah melewati total")
        } catch (_: IOException) { // audit-ignore: kotlin_empty_catch (pola uji: tangkapan IOException adalah jalur sukses yang diharapkan)
        }
    }

    @Test fun melewatiTotalLambatBergerak_tidakDivonisLambat() {
        // Mode total-tak-diketahui tak punya vonis lambat: byte yang
        // melewati total tetapi terus bergerak tak boleh divonis.
        val w = DownloadHealthWatchdog(0)
        var downloaded = 150_000L
        var t = 0L
        while (t <= 120_000L) {
            w.check(t, downloaded, 100_000L, 1500L)
            downloaded += 150L
            t += 1_000L
        }
    }


    @Test fun totalNolBergerak_tidakDivonisLambat() {
        // Unknown-length bersifat burst: byte bergerak tak boleh kena
        // vonis lambat 20 detik seperti total diketahui.
        val w = DownloadHealthWatchdog(0)
        var downloaded = 0L
        var t = 0L
        repeat(60) {
            t += 1000
            downloaded += 1500
            w.check(t, downloaded, 0L, 1500L)
        }
    }

    @Test fun totalNolMacetTotal_divonisStall() {
        val w = DownloadHealthWatchdog(0)
        w.check(1_000L, 5000L, 0L, 0L)
        try {
            w.check(31_000L, 5000L, 0L, 0L)
            fail("harus melempar IOException saat total tak diketahui tapi stall 30 detik")
        } catch (_: IOException) { // audit-ignore: kotlin_empty_catch (pola uji: tangkapan IOException adalah jalur sukses yang diharapkan)
        }
    }
}
