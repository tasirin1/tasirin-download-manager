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
    fun isStateChangeAllowed_postWajibHeaderKhusus() {
        assertTrue(ServerSecurity.isStateChangeAllowed("GET", "/api/downloads", null))
        assertTrue(ServerSecurity.isStateChangeAllowed("POST", "/api/login", null))
        assertTrue(ServerSecurity.isStateChangeAllowed("post", "/api/action", "XMLHttpRequest"))
        assertFalse(ServerSecurity.isStateChangeAllowed("POST", "/api/action", null))
        assertFalse(ServerSecurity.isStateChangeAllowed("POST", "/api/upload", "form-data"))
    }

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
        val r = root()
        assertFalse(ServerSecurity.isPathAllowed("", listOf(r)))
        assertFalse(ServerSecurity.isPathAllowed("   ", listOf(r)))
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
    fun isUploadIdAllowed_menolakSeparatorDanTraversal() {
        assertTrue(ServerSecurity.isUploadIdAllowed("Abcdef1234567890"))
        assertTrue(ServerSecurity.isUploadIdAllowed("safe-upload_id-123"))
        assertFalse(ServerSecurity.isUploadIdAllowed("../../etc/passwd"))
        assertFalse(ServerSecurity.isUploadIdAllowed("a/b/c"))
        assertFalse(ServerSecurity.isUploadIdAllowed("a\\b"))
        assertFalse(ServerSecurity.isUploadIdAllowed("short"))
        assertFalse(ServerSecurity.isUploadIdAllowed(""))
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

    @Test
    fun isBrowseableAncestor_indukDariRoot_true() {
        val r = root()
        val induk = r.parentFile ?: return
        assertTrue(ServerSecurity.isBrowseableAncestor(induk.absolutePath, listOf(r)))
    }

    @Test
    fun isBrowseableAncestor_rootItuSendiri_false() {
        val r = root()
        // Root itu sendiri bukan "induk strict" — isPathAllowed yang menangani.
        assertFalse(ServerSecurity.isBrowseableAncestor(r.absolutePath, listOf(r)))
    }

    @Test
    fun isBrowseableAncestor_pathLuar_false() {
        val r = root()
        val luar = tmp.newFolder("lain")
        assertFalse(ServerSecurity.isBrowseableAncestor(luar.absolutePath, listOf(r)))
    }

    @Test
    fun isBrowseableAncestor_pathKosong_false() {
        val r = root()
        assertFalse(ServerSecurity.isBrowseableAncestor("", listOf(r)))
        assertFalse(ServerSecurity.isBrowseableAncestor("   ", listOf(r)))
    }

    @Test
    fun isBrowseableAncestor_anakDariInduk_true() {
        val r = root()
        // Induk dua tingkat: /tmp/.../root/sub — naik ke /tmp harus terdeteksi.
        val sub = File(r, "sub")
        val indukJauh = r.parentFile?.parentFile ?: return
        assertTrue(ServerSecurity.isBrowseableAncestor(indukJauh.absolutePath, listOf(sub)))
    }

    @Test
    fun isBrowseableAncestor_prefixMiripBukanInduk_false() {
        val r = root()
        val mirip = File(r.parentFile, r.name + "x")
        assertFalse(ServerSecurity.isBrowseableAncestor(mirip.absolutePath, listOf(r)))
    }

    @Test
    fun isRemoteDestinationAllowed_blank_dan_mediaStore_true() {
        val r = root()
        assertTrue(ServerSecurity.isRemoteDestinationAllowed("", listOf(r)))
        assertTrue(ServerSecurity.isRemoteDestinationAllowed("m:", listOf(r)))
        assertTrue(ServerSecurity.isRemoteDestinationAllowed("m:Download/APK", listOf(r)))
    }

    @Test
    fun isRemoteDestinationAllowed_pathDiDalamRoot_true() {
        val r = root()
        assertTrue(
            ServerSecurity.isRemoteDestinationAllowed("f:${r.absolutePath}/sub", listOf(r))
        )
        assertTrue(ServerSecurity.isRemoteDestinationAllowed(r.absolutePath, listOf(r)))
    }

    @Test
    fun partialToken_validDanKedaluwarsa() {
        val token = ServerSecurity.createPartialToken("abc", 1000L, "secret")
        assertTrue(ServerSecurity.isPartialTokenValid(token, "abc", 999L, "secret"))
        assertFalse(ServerSecurity.isPartialTokenValid(token, "other", 999L, "secret"))
        assertFalse(ServerSecurity.isPartialTokenValid(token, "abc", 1000L, "wrong"))
        assertFalse(ServerSecurity.isPartialTokenValid(token, "abc", 1001L, "secret"))
    }

    @Test
    fun isRemoteDestinationAllowed_traversal_atau_luarRoot_false() {
        val r = root()
        val luar = tmp.newFolder("luar")
        assertFalse(ServerSecurity.isRemoteDestinationAllowed("m:Download/../Rahasia", listOf(r)))
        assertFalse(ServerSecurity.isRemoteDestinationAllowed("m:Download\\APK", listOf(r)))
        assertFalse(
            ServerSecurity.isRemoteDestinationAllowed("/data/data/other/app/files", listOf(r))
        )
        assertFalse(
            ServerSecurity.isRemoteDestinationAllowed(luar.absolutePath, listOf(r))
        )
    }

    @Test
    fun isMediaStorePathAllowed_hanya_diDalamRoot() {
        val root = tmp.newFolder("storage", "emulated", "0", "Download")
        val mediaRoot = tmp.root.resolve("storage/emulated/0").absolutePath
        assertTrue(ServerSecurity.isMediaStorePathAllowed("Download/sub", listOf(root), false, mediaRoot))
        assertFalse(ServerSecurity.isMediaStorePathAllowed("Movies/private.mp4", listOf(root), false, mediaRoot))
        assertFalse(ServerSecurity.isMediaStorePathAllowed("", listOf(root), false, mediaRoot))
        assertFalse(ServerSecurity.isMediaStorePathAllowed("../Rahasia", listOf(root), false, mediaRoot))
        assertFalse(ServerSecurity.isMediaStorePathAllowed("Download//x", listOf(root), false, mediaRoot))
        assertTrue(ServerSecurity.isMediaStorePathAllowed("Movies/private.mp4", listOf(root), true, mediaRoot))
    }
}
