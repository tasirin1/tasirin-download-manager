package com.tasirin.httpdownloadmanager.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun zipToken_wajibHeader_tokenUnduhTanpaHeader() {
        // Penerbit token tak boleh dicetak CSRF tag-img.
        assertFalse(ServerSecurity.isStateChangeAllowed("GET", "/api/zip_token", null))
        assertTrue(ServerSecurity.isStateChangeAllowed("GET", "/api/zip_token", "XMLHttpRequest"))
        // ZIP tetap dijaga seperti sebelumnya.
        assertFalse(ServerSecurity.isStateChangeAllowed("GET", "/api/fs_zip?path=x", null))
        assertTrue(ServerSecurity.isHeaderRequiredGetUri("/api/zip_token"))
        assertTrue(ServerSecurity.isHeaderRequiredGetUri("/api/media_zip"))
        assertFalse(ServerSecurity.isHeaderRequiredGetUri("/api/downloads"))
    }

    @Test
    fun redactUri_tokenShareDanStreamPartDisamarkan_idBiasaTampil() {
        // /share/<token> setara password: wajib disensor di log/ekspor TXT.
        assertEquals("/share/<redacted>", ServerSecurity.redactUriForLog("/share/AbC123xYz"))
        assertEquals("/stream_part/<redacted>", ServerSecurity.redactUriForLog("/stream_part/qwerty9"))
        // ID item biasa bukan rahasia: tetap tampil untuk korelasi debug.
        assertEquals("/file/abc123", ServerSecurity.redactUriForLog("/file/abc123"))
        assertEquals("/api/downloads", ServerSecurity.redactUriForLog("/api/downloads"))
        assertEquals("", ServerSecurity.redactUriForLog(""))
    }

    @Test
    fun redactQuery_tokenUnduhDisamarkan_substringAman() {
        // zt ikut disamarkan (token sekali-pakai tak boleh bocor di log).
        assertEquals("?a=1&zt=<redacted>", ServerSecurity.redactQueryForLog("?a=1&zt=abc123"))
        // Delimiter wajib: mytoken= bukan nama param.
        assertEquals("?name=mytoken%3Dx", ServerSecurity.redactQueryForLog("?name=mytoken%3Dx"))
        assertEquals("?pin=<redacted>", ServerSecurity.redactQueryForLog("pin=1234"))
        // Tanda tangan di dalam nilai url= ikut disamarkan (#289): URL
        // bertanda tangan adalah kredensial pembawa untuk media itu.
        assertEquals(
            "?url=https://cdn.example/v.mp4?sig=<redacted>&hd=1",
            ServerSecurity.redactQueryForLog("?url=https://cdn.example/v.mp4?sig=ABC123&hd=1")
        )
        // Nilai rahasia kini ditelan sampai & mentah/akhir (#310):
        // parameter jinak sesudah sig di URL terenkode ikut tersamar
        // (dulu berhenti di %26 dan ekor nilai bisa bocor).
        assertEquals(
            "?url=https%3A%2F%2Fcdn.example%2Fv.mp4%3Fsig%3D<redacted>",
            ServerSecurity.redactQueryForLog(
                "?url=https%3A%2F%2Fcdn.example%2Fv.mp4%3Fsig%3DABC123%26hd%3D1"
            )
        )
        assertEquals(
            "?token=<redacted>",
            ServerSecurity.redactQueryForLog("?token=abc%26def")
        )
        // Nama rahasia tambahan (#310): lsig YouTube, oh Facebook,
        // kredensial AWS, token akses generik.
        assertEquals(
            "?url=https%3A%2F%2Fx%2Fv.mp4%3Flsig%3D<redacted>",
            ServerSecurity.redactQueryForLog(
                "?url=https%3A%2F%2Fx%2Fv.mp4%3Flsig%3DXYZ9%26foo%3Dbar"
            )
        )
        assertEquals(
            "?oh=<redacted>&e=1",
            ServerSecurity.redactQueryForLog("?oh=abcd1234&e=1")
        )
        assertEquals(
            "?x-amz-credential=<redacted>&y=1",
            ServerSecurity.redactQueryForLog("?x-amz-credential=AKID%2F2024&y=1")
        )
        assertEquals(
            "?access_token=<redacted>&y=1",
            ServerSecurity.redactQueryForLog("?access_token=XYZ&y=1")
        )
        assertEquals(
            "?session=<redacted>&a=1",
            ServerSecurity.redactQueryForLog("?session=zzz999&a=1")
        )
        // Kredensial userinfo di dalam url= ikut disamarkan (#913):
        // bentuk terenkode penuh maupun mentah; URL ber-port tanpa
        // userinfo tidak berubah.
        // Nilai harapan dibuktikan port Python 1:1 dari kedua regex.
        assertEquals(
            "?url=https%3A%2F%2F<redacted>%40dl.example%2Ffile.zip",
            ServerSecurity.redactQueryForLog(
                "?url=https%3A%2F%2Fuser%3As3cret%40dl.example%2Ffile.zip"
            )
        )
        assertEquals(
            "?url=https://<redacted>@dl.example/file.zip",
            ServerSecurity.redactQueryForLog("?url=https://user:s3cret@dl.example/file.zip")
        )
        assertEquals(
            "?url=https://dl.example:8080/file.zip",
            ServerSecurity.redactQueryForLog("?url=https://dl.example:8080/file.zip")
        )
        // Userinfo tanpa kata sandi KINI ikut tersamar (#918):
        // token-sebagai-username adalah kredensial penuh.
        assertEquals(
            "?url=https://<redacted>@dl.example/file.zip",
            ServerSecurity.redactQueryForLog("?url=https://user@dl.example/file.zip")
        )
        // Kata sandi memuat @: samarkan sampai @ terakhir di otoritas
        // (#918); @ di path dan nama berkas %40 tanpa userinfo utuh.
        assertEquals(
            "?url=https://<redacted>@host/f.zip",
            ServerSecurity.redactQueryForLog("?url=https://user:p@ss@host/f.zip")
        )
        assertEquals(
            "?url=https%3A%2F%2F<redacted>%40host%2Ff.zip",
            ServerSecurity.redactQueryForLog(
                "?url=https%3A%2F%2Fuser%3Ap%40ss%40host%2Ff.zip"
            )
        )
        assertEquals(
            "?url=https://<redacted>@github.com/x/y.zip",
            ServerSecurity.redactQueryForLog("?url=https://tok123@github.com/x/y.zip")
        )
        assertEquals(
            "?url=https://dl.example:8080/p@ge",
            ServerSecurity.redactQueryForLog("?url=https://dl.example:8080/p@ge")
        )
        assertEquals(
            "?url=https%3A%2F%2Fhost%2Fa%40b.zip",
            ServerSecurity.redactQueryForLog("?url=https%3A%2F%2Fhost%2Fa%40b.zip")
        )
        // Kata sandi terenkode memuat %2F (#921): otoritas diperpanjang
        // melewati %2F di dalam sandi sampai %2F sesudah %40 terakhir;
        // host dan port tetap tampil.
        assertEquals(
            "?url=https%3A%2F%2F<redacted>%40host%2Ff.zip",
            ServerSecurity.redactQueryForLog(
                "?url=https%3A%2F%2Fuser%3Ap%2Fq%40host%2Ff.zip"
            )
        )
        assertEquals(
            "?url=https%3A%2F%2F<redacted>%40host%3A8080%2Ff.zip",
            ServerSecurity.redactQueryForLog(
                "?url=https%3A%2F%2Fuser%3Ap%2Fq%40host%3A8080%2Ff.zip"
            )
        )
        assertEquals(
            "?url=https%3A%2F%2F<redacted>%40host",
            ServerSecurity.redactQueryForLog("?url=https%3A%2F%2Fuser%40host")
        )
        // Sandi memuat %40 SEBELUM %2F-nya (#922): penyamaran berjalan
        // sampai %40 terakhir, ekor sesudah garis miring tak bocor.
        assertEquals(
            "?url=https%3A%2F%2F<redacted>%40host%2Ff.zip",
            ServerSecurity.redactQueryForLog(
                "?url=https%3A%2F%2Fuser%3Ap%40q%2FLONGTAIL%40host%2Ff.zip"
            )
        )
        // Bukti titik dua kini dijangkar di segmen sebelum %2F pertama
        // (#923): %3A di query-dalam-nilai dan %40 di path tidak lagi
        // menyamarkan URL tanpa userinfo; titik dua port pun dijaga.
        assertEquals(
            "?url=https%3A%2F%2Fcdn.example%2Fv.mp4%3Ft%3D12%3A30%26mail%3Da%40b.co",
            ServerSecurity.redactQueryForLog(
                "?url=https%3A%2F%2Fcdn.example%2Fv.mp4%3Ft%3D12%3A30%26mail%3Da%40b.co"
            )
        )
        assertEquals(
            "?url=https%3A%2F%2Fhost%2Fdir%3Aname%2Fa%40b.zip",
            ServerSecurity.redactQueryForLog(
                "?url=https%3A%2F%2Fhost%2Fdir%3Aname%2Fa%40b.zip"
            )
        )
        assertEquals(
            "?url=https%3A%2F%2Fhost%3A8080%2Fp%40ge",
            ServerSecurity.redactQueryForLog(
                "?url=https%3A%2F%2Fhost%3A8080%2Fp%40ge"
            )
        )
        assertEquals(
            "?url=https%3A%2F%2F<redacted>%40github.com%2Fx.zip",
            ServerSecurity.redactQueryForLog(
                "?url=https%3A%2F%2Ftok%40github.com%2Fx.zip"
            )
        )
        assertEquals(
            "?api_key=<redacted>&b=2",
            ServerSecurity.redactQueryForLog("?api_key=K-123&b=2")
        )
        assertEquals(
            "?x-amz-signature=<redacted>&y=2",
            ServerSecurity.redactQueryForLog("?x-amz-signature=DEADBEEF&y=2")
        )
    }

    @Test
    fun isStateChangeAllowed_postWajibHeaderKhusus() {
        assertTrue(ServerSecurity.isStateChangeAllowed("GET", "/api/downloads", null))
        assertTrue(ServerSecurity.isStateChangeAllowed("POST", "/api/login", null))
        assertTrue(ServerSecurity.isStateChangeAllowed("post", "/api/action", "XMLHttpRequest"))
        assertFalse(ServerSecurity.isStateChangeAllowed("POST", "/api/action", null))
        assertFalse(ServerSecurity.isStateChangeAllowed("POST", "/api/upload", "form-data"))
    }

    @Test
    fun fastVarian_setaraKlasik_rootCanonicalSekali() {
        val r = root()
        val dalam = File(r, "sub/file.txt").absolutePath
        val luar = File(tmp.root, "lain.txt").absolutePath
        val roots = listOf(r)
        val canonical = ServerSecurity.canonicalRootPaths(roots)
        // Wrapper isPathAllowed/isBrowseableAncestor dihapus pada issue
        // #1017 (tak pernah dipanggil produksi); uji semantiknya kini
        // langsung atas varian Fast di test-test khusus di bawah.
        assertEquals(
            ServerSecurity.isRemoteDestinationAllowed("f:$dalam", roots),
            ServerSecurity.isRemoteDestinationAllowedFast("f:$dalam", canonical)
        )
        assertTrue(ServerSecurity.isPathAllowedFast(dalam, canonical))
        assertFalse(ServerSecurity.isPathAllowedFast(luar, canonical))
        // Cabang MediaStore (tanpa sentuh filesystem): segmen buruk ditolak.
        assertEquals(
            ServerSecurity.isMediaStorePathAllowed("Download/x.mp4", roots, false),
            ServerSecurity.isMediaStorePathAllowedFast("Download/x.mp4", canonical, false)
        )
        assertFalse(ServerSecurity.isMediaStorePathAllowedFast("a/../b", canonical, false))
        assertFalse(ServerSecurity.isMediaStorePathAllowedFast("a//b", canonical, false))
        assertEquals(
            ServerSecurity.isRemoteDestinationAllowed("m:Download/x.mp4", roots),
            ServerSecurity.isRemoteDestinationAllowedFast("m:Download/x.mp4", canonical)
        )
        assertFalse(ServerSecurity.isRemoteDestinationAllowedFast("m:a/../b", canonical))
    }

    @Test
    fun isPathAllowed_diDalamRoot_true() {
        val r = root()
        val child = File(r, "sub/file.txt")
        assertTrue(ServerSecurity.isPathAllowedFast(child.absolutePath, ServerSecurity.canonicalRootPaths(listOf(r))))
    }

    @Test
    fun isPathAllowed_rootItuSendiri_true() {
        val r = root()
        assertTrue(ServerSecurity.isPathAllowedFast(r.absolutePath, ServerSecurity.canonicalRootPaths(listOf(r))))
    }

    @Test
    fun isPathAllowed_siblingDiLuarRoot_false() {
        val r = root()
        val sibling = tmp.newFolder("lain")
        assertFalse(ServerSecurity.isPathAllowedFast(sibling.absolutePath, ServerSecurity.canonicalRootPaths(listOf(r))))
    }

    @Test
    fun isPathAllowed_parentTraversal_false() {
        val r = root()
        val luar = File(r.parentFile, "rahasia.txt")
        assertFalse(ServerSecurity.isPathAllowedFast(luar.absolutePath, ServerSecurity.canonicalRootPaths(listOf(r))))
    }

    @Test
    fun isPathAllowed_pathKosong_false() {
        val r = root()
        assertFalse(ServerSecurity.isPathAllowedFast("", ServerSecurity.canonicalRootPaths(listOf(r))))
        assertFalse(ServerSecurity.isPathAllowedFast("   ", ServerSecurity.canonicalRootPaths(listOf(r))))
    }

    @Test
    fun isPathAllowed_prefixMiripBukanAnak_false() {
        // root "storage/emulated/0" tidak boleh mengizinkan "storage/emulated/01".
        val base = tmp.newFolder("emulated", "0")
        val mirip = File(base.parentFile, "01/isi.txt")
        assertFalse(ServerSecurity.isPathAllowedFast(mirip.absolutePath, ServerSecurity.canonicalRootPaths(listOf(base))))
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
    fun safUri_hanyaDiDalamTreeBerizin() {
        val roots = listOf(
            "com.android.externalstorage.documents" to "/tree/primary%3ADownload"
        )
        assertTrue(
            ServerSecurity.isSafUriAllowed(
                "com.android.externalstorage.documents",
                "/tree/primary%3ADownload/document/primary%3ADownload/video.mp4",
                roots
            )
        )
        assertFalse(
            ServerSecurity.isSafUriAllowed(
                "com.android.externalstorage.documents",
                "/tree/primary%3ADocuments/video.mp4",
                roots
            )
        )
        assertFalse(
            ServerSecurity.isSafUriAllowed(
                "com.android.providers.downloads.documents",
                "/tree/downloads/document/video.mp4",
                roots
            )
        )
        assertFalse(ServerSecurity.isSafUriAllowed("com.android.externalstorage.documents", null, roots))
    }

    @Test
    fun isShareExpired_tepatWaktu_masihValid() {
        assertFalse(ServerSecurity.isShareExpired(5_000, 5_000))
        assertTrue(ServerSecurity.isShareExpired(4_999, 5_000))
        assertFalse(ServerSecurity.isShareExpired(10_000, 5_000))
    }

    @Test
    fun shareShortId_duaBelasHexSha256() {
        // Nilai harapan dihitung independen (SHA-256 token contoh,
        // 12 hex pertama) — id turunan, bukan potongan token itu sendiri.
        assertEquals(
            "bc3737d2f139",
            ServerSecurity.shareShortId("abcdef0123456789abcdef0123456789")
        )
        assertEquals(12, ServerSecurity.shareShortId("token-lain").length)
    }

    @Test
    fun isBrowseableAncestor_indukDariRoot_true() {
        val r = root()
        val induk = r.parentFile ?: return
        assertTrue(ServerSecurity.isBrowseableAncestorFast(induk.absolutePath, ServerSecurity.canonicalRootPaths(listOf(r))))
    }

    @Test
    fun isBrowseableAncestor_rootItuSendiri_false() {
        val r = root()
        // Root itu sendiri bukan "induk strict" — isPathAllowedFast yang menangani.
        assertFalse(ServerSecurity.isBrowseableAncestorFast(r.absolutePath, ServerSecurity.canonicalRootPaths(listOf(r))))
    }

    @Test
    fun isBrowseableAncestor_pathLuar_false() {
        val r = root()
        val luar = tmp.newFolder("lain")
        assertFalse(ServerSecurity.isBrowseableAncestorFast(luar.absolutePath, ServerSecurity.canonicalRootPaths(listOf(r))))
    }

    @Test
    fun isBrowseableAncestor_pathKosong_false() {
        val r = root()
        assertFalse(ServerSecurity.isBrowseableAncestorFast("", ServerSecurity.canonicalRootPaths(listOf(r))))
        assertFalse(ServerSecurity.isBrowseableAncestorFast("   ", ServerSecurity.canonicalRootPaths(listOf(r))))
    }

    @Test
    fun isBrowseableAncestor_anakDariInduk_true() {
        val r = root()
        // Induk dua tingkat: /tmp/.../root/sub — naik ke /tmp harus terdeteksi.
        val sub = File(r, "sub")
        val indukJauh = r.parentFile?.parentFile ?: return
        assertTrue(ServerSecurity.isBrowseableAncestorFast(indukJauh.absolutePath, ServerSecurity.canonicalRootPaths(listOf(sub))))
    }

    @Test
    fun isBrowseableAncestor_prefixMiripBukanInduk_false() {
        val r = root()
        val mirip = File(r.parentFile, r.name + "x")
        assertFalse(ServerSecurity.isBrowseableAncestorFast(mirip.absolutePath, ServerSecurity.canonicalRootPaths(listOf(r))))
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
    fun uploadVerifyToken_terikatIdDanWaktu() {
        val token = ServerSecurity.createUploadVerifyToken("upload-id", 1000L, "secret")
        assertTrue(
            ServerSecurity.isUploadVerifyTokenValid(token, "upload-id", 1000L, "secret")
        )
        assertFalse(
            ServerSecurity.isUploadVerifyTokenValid(token, "other-id", 1000L, "secret")
        )
        assertFalse(
            ServerSecurity.isUploadVerifyTokenValid(token, "upload-id", 1000L, "wrong")
        )
        assertFalse(
            ServerSecurity.isUploadVerifyTokenValid(token, "upload-id", 1001L, "secret")
        )
        assertFalse(
            ServerSecurity.isUploadVerifyTokenValid(null, "upload-id", 1000L, "secret")
        )
    }

    @Test
    fun tokenAntarJalur_tidakLolos() {
        // Secret + format kawat sama: tanpa domain HMAC, token partial
        // untuk ID yang sama lolos sebagai token verify-upload dan sebaliknya.
        val partial = ServerSecurity.createPartialToken("sama", 2000L, "secret")
        assertFalse(
            ServerSecurity.isUploadVerifyTokenValid(partial, "sama", 1999L, "secret")
        )
        val verify = ServerSecurity.createUploadVerifyToken("sama", 2000L, "secret")
        assertFalse(
            ServerSecurity.isPartialTokenValid(verify, "sama", 1999L, "secret")
        )
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
    fun partialToken_tandaLamaSha256Ditolak_hmacWajib() {
        // Token gaya lama sha256(payload:secret) wajib gagal setelah migrasi HMAC.
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("vid1.2000:secret".toByteArray(Charsets.UTF_8))
        val legacy = "vid1.2000." + digest.joinToString("") { "%02x".format(it) }
        assertFalse(ServerSecurity.isPartialTokenValid(legacy, "vid1", 1999L, "secret"))
        // HMAC baru round-trip tetap valid; ubahan 1 hex menggugurkan.
        val fresh = ServerSecurity.createPartialToken("vid1", 2000L, "secret")
        assertTrue(ServerSecurity.isPartialTokenValid(fresh, "vid1", 1999L, "secret"))
        val tampered = fresh.dropLast(1) + (if (fresh.last() == 'a') 'b' else 'a')
        assertFalse(ServerSecurity.isPartialTokenValid(tampered, "vid1", 1999L, "secret"))
    }

    @Test
    fun clampGalleryPage_mentah_diclamp() {
        assertEquals(0, ServerSecurity.clampGalleryPage(null))
        assertEquals(0, ServerSecurity.clampGalleryPage("abc"))
        assertEquals(0, ServerSecurity.clampGalleryPage("-3"))
        assertEquals(2, ServerSecurity.clampGalleryPage(" 2 "))
    }

    @Test
    fun galleryScanLimit_query_penuh_lainnya_duaHalaman() {
        assertEquals(3000, ServerSecurity.galleryScanLimit(0, 100, 3000, true))
        assertEquals(200, ServerSecurity.galleryScanLimit(0, 100, 3000, false))
        assertEquals(400, ServerSecurity.galleryScanLimit(2, 100, 3000, false))
        assertEquals(3000, ServerSecurity.galleryScanLimit(99, 100, 3000, false))
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

    @Test
    fun sessionCookieValue_hanyaNamaPas_dipakai() {
        assertEquals("abc123", ServerSecurity.sessionCookieValue("dm_pin=abc123", "dm_pin"))
        assertEquals("abc123", ServerSecurity.sessionCookieValue("other=1; dm_pin=abc123; x=2", "dm_pin"))
        assertEquals("abc123", ServerSecurity.sessionCookieValue("dm_pin=abc123; other=2", "dm_pin"))
        // Substring di nama/nilai cookie lain tidak boleh cocok.
        assertNull(ServerSecurity.sessionCookieValue("adm_pin=abc123", "dm_pin"))
        assertEquals("real", ServerSecurity.sessionCookieValue("adm_pin=junk; dm_pin=real", "dm_pin"))
        assertNull(ServerSecurity.sessionCookieValue("dm_pin=; other=1", "dm_pin"))
        assertNull(ServerSecurity.sessionCookieValue(null, "dm_pin"))
        assertNull(ServerSecurity.sessionCookieValue("dm_pin=abc", ""))
    }

    @Test
    fun escapeLike_backslashPersenUnderscore_diescape() {
        assertEquals("abc", ServerSecurity.escapeLike("abc"))
        assertEquals("100\\% hits", ServerSecurity.escapeLike("100% hits"))
        assertEquals("a\\_b", ServerSecurity.escapeLike("a_b"))
        assertEquals("a\\\\b", ServerSecurity.escapeLike("a\\b"))
        assertEquals("", ServerSecurity.escapeLike(""))
    }

    @Test
    fun isAllowedRootItself_hanyaPersisRoot() {
        val roots = listOf("/storage/emulated/0", "/storage/emulated/0/Download")
        assertTrue(ServerSecurity.isAllowedRootItself("/storage/emulated/0", roots))
        assertTrue(ServerSecurity.isAllowedRootItself("/storage/emulated/0/Download", roots))
        // Isi di dalam root BUKAN root itu sendiri: boleh dimanipulasi.
        assertFalse(ServerSecurity.isAllowedRootItself("/storage/emulated/0/Download/film.mp4", roots))
        assertFalse(ServerSecurity.isAllowedRootItself("/storage/emulated/0/Download2", roots))
        assertFalse(ServerSecurity.isAllowedRootItself("/storage/emulated/0", emptyList()))
    }

    @Test
    fun hasEscapingSymlink_pohonNormalDanSymlinkKeDalam() {
        val rootDir = root()
        val sub = File(rootDir, "sub").apply { mkdirs() }
        File(sub, "a.txt").writeText("x")
        val roots = listOf(rootDir.canonicalPath)
        assertFalse(ServerSecurity.hasEscapingSymlink(rootDir, roots))
        // Symlink direktori ke target yang masih di dalam root: aman.
        val inside = File(rootDir, "inside").apply { mkdirs() }
        java.nio.file.Files.createSymbolicLink(
            File(rootDir, "link-in").toPath(), inside.toPath()
        )
        assertFalse(ServerSecurity.hasEscapingSymlink(rootDir, roots))
    }

    @Test
    fun hasEscapingSymlink_symlinkDirektoriKeLuarRoot() {
        val rootDir = root()
        File(rootDir, "sub").mkdirs()
        val outside = tmp.newFolder("outside")
        File(outside, "penting.txt").writeText("jangan hapus")
        java.nio.file.Files.createSymbolicLink(
            File(rootDir, "sub/link-out").toPath(), outside.toPath()
        )
        assertTrue(
            ServerSecurity.hasEscapingSymlink(rootDir, listOf(rootDir.canonicalPath))
        )
    }

    @Test
    fun isFileNameValid_menolakSeparatorDanTraversal() {
        assertTrue(ServerSecurity.isFileNameValid("video.mp4"))
        assertTrue(ServerSecurity.isFileNameValid("file (1).mp4"))
        assertFalse(ServerSecurity.isFileNameValid(""))
        assertFalse(ServerSecurity.isFileNameValid("   "))
        assertFalse(ServerSecurity.isFileNameValid("a/b.mp4"))
        assertFalse(ServerSecurity.isFileNameValid("a\\b.mp4"))
        assertFalse(ServerSecurity.isFileNameValid(".."))
        assertFalse(ServerSecurity.isFileNameValid("../x.mp4"))
        assertFalse(ServerSecurity.isFileNameValid("..\\x.mp4"))
        assertFalse(ServerSecurity.isFileNameValid("video."))
        assertFalse(ServerSecurity.isFileNameValid("video "))
        assertFalse(ServerSecurity.isFileNameValid("..."))
    }
}
