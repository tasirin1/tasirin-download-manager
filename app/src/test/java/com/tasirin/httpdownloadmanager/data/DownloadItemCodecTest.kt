package com.tasirin.httpdownloadmanager.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadItemCodecTest {

    private fun fullItem() = DownloadItem(
        id = "abc-123",
        url = "https://example.com/video.mp4",
        fileName = "video.mp4",
        state = DownloadState.PAUSED,
        bytesDownloaded = 1_024,
        totalBytes = 10_000,
        error = "jaringan putus",
        contentUri = "content://media/123",
        filePath = "/storage/emulated/0/Download/video.mp4",
        addedAt = 1_700_000_000_000,
        finishedAt = 1_700_000_100_000,
        nameIsCustom = true,
        autoResume = true,
        username = "user",
        password = "pass",
        headers = "X-Token: abc\nReferer: https://x/",
        destination = "downloads",
        folderPath = "/sdcard/Musik",
        speedLimitKbps = 250,
        priority = 3,
        checksum = "md5:deadbeef",
        checksumVerified = true,
        mirrors = listOf("https://mirror1/file", "https://mirror2/file"),
        monitor = true,
        etag = "\"abc123\"",
        segments = listOf(
            DownloadSegment(index = 0, start = 0, end = 4_999, downloaded = 1_024),
            DownloadSegment(index = 1, start = 5_000, end = 9_999, downloaded = 0)
        ),
        speedBps = 12_345,
        etaSeconds = 7,
        preferredHeight = 720
    )

    @Test
    fun `roundtrip - semua field utuh`() {
        val item = fullItem()
        val decoded = DownloadItemCodec.decode(
            DownloadItemCodec.encode(listOf(item)), coerceActiveToPaused = false
        )
        assertEquals(1, decoded.size)
        // speedBps/etaSeconds sengaja transient (tidak dipersist, dihitung ulang
        // saat download berjalan) — konsisten dengan perilaku lama.
        assertEquals(item.copy(speedBps = 0, etaSeconds = 0), decoded[0])
    }

    @Test
    fun `roundtrip - daftar kosong`() {
        assertEquals(
            emptyList<DownloadItem>(),
            DownloadItemCodec.decode(DownloadItemCodec.encode(emptyList()))
        )
    }

    @Test
    fun `decode - JSON korup menghasilkan daftar kosong`() {
        assertEquals(emptyList<DownloadItem>(), DownloadItemCodec.decode("bukan json"))
        assertEquals(emptyList<DownloadItem>(), DownloadItemCodec.decode(""))
    }

    @Test
    fun `decode - satu entry korup tidak menghapus daftar`() {
        val raw = """
            [{"id":"1","url":"https://a/x","fileName":"x","state":"BOGUS","bytesDownloaded":0,"totalBytes":0},
             {"id":"2","url":"https://a/y","fileName":"y","state":"COMPLETED","bytesDownloaded":10,"totalBytes":10}]
        """.trimIndent()
        val decoded = DownloadItemCodec.decode(raw)
        assertEquals(1, decoded.size) // entry korup dilewati, bukan seluruh daftar
        assertEquals("2", decoded[0].id)
    }

    @Test
    fun `decode - entry null di tengah tidak menghapus daftar`() {
        val raw = """
            [{"id":"1","url":"https://a/x","fileName":"x","state":"COMPLETED","bytesDownloaded":1,"totalBytes":1},
             null,
             {"id":"3","url":"https://a/z","fileName":"z","state":"COMPLETED","bytesDownloaded":3,"totalBytes":3}]
        """.trimIndent()
        val decoded = DownloadItemCodec.decode(raw)
        assertEquals(2, decoded.size)
        assertEquals("1", decoded[0].id)
        assertEquals("3", decoded[1].id)
    }

    @Test
    fun `decode - state aktif dikonversi ke PAUSED saat restart`() {
        val item = DownloadItem(
            id = "1", url = "https://a/x", fileName = "x",
            state = DownloadState.DOWNLOADING, bytesDownloaded = 5, totalBytes = 100
        )
        val decoded = DownloadItemCodec.decode(DownloadItemCodec.encode(listOf(item)))
        assertEquals(DownloadState.PAUSED, decoded[0].state)
    }

    @Test
    fun `decode - state aktif tetap dipertahankan bila tanpa koersi`() {
        val item = DownloadItem(
            id = "1", url = "https://a/x", fileName = "x",
            state = DownloadState.PENDING, bytesDownloaded = 0, totalBytes = 0
        )
        val decoded = DownloadItemCodec.decode(
            DownloadItemCodec.encode(listOf(item)), coerceActiveToPaused = false
        )
        assertEquals(DownloadState.PENDING, decoded[0].state)
    }

    @Test
    fun `decode - field opsional yang hilang memakai nilai default`() {
        val raw = """
            [{"id":"1","url":"https://a/x","fileName":"x",
              "state":"PAUSED","bytesDownloaded":0,"totalBytes":0}]
        """.trimIndent()
        val decoded = DownloadItemCodec.decode(raw)
        assertEquals(1, decoded.size)
        val it = decoded[0]
        assertEquals("", it.checksum)
        assertTrue(it.mirrors.isEmpty())
        assertTrue(it.segments.isEmpty())
        assertEquals(false, it.monitor)
        assertEquals(0, it.addedAt)
    }

    @Test
    fun `encodeProgress - hanya menyimpan item aktif atau berprogres`() {
        val items = listOf(
            DownloadItem(id = "a", url = "https://a/x", fileName = "a", state = DownloadState.DOWNLOADING, bytesDownloaded = 10, totalBytes = 100),
            DownloadItem(id = "b", url = "https://a/y", fileName = "b", state = DownloadState.PAUSED, bytesDownloaded = 0, totalBytes = 100),
            DownloadItem(id = "c", url = "https://a/z", fileName = "c", state = DownloadState.COMPLETED, bytesDownloaded = 100, totalBytes = 100)
        )
        val o = JSONObject(DownloadItemCodec.encodeProgress(items))
        assertTrue(o.has("a"))
        assertTrue(!o.has("b"))
        assertTrue(!o.has("c"))
    }

    @Test
    fun `overlayProgress - nilai lebih besar diterapkan, lebih kecil diabaikan`() {
        val items = listOf(
            DownloadItem(id = "a", url = "https://a/x", fileName = "a", state = DownloadState.PAUSED, bytesDownloaded = 10, totalBytes = 100),
            DownloadItem(id = "b", url = "https://a/y", fileName = "b", state = DownloadState.PAUSED, bytesDownloaded = 50, totalBytes = 100)
        )
        val raw = """{"a":{"b":90,"t":100},"b":{"b":5,"t":100}}"""
        val out = DownloadItemCodec.overlayProgress(items, raw)
        assertEquals(90L, out[0].bytesDownloaded)
        assertEquals(50L, out[1].bytesDownloaded) // 5 < 50 -> diabaikan
    }
}
