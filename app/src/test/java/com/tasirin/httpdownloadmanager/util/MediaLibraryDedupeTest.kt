package com.tasirin.httpdownloadmanager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Issue #515: kunci dedupe galeri harus menyatukan entri MediaStore
 *  ber-DATA null (tanpa filePath, hanya relativePath + nama) dengan
 *  pasangan filesystem-nya (filePath absolut di bawah root galeri). */
class MediaLibraryDedupeTest {

    private val root = "/storage/emulated/0"

    @Test
    fun `baris MediaStore DATA null menyatu dengan pasangan filesystem`() {
        val dariMediaStore = MediaLibrary.kunciDedupeGaleri(
            filePath = null,
            relativePath = "Movies/",
            name = "film.mp4",
            contentUri = "content://media/external/video/media/42",
            token = "u:42",
            galleryRoot = root
        )
        val dariFilesystem = MediaLibrary.kunciDedupeGaleri(
            filePath = "/storage/emulated/0/Movies/film.mp4",
            relativePath = null,
            name = "film.mp4",
            contentUri = null,
            token = "f:/storage/emulated/0/Movies/film.mp4",
            galleryRoot = root
        )
        assertEquals(dariFilesystem, dariMediaStore)
    }

    @Test
    fun `berkas berbeda tetap berbeda kunci`() {
        val a = MediaLibrary.kunciDedupeGaleri(
            null, "Movies/", "a.mp4", "content://x/1", "u:1", root
        )
        val b = MediaLibrary.kunciDedupeGaleri(
            null, "Movies/", "b.mp4", "content://x/2", "u:2", root
        )
        assertNotEquals(a, b)
    }

    private fun entriFs(nama: String, durasi: Long = 0L) = MediaLibrary.MediaEntry(
        name = nama, size = 100L, modified = 1L, isVideo = true,
        token = "f:/storage/emulated/0/Movies/$nama",
        filePath = "/storage/emulated/0/Movies/$nama",
        durationMs = durasi
    )

    private fun entriMs(nama: String, durasi: Long) = MediaLibrary.MediaEntry(
        name = nama, size = 100L, modified = 1L, isVideo = true,
        token = "u:42",
        contentUri = "content://media/external/video/media/42",
        relativePath = "Movies/",
        durationMs = durasi
    )

    private fun kunci(e: MediaLibrary.MediaEntry) = MediaLibrary.kunciDedupeGaleri(
        e.filePath, e.relativePath, e.name, e.contentUri, e.token, root,
        volumeName = e.volumeName
    )

    @Test
    fun `dedupeGaleri - durasi MediaStore diadopsi entri filesystem yang lebih dulu`() {
        // Issue #1423: ekspektasi dibuktikan port-1423.py (port 1:1).
        // Kunci kedua entri ini sama (kasus uji pertama di kelas ini).
        val hasil = MediaLibrary.dedupeGaleri(
            listOf(entriFs("film.mp4"), entriMs("film.mp4", 5000L)), ::kunci
        )
        assertEquals(1, hasil.size)
        // Identitas entri pertama (token filesystem) dipertahankan,
        // durasinya diadopsi dari baris MediaStore.
        assertEquals("f:/storage/emulated/0/Movies/film.mp4", hasil[0].token)
        assertEquals(5000L, hasil[0].durationMs)
    }

    @Test
    fun `dedupeGaleri - entri pertama berdurasi menang utuh`() {
        val hasil = MediaLibrary.dedupeGaleri(
            listOf(entriMs("film.mp4", 5000L), entriFs("film.mp4")), ::kunci
        )
        assertEquals(1, hasil.size)
        assertEquals("u:42", hasil[0].token)
        assertEquals(5000L, hasil[0].durationMs)
        // Keduanya tanpa durasi -> tetap 0; keduanya berdurasi ->
        // durasi entri pertama yang dipertahankan.
        val nol = MediaLibrary.dedupeGaleri(
            listOf(entriFs("film.mp4"), entriMs("film.mp4", 0L)), ::kunci
        )
        assertEquals(0L, nol[0].durationMs)
        val dua = MediaLibrary.dedupeGaleri(
            listOf(entriFs("film.mp4", 3000L), entriMs("film.mp4", 5000L)), ::kunci
        )
        assertEquals(3000L, dua[0].durationMs)
    }

    @Test
    fun `dedupeGaleri - adopsi dari duplikat ketiga dan urutan kemunculan pertama`() {
        val hasil = MediaLibrary.dedupeGaleri(
            listOf(
                entriFs("a.mp4"), entriFs("b.mp4", 100L), entriMs("a.mp4", 7000L)
            ), ::kunci
        )
        assertEquals(listOf("a.mp4", "b.mp4"), hasil.map { it.name })
        assertEquals(7000L, hasil[0].durationMs)
        assertEquals(100L, hasil[1].durationMs)
    }

    @Test
    fun `berkas di akar volume ber-relativePath kosong menyatu dengan filesystem`() {
        // Issue #525: RELATIVE_PATH "" (atau "/") untuk berkas tepat
        // di akar volume — kuncinya harus sama dengan pasangan
        // filesystem <root>/<nama>, bukan jatuh ke uri:.
        val dariMediaStore = MediaLibrary.kunciDedupeGaleri(
            filePath = null,
            relativePath = "",
            name = "akar.mp4",
            contentUri = "content://media/external/video/media/7",
            token = "u:7",
            galleryRoot = root
        )
        val dariMediaStoreSlash = MediaLibrary.kunciDedupeGaleri(
            null, "/", "akar.mp4", "content://media/external/video/media/7", "u:7", root
        )
        val dariFilesystem = MediaLibrary.kunciDedupeGaleri(
            filePath = "/storage/emulated/0/akar.mp4",
            relativePath = null,
            name = "akar.mp4",
            contentUri = null,
            token = "f:1",
            galleryRoot = root
        )
        assertEquals(dariFilesystem, dariMediaStore)
        assertEquals(dariFilesystem, dariMediaStoreSlash)
    }

    @Test
    fun `volume sekunder tidak menyatu dengan berkas senama di primer`() {
        // Issue #532: relativePath relatif terhadap volume ASAL —
        // entri SD card ber-DATA null tidak boleh berkunci rel: yang
        // sama dengan berkas berbeda senama di akar primer.
        val dariSdCard = MediaLibrary.kunciDedupeGaleri(
            filePath = null,
            relativePath = "",
            name = "akar.mp4",
            contentUri = "content://media/external/volume_sd/video/media/9",
            token = "u:9",
            galleryRoot = root,
            volumeName = "external"
        )
        val dariFilesystemPrimer = MediaLibrary.kunciDedupeGaleri(
            filePath = "/storage/emulated/0/akar.mp4",
            relativePath = null,
            name = "akar.mp4",
            contentUri = null,
            token = "f:1",
            galleryRoot = root
        )
        assertNotEquals(dariFilesystemPrimer, dariSdCard)
        // Volume primer bernama eksplisit tetap menyatu seperti biasa.
        val dariPrimer = MediaLibrary.kunciDedupeGaleri(
            null, "", "akar.mp4", "content://x/7", "u:7", root,
            volumeName = "external_primary"
        )
        assertEquals(dariFilesystemPrimer, dariPrimer)
    }

    @Test
    fun `tanpa relativePath jatuh ke contentUri seperti sebelumnya`() {
        val kunci = MediaLibrary.kunciDedupeGaleri(
            null, null, "a.mp4", "content://x/1", "u:1", root
        )
        assertEquals("uri:content://x/1", kunci)
    }
}
