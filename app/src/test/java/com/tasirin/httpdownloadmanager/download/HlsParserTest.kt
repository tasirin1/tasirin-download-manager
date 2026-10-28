package com.tasirin.httpdownloadmanager.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `tag STREAM-INF tanpa URI tidak menelan varian berikut`() {
        val malformed = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1280000,NAME="720p"
            #EXT-X-STREAM-INF:BANDWIDTH=2800000,NAME="1080p"
            https://cdn.example.com/videos/1080.m3u8
        """.trimIndent()
        val variants = HlsParser.parseMaster(malformed, "https://cdn.example.com/videos/master.m3u8")!!
        assertEquals(1, variants.size)
        assertEquals("1080p", variants[0].name)
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
        // URI absolut berskema asing dikembalikan apa adanya, BUKAN
        // ditempel ke direktori basis menjadi URL rusak yang lolos
        // saringan http di pemanggil (issue #452).
        assertEquals("ftp://cdn.example.com/seg.ts", HlsParser.resolveUrl(base, "ftp://cdn.example.com/seg.ts"))
        assertEquals("rtmp://cdn.example.com/s", HlsParser.resolveUrl(base, "rtmp://cdn.example.com/s"))
        // Skema tanpa "//" juga absolut (issue #464): dikembalikan utuh,
        // bukan ditempel ke direktori basis.
        assertEquals("data:application/octet-stream;base64,AAAA", HlsParser.resolveUrl(base, "data:application/octet-stream;base64,AAAA"))
        assertEquals("urn:isbn:123", HlsParser.resolveUrl(base, "urn:isbn:123"))
        // Titik dua SESUDAH "/" pertama tetap nama relatif yang sah.
        assertEquals("https://cdn.example.com/videos/dir/a:b.ts", HlsParser.resolveUrl(base, "dir/a:b.ts"))
    }

    @Test
    fun `resolveUrl - query basis tidak merusak direktori`() {
        assertEquals(
            "https://cdn.example.com/videos/low.m3u8",
            HlsParser.resolveUrl("https://cdn.example.com/videos/master.m3u8?token=abc", "low.m3u8")
        )
        assertEquals(
            "https://cdn.example.com/videos/low.m3u8",
            HlsParser.resolveUrl("https://cdn.example.com/videos/master.m3u8?next=a/b", "low.m3u8")
        )
    }

    @Test
    fun `resolveUrl - skema kapital dan protocol-relative`() {
        val base = "https://cdn.example.com/videos/master.m3u8"
        assertEquals(
            "HTTPS://cdn.example.com/videos/low.m3u8",
            HlsParser.resolveUrl(base, "HTTPS://cdn.example.com/videos/low.m3u8")
        )
        assertEquals(
            "https://cdn.example.com/videos/low.m3u8",
            HlsParser.resolveUrl(base, "//cdn.example.com/videos/low.m3u8")
        )
        assertEquals(
            "http://cdn.example.com/low.m3u8",
            HlsParser.resolveUrl("http://cdn.example.com/master.m3u8", "//cdn.example.com/low.m3u8")
        )
    }

    @Test
    fun `parse master - frameRate dibaca dari atribut FRAME-RATE`() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1072240,RESOLUTION=480x854,FRAME-RATE=30,CODECS="avc1.4D401F,mp4a.40.2"
            https://cdn.example.com/videos/480.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3789534,RESOLUTION=720x1280,FRAME-RATE=60.0,CODECS="avc1.4D4020,mp4a.40.2"
            https://cdn.example.com/videos/720.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=291706,RESOLUTION=240x426,CODECS="avc1.4D4015,mp4a.40.2"
            https://cdn.example.com/videos/240.m3u8
        """.trimIndent()
        val variants = HlsParser.parseMaster(master, "https://cdn.example.com/videos/master.m3u8")!!
        assertEquals(60, variants[0].frameRate)
        assertEquals(30, variants[1].frameRate)
        assertEquals(0, variants.minByOrNull { it.bandwidth }!!.frameRate)
        assertEquals(720, variants[0].height)
        assertEquals(480, variants[1].height)
        assertEquals(240, variants.minByOrNull { it.bandwidth }!!.height)
    }

    @Test
    fun `parse master - varian ber-URI asing non-http dibuang`() {
        val body = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=900000
            ftp://cdn.example.com/videos/alt.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=500000
            low.m3u8
        """.trimIndent()
        val variants = HlsParser.parseMaster(body, "https://cdn.example.com/videos/master.m3u8")!!
        assertEquals(1, variants.size)
        assertEquals(500L, variants[0].bandwidth / 1000)
    }

    @Test
    fun `parse audio renditions - rendisi ber-URI asing non-http dibuang`() {
        val body = """
            #EXTM3U
            #EXT-X-MEDIA:URI="ftp://cdn.example.com/aud.m3u8",TYPE=AUDIO,GROUP-ID="aud",NAME="Asing"
            #EXT-X-MEDIA:URI="audio.m3u8",TYPE=AUDIO,GROUP-ID="aud",NAME="Default",DEFAULT=YES
        """.trimIndent()
        val renditions = HlsParser.parseAudioRenditions(body, "https://cdn.example.com/videos/master.m3u8")
        assertEquals(1, renditions.size)
        assertEquals("Default", renditions[0].name)
    }

    @Test
    fun `parse master - audioGroupId dari atribut AUDIO`() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:URI="audio.m3u8",TYPE=AUDIO,GROUP-ID="aud",NAME="Default",DEFAULT=YES
            #EXT-X-STREAM-INF:BANDWIDTH=1280000,RESOLUTION=1280x720,NAME="720p",AUDIO="aud"
            https://cdn.example.com/videos/720.m3u8
        """.trimIndent()
        val variants = HlsParser.parseMaster(master, "https://cdn.example.com/videos/master.m3u8")!!
        assertEquals("aud", variants[0].audioGroupId)
    }

    @Test
    fun `parseMasterLenient - label tanpa RESOLUTION jatuh ke bandwidth lalu nomor urut`() {
        // Tanpa RESOLUTION label "$h p" memburuk jadi "0 p" untuk semua
        // varian (issue #476): jatuh ke kbps seperti parseMaster, lalu
        // ke nomor urut bila bandwidth juga nol.
        val body = "#EXTM3U\n" +
            "#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=800000\n" +
            "https://cdn.example.com/videos/a.m3u8\n" +
            "#EXT-X-STREAM-INF:PROGRAM-ID=1\n" +
            "https://cdn.example.com/videos/b.m3u8\n"
        val variants = HlsParser.parseMasterLenient(body, "https://cdn.example.com/videos/master.m3u8")!!
        assertEquals(2, variants.size)
        // Terurut menurun menurut bandwidth: yang ber-bandwidth di depan.
        assertEquals("800 kbps", variants[0].name)
        assertEquals("Variant 2", variants[1].name)
    }

    @Test
    fun `parseMasterLenient - fallback absolut saat parseMaster gagal`() {
        val body = "#EXTM3U\n" +
            "#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=800000,RESOLUTION=640x360,CODECS=\"avc1.4D401E\",FRAME-RATE=30\n" +
            "https://cdn.example.com/videos/360.m3u8\n" +
            "#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=1600000,RESOLUTION=1280x720\n" +
            "https://cdn.example.com/videos/720.m3u8\n"
        val variants = HlsParser.parseMasterLenient(body, "https://cdn.example.com/videos/master.m3u8")!!
        assertEquals(2, variants.size)
        assertEquals(1600000L, variants[0].bandwidth)
        assertEquals(720, variants[0].height)
        assertEquals("720 p", variants[0].name)
        assertEquals(30, variants[1].frameRate)
        assertEquals("avc1.4D401E", variants[1].codecs)
        assertEquals("https://cdn.example.com/videos/360.m3u8", variants[1].url)
    }

    @Test
    fun `parseMasterLenient - portrait memakai dimensi terkecil seperti parseMaster`() {
        // 720x1280 portrait wajib tercatat height 720 (bukan 1280 mentah)
        // agar peringkat kedekatan resolusi setara jalur strict (#393).
        val body = "#EXTM3U\n" +
            "#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=1600000,RESOLUTION=720x1280\n" +
            "https://cdn.example.com/videos/portrait.m3u8\n"
        val variants = HlsParser.parseMasterLenient(body, "https://cdn.example.com/videos/master.m3u8")!!
        assertEquals(720, variants[0].height)
        assertEquals("720 p", variants[0].name)
    }

    @Test
    fun `parseMasterLenient - relatif dan baris rusak diabaikan`() {
        val body = "#EXTM3U\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=500000\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=854x480\n" +
            "/rel/480.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=100\n" +
            "relative-tanpa-slash.m3u8\n"
        val variants = HlsParser.parseMasterLenient(body, "https://cdn.example.com/videos/master.m3u8")
        assertEquals(1, variants!!.size)
        assertEquals("https://cdn.example.com/rel/480.m3u8", variants[0].url)
    }

    @Test
    fun `parseDirectMedia - satu lintasan segmen plus durasi`() {
        val body = "#EXTM3U\n" +
            "#EXT-X-TARGETDURATION:10\n" +
            "#EXTINF:9.5,\n" +
            "seg1.ts\n" +
            "#EXTINF:10.0,judul\n" +
            "/media/seg2.ts\n" +
            "#EXT-X-KEY:METHOD=AES-128,URI=\"key.key\"\n" +
            "seg3.ts\n"
        val direct = HlsParser.parseDirectMedia(body, "https://cdn.example.com/videos/media.m3u8")
        assertEquals(3, direct.segments.size)
        assertEquals("https://cdn.example.com/videos/seg1.ts", direct.segments[0])
        assertEquals("https://cdn.example.com/media/seg2.ts", direct.segments[1])
        // Sejajar indeks (issue #394): segmen tanpa EXTINF (seg3) mendapat
        // durasi 0, BUKAN menggeser durasi segmen lain.
        assertEquals(3, direct.durationsUs.size)
        assertEquals(9_500_000L, direct.durationsUs[0])
        assertEquals(10_000_000L, direct.durationsUs[1])
        assertEquals(0L, direct.durationsUs[2])
    }

    @Test
    fun `parseDirectMedia - segmen ber-skema kapital diterima`() {
        // Skema URI tak peka huruf (RFC 3986): resolveUrl mempertahankan
        // HTTPS:// apa adanya, jadi saringan penerimaan wajib ignoreCase
        // juga — kalau tidak segmen sah ini dibuang diam-diam (issue #463).
        val body = "#EXTM3U\n" +
            "#EXTINF:4.0,\n" +
            "HTTPS://cdn.example.com/segK.ts\n" +
            "#EXTINF:6.0,\n" +
            "seg2.ts\n"
        val direct = HlsParser.parseDirectMedia(body, "https://cdn.example.com/a.m3u8")
        assertEquals(2, direct.segments.size)
        assertEquals("HTTPS://cdn.example.com/segK.ts", direct.segments[0])
        assertEquals(4_000_000L, direct.durationsUs[0])
        assertEquals(6_000_000L, direct.durationsUs[1])
    }

    @Test
    fun `parseDirectMedia - segmen data dibuang tanpa menggeser durasi`() {
        // Skema tanpa "//" (issue #464) sama asingnya dengan ftp://:
        // dibuang sebelum resolusi dan EXTINF-nya tak menempel ke segmen
        // berikutnya (pasangan #394).
        val body = "#EXTM3U\n" +
            "#EXTINF:3.0,\n" +
            "data:application/octet-stream;base64,AAAA\n" +
            "#EXTINF:7.0,\n" +
            "seg2.ts\n"
        val direct = HlsParser.parseDirectMedia(body, "https://cdn.example.com/a.m3u8")
        assertEquals(listOf("https://cdn.example.com/seg2.ts"), direct.segments)
        assertEquals(listOf(7_000_000L), direct.durationsUs)
    }

    @Test
    fun `parseDirectMedia - tag dan baris kosong bukan segmen`() {
        val body = "#EXTM3U\n\n#EXT-X-VERSION:3\nseg1.ts\n"
        val direct = HlsParser.parseDirectMedia(body, "https://cdn.example.com/a.m3u8")
        assertEquals(listOf("https://cdn.example.com/seg1.ts"), direct.segments)
        assertEquals(listOf(0L), direct.durationsUs)
    }

    @Test
    fun `parseDirectMedia - EXTINF segmen terbuang tak menggeser durasi`() {
        // Segmen skema non-http dibuang; EXTINF-nya tak boleh menempel ke
        // segmen berikutnya (issue #394).
        val body = "#EXTM3U\n" +
            "#EXTINF:5.0,\n" +
            "ftp://cdn.example.com/segA.ts\n" +
            "#EXTINF:7.0,\n" +
            "segB.ts\n"
        val direct = HlsParser.parseDirectMedia(body, "https://cdn.example.com/a.m3u8")
        assertEquals(listOf("https://cdn.example.com/segB.ts"), direct.segments)
        assertEquals(listOf(7_000_000L), direct.durationsUs)
    }

    @Test
    fun `parse audio rendition - menghormati DEFAULT dan resolusi URL`() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:URI="/audios/low.m3u8",TYPE=AUDIO,GROUP-ID="low",NAME="Low",DEFAULT=YES,AUTOSELECT=YES
            #EXT-X-MEDIA:URI="high.m3u8",TYPE=AUDIO,GROUP-ID="high",NAME="High"
            #EXT-X-MEDIA:URI="subs.m3u8",TYPE=SUBTITLES,GROUP-ID="sub",NAME="Subs"
        """.trimIndent()
        val renditions = HlsParser.parseAudioRenditions(master, "https://cdn.example.com/videos/master.m3u8")
        assertEquals(2, renditions.size)
        assertTrue(renditions[0].isDefault)
        assertEquals("https://cdn.example.com/audios/low.m3u8", renditions[0].url)
        assertEquals("https://cdn.example.com/videos/high.m3u8", renditions[1].url)
        assertTrue(!renditions[1].isDefault)
    }
}
