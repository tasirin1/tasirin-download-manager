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
        preferredHeight = 720,
        totalBytesEstimated = true
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
    fun `roundtrip - flag totalBytesEstimated utuh`() {
        val item = DownloadItem(
            id = "hls-1",
            url = "https://x/playlist.m3u8",
            fileName = "a.ts",
            state = DownloadState.DOWNLOADING,
            bytesDownloaded = 100,
            totalBytes = 5_000_000,
            totalBytesEstimated = true
        )
        val decoded = DownloadItemCodec.decode(
            DownloadItemCodec.encode(listOf(item)), coerceActiveToPaused = false
        )
        assertEquals(item.copy(speedBps = 0, etaSeconds = 0), decoded[0])
    }

    @Test
    fun `decode - tanpa flag totalBytesEstimated = false`() {
        val raw = "[{\"id\":\"1\",\"url\":\"https://a/x\",\"fileName\":\"x\",\"state\":\"PAUSED\",\"bytesDownloaded\":0,\"totalBytes\":0}]"
        val item = DownloadItemCodec.decode(raw, coerceActiveToPaused = false)[0]
        assertTrue(!item.totalBytesEstimated)
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
    fun `encodeProgress - segmen ikut tersimpan menurut index`() {
        val item = DownloadItem(
            id = "a", url = "https://a/x", fileName = "a", state = DownloadState.DOWNLOADING,
            bytesDownloaded = 140, totalBytes = 200,
            segments = listOf(
                DownloadSegment(0, 0, 99, 40),
                DownloadSegment(1, 100, 199, 100)
            )
        )
        val o = JSONObject(DownloadItemCodec.encodeProgress(listOf(item)))
        val segArr = o.getJSONObject("a").getJSONArray("s")
        assertEquals(40L, segArr.getLong(0))
        assertEquals(100L, segArr.getLong(1))
    }

    @Test
    fun `overlayProgress - segmen diterapkan monoton dan dijepit panjang segmen`() {
        val items = listOf(
            DownloadItem(
                id = "a", url = "https://a/x", fileName = "a", state = DownloadState.PAUSED,
                bytesDownloaded = 110, totalBytes = 200,
                segments = listOf(
                    DownloadSegment(0, 0, 99, 10),
                    DownloadSegment(1, 100, 199, 100)
                )
            )
        )
        // s[0]=80 menaikkan 10 -> 80; s[1]=500 dijepit ke panjang segmen (100).
        val raw = "{\"a\":{\"b\":180,\"t\":200,\"s\":[80,500]}}"
        val out = DownloadItemCodec.overlayProgress(items, raw)
        assertEquals(80L, out[0].segments[0].downloaded)
        assertEquals(100L, out[0].segments[1].downloaded)
        // Nilai segmen lebih kecil diabaikan (tidak mundur).
        val raw2 = "{\"a\":{\"b\":180,\"t\":200,\"s\":[5,90]}}"
        val out2 = DownloadItemCodec.overlayProgress(items, raw2)
        assertEquals(10L, out2[0].segments[0].downloaded)
        assertEquals(100L, out2[0].segments[1].downloaded)
    }

    @Test
    fun `overlayProgress - format lama tanpa segmen tidak menyentuh segmen`() {
        val items = listOf(
            DownloadItem(
                id = "a", url = "https://a/x", fileName = "a", state = DownloadState.PAUSED,
                bytesDownloaded = 10, totalBytes = 200,
                segments = listOf(DownloadSegment(0, 0, 199, 10))
            )
        )
        val raw = "{\"a\":{\"b\":90,\"t\":200}}"
        val out = DownloadItemCodec.overlayProgress(items, raw)
        assertEquals(90L, out[0].bytesDownloaded)
        assertEquals(10L, out[0].segments[0].downloaded)
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

    @Test
    fun `overlayProgress - bytes basi tidak melebihi total (clamp persen)`() {
        val items = listOf(
            DownloadItem(id = "a", url = "https://a/x", fileName = "a", state = DownloadState.PAUSED, bytesDownloaded = 0, totalBytes = 100)
        )
        // Progres basi 500 byte dari sesi sebelum reset tidak boleh membuat persen >100%.
        val raw = "{\"a\":{\"b\":500,\"t\":100}}"
        val out = DownloadItemCodec.overlayProgress(items, raw)
        assertEquals(100L, out[0].bytesDownloaded)
        assertEquals(100L, out[0].totalBytes)
    }

    @Test
    fun `decode array terparse setara dengan decode string (satu parse load)`() {
        val item = DownloadItem(
            id = "1", url = "https://a/x", fileName = "x",
            state = DownloadState.PAUSED, bytesDownloaded = 7, totalBytes = 70
        )
        val raw = DownloadItemCodec.encode(listOf(item))
        val arr = org.json.JSONArray(raw)
        val viaArr = DownloadItemCodec.decode(arr)
        assertEquals(DownloadItemCodec.decode(raw), viaArr)
        assertEquals("1", viaArr[0].id)
        assertEquals(7L, viaArr[0].bytesDownloaded)
    }

    @Test
    fun `overlayProgress - bytes lama tidak mundur saat hanya total yang tumbuh`() {
        val items = listOf(
            DownloadItem(id = "a", url = "https://a/x", fileName = "a", state = DownloadState.PAUSED, bytesDownloaded = 100, totalBytes = 500)
        )
        val raw = "{\"a\":{\"b\":50,\"t\":1000}}"
        val out = DownloadItemCodec.overlayProgress(items, raw)
        assertEquals(100L, out[0].bytesDownloaded) // 50 tidak boleh menimpa mundur
        assertEquals(1000L, out[0].totalBytes)
    }
}
