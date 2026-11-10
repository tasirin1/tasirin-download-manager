package com.tasirin.httpdownloadmanager.download

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveProgressTest {

    @Test
    fun `total efektif adaptif hanya dari Content-Length riil`() {
        // Nilai harapan dibuktikan port Python 1:1 (audit 14, port-1428.py;
        // bentuk 4 argumen: bug-99/port-mux-progress.py). Total per-sisi
        // belum dikenal (0,0) mempertahankan semantik #1428 persis.
        // Berkas selesai + Content-Length berkas aktif = total gabungan.
        assertEquals(600L, muxEffectiveTotal(100, 500, 0, 0))
        assertEquals(500L, muxEffectiveTotal(0, 500, 0, 0))
        assertEquals(500L, muxEffectiveTotal(250, 250, 0, 0))
        // Tanpa Content-Length (0 atau -1): 0 = tak diketahui secara jujur,
        // menggantikan mesin estimasi segmen yatim yang mengunci 0%.
        assertEquals(0L, muxEffectiveTotal(100, 0, 0, 0))
        assertEquals(0L, muxEffectiveTotal(100, -1, 0, 0))
        assertEquals(0L, muxEffectiveTotal(0, 0, 0, 0))
    }

    @Test
    fun `total efektif dikunci ke jumlah kedua berkas bila keduanya dikenal`() {
        // Diagnosis bug-99 A3: penyebut lama hanya memuat total berkas
        // yang sedang melapor, jadi persen melonjak/mundur antar kedua
        // coroutine konkuren. Nilai harapan: bug-99/port-mux-progress.py.
        assertEquals(800L, muxEffectiveTotal(0, 500, 500, 300))
        // Byte selesai TIDAK dihitung ganda di atas jumlah kedua total.
        assertEquals(800L, muxEffectiveTotal(500, 300, 500, 300))
        // Pelapor tak dikenal totalnya pun memakai kunci gabungan.
        assertEquals(800L, muxEffectiveTotal(300, 0, 500, 300))
        // Baru satu sisi terdaftar: byte selesai + total sisi dikenal.
        assertEquals(600L, muxEffectiveTotal(200, 400, 0, 400))
        assertEquals(400L, muxEffectiveTotal(0, 400, 500, 0))
    }

    @Test
    fun `persen tampilan fase unduh monoton di jendela registrasi total kedua`() {
        // Issue #1481: nilai harapan dibuktikan port Python 1:1
        // (audit-dm-r16/port_mux_monotonic.py). Tanpa lantai, contoh
        // sah dari uji total efektif di atas menurunkan persen yang
        // sudah tampil 25% menjadi 12,5% tanpa byte hilang.
        assertEquals(25, muxMonotonicPercent(100, 400, 0))
        assertEquals(25, muxMonotonicPercent(100, 800, 25))
        assertEquals(12, muxMonotonicPercent(100, 800, 0))
        assertEquals(62, muxMonotonicPercent(500, 800, 25))
        assertEquals(0, muxMonotonicPercent(0, 800, 0))
        assertEquals(0, muxMonotonicPercent(100, 0, 0))
        assertEquals(30, muxMonotonicPercent(100, 0, 30))
        assertEquals(98, muxMonotonicPercent(790, 800, 0))
        // Fase unduh tak pernah menampilkan 100 (hanya COMPLETED).
        assertEquals(99, muxMonotonicPercent(800, 800, 0))
        assertEquals(99, muxMonotonicPercent(900, 800, 0))
        assertEquals(99, muxMonotonicPercent(100, 400, 150))
    }

    @Test
    fun `persen fase gabung memetakan byte tersalin ke pita awal sampai 99`() {
        // Nilai harapan dibuktikan port Python 1:1
        // (bug-99/port-mux-progress.py). 100 hanya dari state COMPLETED.
        assertEquals(90, muxMergePercent(0, 1000, 90))
        assertEquals(99, muxMergePercent(1000, 1000, 90))
        assertEquals(94, muxMergePercent(500, 1000, 90))
        assertEquals(49, muxMergePercent(500, 1000, 0))
        assertEquals(0, muxMergePercent(0, 1000, 0))
        assertEquals(99, muxMergePercent(1000, 1000, 0))
        assertEquals(93, muxMergePercent(1, 3, 90))
        assertEquals(99, muxMergePercent(999, 1000, 99))
    }

    @Test
    fun `persen fase gabung jujur saat total tak dikenal dan input liar dijepit`() {
        // Total tak dikenal: diam di persen awal fase, bukan angka palsu.
        assertEquals(42, muxMergePercent(300, 0, 42))
        assertEquals(42, muxMergePercent(300, -5, 42))
        // Byte tersalin di luar rentang dijepit ke [0, total].
        assertEquals(99, muxMergePercent(2000, 1000, 90))
        assertEquals(90, muxMergePercent(-10, 1000, 90))
        // Persen awal di luar rentang dijepit ke [0, 99].
        assertEquals(99, muxMergePercent(500, 1000, 150))
        assertEquals(49, muxMergePercent(500, 1000, -3))
    }
}
