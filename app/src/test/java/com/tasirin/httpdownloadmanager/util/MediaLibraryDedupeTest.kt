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
    fun `tanpa relativePath jatuh ke contentUri seperti sebelumnya`() {
        val kunci = MediaLibrary.kunciDedupeGaleri(
            null, null, "a.mp4", "content://x/1", "u:1", root
        )
        assertEquals("uri:content://x/1", kunci)
    }
}
