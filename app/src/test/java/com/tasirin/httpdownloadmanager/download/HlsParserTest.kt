package com.tasirin.httpdownloadmanager.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HlsParserTest {

    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=1280000,RESOLUTION=1280x720,NAME="720p"
        https://cdn.example.com/videos/720.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1920x1080,NAME="1080p"
        /videos/1080.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=500000
        low.m3u8
    """.trimIndent()

    @Test
    fun `parse master - jumlah varian dan urutan bandwidth menurun`() {
        val variants = HlsParser.parseMaster(master, "https://cdn.example.com/videos/master.m3u8")!!
        assertEquals(3, variants.size)
        assertEquals("1080p", variants[0].name)
        assertEquals("720p", variants[1].name)
        assertEquals(2800L, variants[0].bandwidth / 1000)
        assertEquals(1280L, variants[1].bandwidth / 1000)
        assertEquals(500L, variants[2].bandwidth / 1000)
    }

    @Test
    fun `parse master - label dari RESOLUTION bila tanpa NAME`() {
        val variants = HlsParser.parseMaster(master, "https://cdn.example.com/videos/master.m3u8")!!
        assertEquals("500 kbps", variants[2].name)
    }

    @Test
    fun `parse master - URL relatif dilengkapi`() {
        val variants = HlsParser.parseMaster(master, "https://cdn.example.com/videos/master.m3u8")!!
        // absolute
        assertEquals("https://cdn.example.com/videos/720.m3u8", variants[1].url)
        // root-relative
        assertEquals("https://cdn.example.com/videos/1080.m3u8", variants[0].url)
        // sibling-relative
        assertEquals("https://cdn.example.com/videos/low.m3u8", variants[2].url)
    }

    @Test
    fun `bukan master playlist - mengembalikan null`() {
        val media = """
            #EXTM3U
            #EXTINF:10.0,
            seg1.ts
            #EXTINF:10.0,
            seg2.ts
        """.trimIndent()
        assertNull(HlsParser.parseMaster(media, "https://cdn.example.com/videos/media.m3u8"))
    }

    @Test
    fun `master kosong atau rusak - null`() {
        assertNull(HlsParser.parseMaster("", "https://cdn.example.com/a.m3u8"))
        assertNull(HlsParser.parseMaster("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n", "https://cdn.example.com/a.m3u8"))
    }

    @Test
    fun `resolveUrl - kasus absolut, root-relative, dan sibling`() {
        val base = "https://cdn.example.com/videos/master.m3u8"
        assertEquals("https://cdn.example.com/other.m3u8", HlsParser.resolveUrl(base, "/other.m3u8"))
        assertEquals("https://cdn.example.com/videos/low.m3u8", HlsParser.resolveUrl(base, "low.m3u8"))
        assertEquals(
            "https://cdn.example.com/videos/low.m3u8",
            HlsParser.resolveUrl(base, "https://cdn.example.com/videos/low.m3u8")
        )
        // base tanpa path -> relative menempel di akar
        assertEquals("https://cdn.example.com/low.m3u8", HlsParser.resolveUrl("https://cdn.example.com/master.m3u8", "low.m3u8"))
    }
}
