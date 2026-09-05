package com.tasirin.httpdownloadmanager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryFolderFilterTest {

    private val root = "/storage/emulated/0"
    private val folder = "$root/DCIM/Camera"

    @Test
    fun `tanpa filter folder - semua item tampil`() {
        assertTrue(MediaLibrary.isInGalleryFolders("/x/a.mp4", null, root, emptyList()))
        assertTrue(MediaLibrary.isInGalleryFolders(null, null, root, emptyList()))
    }

    @Test
    fun `path absolut - file di folder atau subfolder cocok`() {
        assertTrue(MediaLibrary.isInGalleryFolders("$folder/VID.mp4", null, root, listOf(folder)))
        assertTrue(MediaLibrary.isInGalleryFolders("$folder/sub/VID.mp4", null, root, listOf(folder)))
        assertTrue(MediaLibrary.isInGalleryFolders(folder, null, root, listOf(folder)))
    }

    @Test
    fun `path absolut - di luar folder terpilih ditolak`() {
        assertFalse(MediaLibrary.isInGalleryFolders("$root/Movies/VID.mp4", null, root, listOf(folder)))
        assertFalse(MediaLibrary.isInGalleryFolders("$root/Dcim/VID.mp4", null, root, listOf(folder)))
    }

    @Test
    fun `relative path MediaStore - cocok walau DATA null`() {
        // Android 11+: DATA bisa null, RELATIVE_PATH = "DCIM/Camera/"
        assertTrue(MediaLibrary.isInGalleryFolders(null, "DCIM/Camera/VID.mp4", root, listOf(folder)))
        assertTrue(MediaLibrary.isInGalleryFolders(null, "DCIM/Camera/sub/VID.mp4", root, listOf(folder)))
        assertTrue(MediaLibrary.isInGalleryFolders(null, "DCIM/Camera/", root, listOf(folder)))
    }

    @Test
    fun `relative path MediaStore - di luar folder terpilih ditolak`() {
        assertFalse(MediaLibrary.isInGalleryFolders(null, "Movies/VID.mp4", root, listOf(folder)))
        assertFalse(MediaLibrary.isInGalleryFolders(null, "Download/VID.mp4", root, listOf(folder)))
    }

    @Test
    fun `entry tanpa path sama sekali - ditolak saat filter aktif`() {
        // Bug lama: SAF/MediaStore tanpa DATA ikut tampil walau di luar pilihan.
        assertFalse(MediaLibrary.isInGalleryFolders(null, null, root, listOf(folder)))
    }

    @Test
    fun `pilih seluruh root - semua relative path cocok`() {
        assertTrue(MediaLibrary.isInGalleryFolders(null, "Anything/VID.mp4", root, listOf(root)))
        assertTrue(MediaLibrary.isInGalleryFolders("$root/Anything/VID.mp4", null, root, listOf(root)))
    }

    @Test
    fun `root eksternal berbeda - relative path tetap relatif ke root itu`() {
        val otherRoot = "/storage/9999-ABCD"
        val otherFolder = "$otherRoot/DCIM/Camera"
        assertTrue(MediaLibrary.isInGalleryFolders(null, "DCIM/Camera/VID.mp4", otherRoot, listOf(otherFolder)))
        assertFalse(MediaLibrary.isInGalleryFolders(null, "Movies/VID.mp4", otherRoot, listOf(otherFolder)))
    }
}
