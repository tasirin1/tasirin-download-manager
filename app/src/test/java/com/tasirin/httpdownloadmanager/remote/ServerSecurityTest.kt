package com.tasirin.httpdownloadmanager.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ServerSecurityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun root(): File = tmp.newFolder("root")

    @Test
    fun isPathAllowed_diDalamRoot_true() {
        val r = root()
        val child = File(r, "sub/file.txt")
        assertTrue(ServerSecurity.isPathAllowed(child.absolutePath, listOf(r)))
    }

    @Test
    fun isPathAllowed_rootItuSendiri_true() {
        val r = root()
        assertTrue(ServerSecurity.isPathAllowed(r.absolutePath, listOf(r)))
    }

    @Test
    fun isPathAllowed_siblingDiLuarRoot_false() {
        val r = root()
        val sibling = tmp.newFolder("lain")
        assertFalse(ServerSecurity.isPathAllowed(sibling.absolutePath, listOf(r)))
    }

    @Test
    fun isPathAllowed_parentTraversal_false() {
        val r = root()
        val luar = File(r.parentFile, "rahasia.txt")
        assertFalse(ServerSecurity.isPathAllowed(luar.absolutePath, listOf(r)))
    }

    @Test
    fun isPathAllowed_pathKosong_false() {
        assertFalse(ServerSecurity.isPathAllowed("", listOf(root())))
        assertFalse(ServerSecurity.isPathAllowed("   ", listOf(root())))
    }

    @Test
    fun isPathAllowed_prefixMiripBukanAnak_false() {
        // root "storage/emulated/0" tidak boleh mengizinkan "storage/emulated/01".
        val base = tmp.newFolder("emulated", "0")
        val mirip = File(base.parentFile, "01/isi.txt")
        assertFalse(ServerSecurity.isPathAllowed(mirip.absolutePath, listOf(base)))
    }

    @Test
    fun isPinLocked_boundary() {
        assertTrue(ServerSecurity.isPinLocked(1000, 2000))
        assertFalse(ServerSecurity.isPinLocked(2000, 2000))
        assertFalse(ServerSecurity.isPinLocked(3000, 2000))
    }

    @Test
    fun pinLockUntilAfter_belumAmbang_0() {
        assertEquals(0L, ServerSecurity.pinLockUntilAfter(4, 5, 30_000, 1234))
    }

    @Test
    fun pinLockUntilAfter_tepatAmbang_lock() {
        assertEquals(1_234 + 30_000, ServerSecurity.pinLockUntilAfter(5, 5, 30_000, 1_234))
    }

    @Test
    fun pinLockUntilAfter_melebihiAmbang_lock() {
        assertEquals(50L + 10_000, ServerSecurity.pinLockUntilAfter(7, 5, 10_000, 50L))
    }

    @Test
    fun isChunkOffsetAllowed_boundary() {
        val max = 50L * 1024 * 1024
        assertTrue(ServerSecurity.isChunkOffsetAllowed(0, max))
        assertTrue(ServerSecurity.isChunkOffsetAllowed(max, max))
        assertFalse(ServerSecurity.isChunkOffsetAllowed(-1, max))
        assertFalse(ServerSecurity.isChunkOffsetAllowed(max + 1, max))
    }

    @Test
    fun isShareExpired_tepatWaktu_masihValid() {
        assertFalse(ServerSecurity.isShareExpired(5_000, 5_000))
        assertTrue(ServerSecurity.isShareExpired(4_999, 5_000))
        assertFalse(ServerSecurity.isShareExpired(10_000, 5_000))
    }
}
