package com.tasirin.httpdownloadmanager.remote

import com.tasirin.httpdownloadmanager.data.DownloadSegment
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

/** Uji fungsi murni awalan terunduh stream parsial segmen + aliran
 *  pembatasnya (issue #1459). Ekspektasi angka dibuktikan lebih dulu
 *  dengan port Python 1:1 (audit-dm-r16/port_prefix.py): berkas `.part`
 *  terpadu pra-alokasi (#1427) hanya boleh menyajikan awalan yang
 *  kontinu terunduh — byte sesudahnya nol pra-alokasi. */
class SegmentPrefixTest {

    private fun seg(index: Int, start: Long, end: Long, downloaded: Long) =
        DownloadSegment(index, start, end, downloaded)

    @Test
    fun prefix_kosongDanSebagian() {
        assertEquals(0L, downloadedPrefixLength(emptyList()))
        assertEquals(100L, downloadedPrefixLength(listOf(seg(0, 0, 99, 100))))
        assertEquals(40L, downloadedPrefixLength(listOf(seg(0, 0, 99, 40))))
        assertEquals(0L, downloadedPrefixLength(listOf(seg(0, 0, 99, 0))))
    }

    @Test
    fun prefix_berhentiDiSegmenBelumTuntasPertama() {
        // Ekor tuntas sesudah segmen bolong tidak ikut dihitung.
        assertEquals(
            130L,
            downloadedPrefixLength(
                listOf(seg(0, 0, 99, 100), seg(1, 100, 199, 30), seg(2, 200, 299, 100))
            )
        )
        assertEquals(
            150L,
            downloadedPrefixLength(
                listOf(seg(0, 0, 99, 100), seg(1, 100, 199, 50), seg(2, 200, 299, 100))
            )
        )
        assertEquals(
            0L,
            downloadedPrefixLength(
                listOf(seg(0, 0, 99, 0), seg(1, 100, 199, 100))
            )
        )
    }

    @Test
    fun prefix_urutIndeksDanJepitNilaiLiar() {
        // Masukan tak urut dinilai per indeks, bukan urutan daftar.
        assertEquals(
            200L,
            downloadedPrefixLength(listOf(seg(1, 100, 199, 100), seg(0, 0, 99, 100)))
        )
        // downloaded melampaui panjang segmen dijepit ke panjangnya;
        // nilai negatif dijepit ke nol.
        assertEquals(100L, downloadedPrefixLength(listOf(seg(0, 0, 99, 250))))
        assertEquals(0L, downloadedPrefixLength(listOf(seg(0, 0, 99, -5))))
    }

    @Test
    fun prefix_segmenTerbukaEndMaksimum() {
        // Segmen terbuka (end = Long.MAX_VALUE, bentuk sah codec #1042):
        // panjang inklusifnya jenuh, bukan meluap ke 0 (issue #1483).
        // Awalan = byte terunduh segmen itu sendiri; ekor sesudahnya
        // (tak ada) tidak ikut. Ekspektasi dibuktikan port_1483.py.
        assertEquals(
            12345L,
            downloadedPrefixLength(listOf(seg(0, 0, Long.MAX_VALUE, 12345)))
        )
        assertEquals(
            0L,
            downloadedPrefixLength(listOf(seg(0, 0, Long.MAX_VALUE, 0)))
        )
        assertEquals(
            0L,
            downloadedPrefixLength(listOf(seg(0, 0, Long.MAX_VALUE, -5)))
        )
    }

    @Test
    fun prefix_campuranTuntasDanTerbuka() {
        // Segmen tuntas penuh + segmen terbuka sebagian: awalan adalah
        // gabungan keduanya, berhenti di dalam segmen terbuka.
        assertEquals(
            877L,
            downloadedPrefixLength(
                listOf(seg(0, 0, 99, 100), seg(1, 100, Long.MAX_VALUE, 777))
            )
        )
        // Segmen terbuka tidak harus mulai dari 0 untuk dihitung benar.
        assertEquals(
            42L,
            downloadedPrefixLength(listOf(seg(0, 500, Long.MAX_VALUE, 42)))
        )
    }

    @Test
    fun limitedStream_berhentiTepatDiBatas() {
        val data = ByteArray(10) { it.toByte() }
        LimitedInputStream(ByteArrayInputStream(data), 4).use { s ->
            val out = s.readBytes()
            assertEquals(4, out.size)
            assertEquals(listOf(0, 1, 2, 3), out.map { it.toInt() })
            assertEquals(-1, s.read())
        }
    }

    @Test
    fun limitedStream_batasMelebihiDataDanNol() {
        val data = byteArrayOf(7, 8, 9)
        LimitedInputStream(ByteArrayInputStream(data), 100).use { s ->
            assertEquals(3, s.readBytes().size)
        }
        LimitedInputStream(ByteArrayInputStream(data), 0).use { s ->
            assertEquals(-1, s.read())
            assertEquals(0, s.readBytes().size)
        }
    }

    @Test
    fun limitedStream_skipDibatasiSisa() {
        val data = ByteArray(10) { it.toByte() }
        LimitedInputStream(ByteArrayInputStream(data), 6).use { s ->
            // Minta skip 8, yang terlewati hanya sisa batas (6).
            assertEquals(6L, s.skip(8))
            assertEquals(-1, s.read())
        }
    }
}
