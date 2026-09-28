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
