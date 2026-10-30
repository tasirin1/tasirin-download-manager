package com.tasirin.httpdownloadmanager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryFolderFilterTest {

    private val root = "/storage/emulated/0"
    private val folder = "$root/DCIM/Camera"

    // Menyusun jalur PRODUKSI persis: gerbang level scan (daftar kosong =
    // tanpa saringan) + allowedFoldersFor + inSelectedFoldersPrecomputed
    // yang dipakai scan per baris. Dulu uji memanggil wrapper
    // isInGalleryFolders yang tak pernah dijalankan produksi (issue #684).
    private fun tampil(
        dataPath: String?,
        relativePath: String?,
        externalRoot: String,
        selectedFolders: List<String>,
        volumeName: String? = null
    ): Boolean {
        if (selectedFolders.isEmpty()) return true
        return MediaLibrary.inSelectedFoldersPrecomputed(
            dataPath, relativePath,
            MediaLibrary.allowedFoldersFor(selectedFolders, externalRoot),
            volumeName
        )
    }

    @Test
    fun `tanpa filter folder - semua item tampil`() {
        assertTrue(tampil("/x/a.mp4", null, root, emptyList()))
        assertTrue(tampil(null, null, root, emptyList()))
    }

    @Test
    fun `volume sekunder - cabang relatif ditolak, cabang path absolut tetap sah`() {
        // Issue #538: relativePath baris SD card relatif terhadap
        // volume asalnya, bukan root primer — walau nama relatifnya
        // sama persis dengan folder terpilih, ia tidak boleh lolos
        // lewat cabang relatif. Primer bernama eksplisit tetap lolos.
        val movies = "$root/Movies"
        assertFalse(
            tampil(null, "Movies/", root, listOf(movies), volumeName = "external")
        )
        assertTrue(
            tampil(null, "Movies/", root, listOf(movies), volumeName = "external_primary")
        )
        // Baris volume sekunder ber-DATA terisi diputuskan path absolutnya.
        assertTrue(
            tampil("$movies/VID.mp4", null, root, listOf(movies), volumeName = "external")
        )
    }

    @Test
    fun `path absolut - file di folder atau subfolder cocok`() {
        assertTrue(tampil("$folder/VID.mp4", null, root, listOf(folder)))
        assertTrue(tampil("$folder/sub/VID.mp4", null, root, listOf(folder)))
        assertTrue(tampil(folder, null, root, listOf(folder)))
    }

    @Test
    fun `path absolut - di luar folder terpilih ditolak`() {
        assertFalse(tampil("$root/Movies/VID.mp4", null, root, listOf(folder)))
        assertFalse(tampil("$root/Dcim/VID.mp4", null, root, listOf(folder)))
    }

    @Test
    fun `path absolut - beda huruf besar masih cocok (SQLite LIKE & FAT)`() {
        // Android 6 MediaStore DATA kadang beda kapitalisasi dari folder
        // yang diketik user; SQLite LIKE sendiri case-insensitive.
        assertTrue(tampil("$root/dcim/camera/VID.mp4", null, root, listOf(folder)))
        assertTrue(tampil("$root/DCIM/Camera/Sub/VID.mp4", null, root, listOf(folder.lowercase())))
    }

    @Test
    fun `path absolut - alias root primer cocok dengan folder asli`() {
        // Android 5-6: DATA kadang memakai symlink /sdcard atau /mnt/sdcard
        // padahal folder galeri disimpan dengan /storage/emulated/0.
        assertTrue(tampil("/sdcard/DCIM/Camera/VID.mp4", null, root, listOf(folder)))
        assertTrue(tampil("/mnt/sdcard/DCIM/Camera/sub/VID.mp4", null, root, listOf(folder)))
        assertTrue(tampil("/storage/emulated/legacy/DCIM/Camera/VID.mp4", null, root, listOf(folder)))
    }

    @Test
    fun `path absolut - volume lain dengan nama folder sama tidak ikut`() {
        // Folder galeri hanya bisa di bawah root primer; jangan menyeret
        // video dari USB/SD eksternal yang kebetulan punya folder senama.
        assertFalse(tampil("/storage/9999-ABCD/DCIM/Camera/VID.mp4", null, root, listOf(folder)))
    }

    @Test
    fun `relative path MediaStore - cocok walau DATA null`() {
        // Android 11+: DATA bisa null, RELATIVE_PATH = "DCIM/Camera/"
        assertTrue(tampil(null, "DCIM/Camera/VID.mp4", root, listOf(folder)))
        assertTrue(tampil(null, "DCIM/Camera/sub/VID.mp4", root, listOf(folder)))
        assertTrue(tampil(null, "DCIM/Camera/", root, listOf(folder)))
    }

    @Test
    fun `relative path MediaStore - di luar folder terpilih ditolak`() {
        assertFalse(tampil(null, "Movies/VID.mp4", root, listOf(folder)))
        assertFalse(tampil(null, "Download/VID.mp4", root, listOf(folder)))
    }

    @Test
    fun `relative path MediaStore - beda huruf besar masih cocok`() {
        assertTrue(tampil(null, "dcim/camera/VID.mp4", root, listOf(folder)))
    }

    @Test
    fun `entry tanpa path sama sekali - ditolak saat filter aktif`() {
        // Bug lama: SAF/MediaStore tanpa DATA ikut tampil walau di luar pilihan.
        assertFalse(tampil(null, null, root, listOf(folder)))
    }

    @Test
    fun `pilih seluruh root - semua relative path cocok`() {
        assertTrue(tampil(null, "Anything/VID.mp4", root, listOf(root)))
        assertTrue(tampil("$root/Anything/VID.mp4", null, root, listOf(root)))
    }

    @Test
    fun `prefix mirip tapi beda folder - ditolak (batas slash)`() {
        // /DCIM/Camera2 bukan subfolder /DCIM/Camera; tanpa cek batas,
        // startsWith polos ikut mencocokkan.
        assertFalse(tampil("${folder}2/VID.mp4", null, root, listOf(folder)))
        assertFalse(tampil("${folder}X/VID.mp4", null, root, listOf(folder)))
        assertFalse(tampil(null, "DCIM/Camera2/VID.mp4", root, listOf(folder)))
    }

    @Test
    fun `alias root primer - beda huruf besar tetap cocok`() {
        assertTrue(tampil("/SDCARD/DCIM/Camera/VID.mp4", null, root, listOf(folder)))
    }

    @Test
    fun `root eksternal berbeda - relative path tetap relatif ke root itu`() {
        val otherRoot = "/storage/9999-ABCD"
        val otherFolder = "$otherRoot/DCIM/Camera"
        assertTrue(tampil(null, "DCIM/Camera/VID.mp4", otherRoot, listOf(otherFolder)))
        assertFalse(tampil(null, "Movies/VID.mp4", otherRoot, listOf(otherFolder)))
    }
}
