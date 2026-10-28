package com.tasirin.httpdownloadmanager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StatFsTargetTest {

    @Test
    fun `non-f dan media store memakai volume default`() {
        assertNull(statFsDirForFolderPath(""))
        assertNull(statFsDirForFolderPath("m:Download"))
        assertNull(statFsDirForFolderPath("f:"))
        // Relatif tidak di-resolve (perilaku CWD tak tentu).
        assertNull(statFsDirForFolderPath("f:relatif/sub"))
    }

    @Test
    fun `dirForFolderPath membuang prefiks f dan menolak m serta kosong`() {
        assertNull(dirForFolderPath(""))
        assertNull(dirForFolderPath("m:Download"))
        assertNull(dirForFolderPath("f:"))
        assertEquals("/storage/emulated/0/Movies", dirForFolderPath("f:/storage/emulated/0/Movies")?.path)
        assertEquals("/storage/emulated/0/Movies", dirForFolderPath("/storage/emulated/0/Movies")?.path)
        // Relatif dipertahankan apa adanya (publish membuatkan direktorinya).
        assertEquals("relatif/sub", dirForFolderPath("f:relatif/sub")?.path)
    }

    @Test
    fun `direktori ada dipakai langsung, hilang naik ke induk ada`() {
        val tmp = Files.createTempDirectory("statfstarget").toFile()
        try {
            assertEquals(tmp.canonicalPath, statFsDirForFolderPath("f:" + tmp.absolutePath)?.canonicalPath)
            val hilang = File(tmp, "tak-ada/sub")
            assertEquals(tmp.canonicalPath, statFsDirForFolderPath("f:" + hilang.absolutePath)?.canonicalPath)
        } finally {
            runCatching { tmp.deleteRecursively() }
        }
    }
}
