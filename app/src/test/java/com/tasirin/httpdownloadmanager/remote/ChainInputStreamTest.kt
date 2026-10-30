package com.tasirin.httpdownloadmanager.remote

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Uji lompatan skip ChainInputStream (issue #1018): skip kini melompat
 *  per segmen (indeks + posisi channel), bukan membaca-buang byte.
 *  Isi tiap segmen adalah byte berurutan yang khas agar posisi akhir
 *  sesudah skip dapat dibuktikan dari byte yang terbaca. */
class ChainInputStreamTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun seg(name: String, bytes: ByteArray): File {
        val f = File(tmp.root, name)
        f.writeBytes(bytes)
        return f
    }

    // seg1: 0..99, seg2: 100..149 (total 150)
    private fun duaSegmen(): ChainInputStream = ChainInputStream(
        listOf(
            seg("a.part", ByteArray(100) { it.toByte() }),
            seg("b.part", ByteArray(50) { (100 + it).toByte() })
        )
    )

    @Test
    fun skip_diDalamSatuSegmen() {
        duaSegmen().use { s ->
            assertEquals(150L, s.totalLength)
            assertEquals(30L, s.skip(30))
            assertEquals(30, s.read())
        }
    }

    @Test
    fun skip_melintasiSegmen() {
        duaSegmen().use { s ->
            assertEquals(120L, s.skip(120))
            assertEquals(120, s.read())
        }
    }

    @Test
    fun skip_tepatDiBatasSegmen() {
        duaSegmen().use { s ->
            assertEquals(100L, s.skip(100))
            assertEquals(100, s.read())
        }
    }

    @Test
    fun skip_melebihiTotal_berhentiDiAkhir() {
        duaSegmen().use { s ->
            assertEquals(150L, s.skip(1000))
            assertEquals(-1, s.read())
        }
    }

    @Test
    fun skip_nolDanNegatif_tidakBergerak() {
        duaSegmen().use { s ->
            assertEquals(0L, s.skip(0))
            assertEquals(0L, s.skip(-5))
            assertEquals(0, s.read())
        }
    }

    @Test
    fun skip_sesudahBacaParsial() {
        duaSegmen().use { s ->
            val buf = ByteArray(10)
            assertEquals(10, s.read(buf, 0, 10))
            // 10 terbaca + 95 dilompati = posisi 105 (segmen 2 offset 5).
            assertEquals(95L, s.skip(95))
            assertEquals(105, s.read())
        }
    }

    @Test
    fun skip_segmenKosongDiTengah() {
        val s = ChainInputStream(
            listOf(
                seg("a.part", ByteArray(10) { it.toByte() }),
                seg("b.part", ByteArray(0)),
                seg("c.part", ByteArray(10) { (10 + it).toByte() })
            )
        )
        s.use {
            assertEquals(15L, it.skip(15))
            assertEquals(15, it.read())
        }
    }

    @Test
    fun bacaSesudahSkip_isiSisaUtuh() {
        duaSegmen().use { s ->
            assertEquals(90L, s.skip(90))
            val sisa = s.readBytes()
            assertEquals(60, sisa.size)
            assertEquals(90, sisa[0].toInt() and 0xFF)
            assertEquals(149, sisa[sisa.size - 1].toInt() and 0xFF)
        }
    }
}
