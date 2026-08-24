package com.tasirin.httpdownloadmanager.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FileNamesTest {

    @Test
    fun `safe - membuang traversal dan separator`() {
        assertEquals(".._.._rahasia.txt", FileNames.safe("../..\\rahasia.txt"))
        assertEquals("download", FileNames.safe("   "))
    }

    @Test
    fun `unique - nama bebas tidak diubah`() {
        val result = FileNames.unique("video.mp4") { false }
        assertEquals("video.mp4", result)
    }

    @Test
    fun `unique - nama bentrok diberi angka`() {
        val taken = setOf("video.mp4", "video (1).mp4")
        val result = FileNames.unique("video.mp4") { it in taken }
        assertEquals("video (2).mp4", result)
    }

    @Test
    fun `unique - tanpa ekstensi`() {
        val result = FileNames.unique("video") { it == "video" }
        assertEquals("video (1)", result)
    }

    @Test
    fun `unique - ekstensi bertitik banyak tetap dipertahankan`() {
        val result = FileNames.unique("arsip.tar.gz") { it == "arsip.tar.gz" }
        assertEquals("arsip.tar (1).gz", result)
    }

    @Test
    fun `unique - angka melompati yang sudah terpakai`() {
        val taken = setOf("f.txt", "f (1).txt", "f (3).txt")
        val result = FileNames.unique("f.txt") { it in taken }
        assertEquals("f (2).txt", result)
    }
}
