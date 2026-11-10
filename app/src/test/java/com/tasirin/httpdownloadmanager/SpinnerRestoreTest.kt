package com.tasirin.httpdownloadmanager

import org.junit.Assert.assertEquals
import org.junit.Test

/** Uji aturan pemulihan posisi spinner sosial (issue #1485):
 *  memo #1473 yang berbasis teks label memindahkan pilihan pengguna
 *  ke kandidat lain berlabel sama; memo posisi + aturan murni ini
 *  menguncinya ke indeks kandidat hasil probe. Nilai harapan
 *  dibuktikan port Python 1:1 (audit-dm-r17/port_1485.py). */
class SpinnerRestoreTest {

    @Test
    fun restore_posisiValidDiDalamDaftar() {
        assertEquals(2, spinnerRestoreIndex(2, 5))
        assertEquals(4, spinnerRestoreIndex(4, 5))
        assertEquals(1, spinnerRestoreIndex(1, 2))
    }

    @Test
    fun restore_defaultDanTanpaMemoTidakDiubah() {
        // Posisi 0 = Default: sudah bawaan sesudah adapter dipasang.
        assertEquals(-1, spinnerRestoreIndex(0, 5))
        assertEquals(-1, spinnerRestoreIndex(null, 5))
        assertEquals(-1, spinnerRestoreIndex(-1, 5))
    }

    @Test
    fun restore_posisiDiLuarDaftarSaatIniDitolak() {
        assertEquals(-1, spinnerRestoreIndex(5, 5))
        assertEquals(-1, spinnerRestoreIndex(9, 5))
        assertEquals(-1, spinnerRestoreIndex(3, 1))
        assertEquals(-1, spinnerRestoreIndex(2, 0))
    }
}
