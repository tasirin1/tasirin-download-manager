package com.tasirin.httpdownloadmanager.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `AVERAGE-BANDWIDTH ditulis lebih dulu - bandwidth puncak yang terbaca`() {
        val m = """
            #EXTM3U
            #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=200000,BANDWIDTH=800000,RESOLUTION=1280x720
            https://cdn.example.com/videos/720.m3u8
        """.trimIndent()
        val variants = HlsParser.parseMaster(m, "https://cdn.example.com/videos/master.m3u8")!!
        assertEquals(1, variants.size)
        assertEquals(800000L, variants[0].bandwidth)
    }

    @Test
    fun `AVERAGE-BANDWIDTH sesudah BANDWIDTH - urutan tak mengubah hasil`() {
        val m = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,AVERAGE-BANDWIDTH=200000,RESOLUTION=1280x720
            https://cdn.example.com/videos/720.m3u8
        """.trimIndent()
        val variants = HlsParser.parseMaster(m, "https://cdn.example.com/videos/master.m3u8")!!
        assertEquals(800000L, variants[0].bandwidth)
    }

    @Test
    fun `BANDWIDTH di dalam NAME terkutip sebelum atribut asli - atribut asli yang terbaca`() {
        val m = """
            #EXTM3U
            #EXT-X-STREAM-INF:NAME="BANDWIDTH=999",BANDWIDTH=800000,RESOLUTION=1280x720
            https://cdn.example.com/videos/720.m3u8
        """.trimIndent()
        val variants = HlsParser.parseMaster(m, "https://cdn.example.com/videos/master.m3u8")!!
        assertEquals(1, variants.size)
        assertEquals(800000L, variants[0].bandwidth)
    }

    @Test
    fun `BANDWIDTH hanya di dalam NAME terkutip - bandwidth nol`() {
        val m = """
            #EXTM3U
            #EXT-X-STREAM-INF:NAME="promo BANDWIDTH=555 sale",RESOLUTION=640x360
            https://cdn.example.com/videos/360.m3u8
        """.trimIndent()
        val variants = HlsParser.parseMaster(m, "https://cdn.example.com/videos/master.m3u8")!!
        assertEquals(1, variants.size)
        assertEquals(0L, variants[0].bandwidth)
    }

    @Test
    fun `hanya AVERAGE-BANDWIDTH - bandwidth nol, bukan nilai rata-rata`() {
        val m = """
            #EXTM3U
            #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=200000,RESOLUTION=640x360
            https://cdn.example.com/videos/360.m3u8
        """.trimIndent()
        val variants = HlsParser.parseMaster(m, "https://cdn.example.com/videos/master.m3u8")!!
        assertEquals(1, variants.size)
        assertEquals(0L, variants[0].bandwidth)
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
    fun `isHttpUrl - hanya http dan https utuh yang diterima`() {
        // Predikat bersama penerimaan (issue #477): awalan "http" saja
        // tak cukup — skema mirip-http tanpa "//" harus tertolak.
        assertTrue(HlsParser.isHttpUrl("https://cdn.example.com/x"))
        assertTrue(HlsParser.isHttpUrl("HTTP://cdn.example.com/x"))
        assertTrue(!HlsParser.isHttpUrl("http:seg.ts"))
        assertTrue(!HlsParser.isHttpUrl("httpx://cdn.example.com/x"))
        assertTrue(!HlsParser.isHttpUrl("ftp://cdn.example.com/x"))
    }

    @Test
    fun `parseMasterLenient - varian skema mirip-http tanpa garis miring ganda dibuang`() {
        val body = "#EXTM3U\n" +
            "#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=800000\n" +
            "httpx://cdn.example.com/videos/a.m3u8\n" +
            "#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=900000\n" +
            "http:seg.ts\n" +
            "#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=1000000\n" +
            "https://cdn.example.com/videos/ok.m3u8\n"
        val variants = HlsParser.parseMasterLenient(body, "https://cdn.example.com/videos/master.m3u8")!!
        assertEquals(1, variants.size)
        assertEquals("https://cdn.example.com/videos/ok.m3u8", variants[0].url)
    }

    @Test
    fun `parseMasterLenient - varian relatif polos diterima dan dilengkapi seperti parseMaster`() {
        // Issue #489: saringan lama membuang URI varian relatif polos
        // yang diterima parseMaster, sehingga master tak umum ber-URI
        // relatif gagal total di fallback. Kini sejajar: bukan skema
        // asing -> diterima, resolveUrl melengkapinya.
        val body = "#EXTM3U\n" +
            "#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=800000\n" +
            "360p/index.m3u8\n" +
            "#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=400000\n" +
            "ftp://cdn.example.com/videos/asing.m3u8\n"
        val variants = HlsParser.parseMasterLenient(body, "https://cdn.example.com/videos/master.m3u8")!!
        assertEquals(1, variants.size)
        assertEquals("https://cdn.example.com/videos/360p/index.m3u8", variants[0].url)
    }

    @Test
    fun `parseMasterLenient - label kbps ganda untuk varian sebandwidth diberi pembeda`() {
        // Issue #490: dua varian BANDWIDTH sama tanpa RESOLUTION
        // (AVC vs HEVC) dulu sama-sama bernama "800 kbps".
        val body = "#EXTM3U\n" +
            "#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=800000,CODECS=\"avc1.4D401E\"\n" +
            "https://cdn.example.com/videos/a.m3u8\n" +
            "#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=800000,CODECS=\"hvc1.1.6.L93\"\n" +
            "https://cdn.example.com/videos/b.m3u8\n"
        val variants = HlsParser.parseMasterLenient(body, "https://cdn.example.com/videos/master.m3u8")!!
        assertEquals(2, variants.size)
        assertEquals(listOf("800 kbps", "800 kbps #2"), variants.map { it.name })
    }

    @Test
    fun `parseMaster - label kbps ganda untuk varian sebandwidth tanpa NAME dan RESOLUTION diberi pembeda`() {
        // Issue #495: kelas cacat #490 di parser ketat — label tanpa
        // NAME/RESOLUTION adalah fungsi murni bandwidth.
        val body = "#EXTM3U\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=800000,CODECS=\"avc1.4D401E\"\n" +
            "https://cdn.example.com/videos/a.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=800000,CODECS=\"hvc1.1.6.L93\"\n" +
            "https://cdn.example.com/videos/b.m3u8\n"
        val variants = HlsParser.parseMaster(body, "https://cdn.example.com/videos/master.m3u8")!!
        assertEquals(2, variants.size)
        assertEquals(listOf("800 kbps", "800 kbps #2"), variants.map { it.name })
    }

    @Test
    fun `parseMaster - NAME packager berpola sufiks turunan dinaikkan, bukan ditumpuk`() {
        // Issue #498: urutan (1) tanpa NAME -> "800 kbps", (2) tanpa
        // NAME -> "800 kbps #2", (3) NAME persis "800 kbps #2" dari
        // packager -> dulu "800 kbps #2 #2", kini sufiksnya dinaikkan.
        val body = "#EXTM3U\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=800000,CODECS=\"avc1.4D401E\"\n" +
            "https://cdn.example.com/videos/a.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=800000,CODECS=\"hvc1.1.6.L93\"\n" +
            "https://cdn.example.com/videos/b.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=800000,NAME=\"800 kbps #2\"\n" +
            "https://cdn.example.com/videos/c.m3u8\n"
        val variants = HlsParser.parseMaster(body, "https://cdn.example.com/videos/master.m3u8")!!
        assertEquals(listOf("800 kbps", "800 kbps #2", "800 kbps #3"), variants.map { it.name })
    }

    @Test
    fun `parseMaster - NAME bersufiks Int MAX_VALUE dijepit ke penomoran ulang`() {
        // Issue #505 memperkenalkan parse Long agar +1 tak meluap
        // negatif; issue #507 lalu MENJEPT nomor di atas batas wajar,
        // sehingga sufiks raksasa kini diperlakukan seperti gagal
        // parse: dasar dipakai dan penomoran diulang dari 2.
        val body = "#EXTM3U\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=800000,NAME=\"Lagu #2147483647\"\n" +
            "https://cdn.example.com/videos/a.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=800000,NAME=\"Lagu #2147483647\"\n" +
            "https://cdn.example.com/videos/b.m3u8\n"
        val variants = HlsParser.parseMaster(body, "https://cdn.example.com/videos/master.m3u8")!!
        assertEquals(listOf("Lagu #2147483647", "Lagu #2"), variants.map { it.name })
    }

    @Test
    fun `parseMaster - NAME bersufiks Long MAX_VALUE dijepit ke penomoran ulang`() {
        // Issue #507: nomor sufiks di atas batas wajar (1.000.000)
        // diperlakukan seperti gagal parse — dasar dipakai dan
        // penomoran diulang dari 2, bukan meluap negatif.
        val body = "#EXTM3U\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=800000,NAME=\"Lagu #9223372036854775807\"\n" +
            "https://cdn.example.com/videos/a.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=800000,NAME=\"Lagu #9223372036854775807\"\n" +
            "https://cdn.example.com/videos/b.m3u8\n"
        val variants = HlsParser.parseMaster(body, "https://cdn.example.com/videos/master.m3u8")!!
        assertEquals(listOf("Lagu #9223372036854775807", "Lagu #2"), variants.map { it.name })
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
    fun `parseMasterLenient - baris rusak diabaikan, relatif dilengkapi`() {
        // Semantik diperbarui oleh issue #489 (uji ini dulu mengunci
        // perilaku lama "relatif polos dibuang" dan menjadi satu-satunya
        // kegagalan CI run 40718513071): pasangan STREAM-INF yang hilang
        // tetap diabaikan, sedangkan URI relatif — root maupun polos —
        // kini diterima dan dilengkapi resolveUrl seperti parseMaster.
        val body = "#EXTM3U\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=500000\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=854x480\n" +
            "/rel/480.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=100\n" +
            "relative-tanpa-slash.m3u8\n"
        val variants = HlsParser.parseMasterLenient(body, "https://cdn.example.com/videos/master.m3u8")
        assertEquals(2, variants!!.size)
        assertEquals("https://cdn.example.com/rel/480.m3u8", variants[0].url)
        assertEquals("https://cdn.example.com/videos/relative-tanpa-slash.m3u8", variants[1].url)
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
    fun `parseDirectMedia - EXTINF segmen terbuang tak menempel ke segmen polos berikutnya`() {
        // Kasus pembeda kontrak durasi (issue #601): EXTINF dikonsumsi
        // baris URI berikutnya apa pun nasibnya, jadi segmen polos
        // sesudah segmen asing yang dibuang mendapat durasi 0 — bukan
        // 5 detik milik segmen yang dibuang.
        val b = "#EXTM3U\n" +
            "#EXT-X-TARGETDURATION:10\n" +
            "#EXTINF:5,\n" +
            "ftp://cdn.example.com/seg-asing.ts\n" +
            "seg-polos.ts\n"
        val direct = HlsParser.parseDirectMedia(b, "https://cdn.example.com/videos/media.m3u8")
        assertEquals(listOf("https://cdn.example.com/videos/seg-polos.ts"), direct.segments)
        assertEquals(listOf(0L), direct.durationsUs)
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
    fun `parseDirectMedia - token ber-titik-dua di segmen pertama dibuang sebagai skema (keputusan RFC, issue 478)`() {
        // Keputusan yang DIKUNCI, jangan diperbaiki mundur: segmen
        // pertama referensi relatif tak boleh memuat ":" (RFC 3986),
        // jadi seg-12:34.ts dinilai URI berskema asing dan dibuang
        // (tradeoff #464). Pola aman: garis bawah memutus pola skema
        // sehingga seg_12:30.ts tetap relatif yang sah.
        val body = "#EXTM3U\n" +
            "seg-12:34.ts\n" +
            "seg_12:30.ts\n"
        val direct = HlsParser.parseDirectMedia(body, "https://cdn.example.com/a.m3u8")
        assertEquals(listOf("https://cdn.example.com/seg_12:30.ts"), direct.segments)
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

    @Test
    fun `isByteRangeHlsPlaylist - mendeteksi tag byterange`() {
        // Issue #513: gerbang fail-fast di kedua pintu pengumpul segmen
        // bertumpu pada deteksi ini.
        assertTrue(
            isByteRangeHlsPlaylist(
                "#EXTM3U\n#EXTINF:10,\n#EXT-X-BYTERANGE:1000@0\nseg.ts\n"
            )
        )
        assertFalse(
            isByteRangeHlsPlaylist("#EXTM3U\n#EXTINF:10,\nseg.ts\n")
        )
    }

    @Test
    fun `isEncryptedHlsPlaylist - method selain NONE tertangkap, NONE murni lolos`() {
        // Issue #554: gerbang enkripsi kini terkunci uji khusus
        // sejajar gerbang byterange/MAP — semantiknya fail-closed
        // dan sadar rotasi kunci, jangan sampai regresi senyap.
        assertTrue(
            isEncryptedHlsPlaylist(
                "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:10,\nseg.ts\n"
            )
        )
        assertTrue(
            isEncryptedHlsPlaylist(
                "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key.bin\"\n#EXTINF:10,\nseg.ts\n"
            )
        )
        assertFalse(
            isEncryptedHlsPlaylist(
                "#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:10,\nseg.ts\n"
            )
        )
        assertFalse(isEncryptedHlsPlaylist("#EXTM3U\n#EXTINF:10,\nseg.ts\n"))
    }

    @Test
    fun `isEncryptedHlsPlaylist - rotasi kunci dan bentuk fail-closed`() {
        // Rotasi NONE -> AES di tengah playlist tetap terenkripsi.
        assertTrue(
            isEncryptedHlsPlaylist(
                "#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:10,\na.ts\n" +
                    "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:10,\nb.ts\n"
            )
        )
        // Tanpa METHOD terbaca sama sekali: fail-closed.
        assertTrue(
            isEncryptedHlsPlaylist(
                "#EXTM3U\n#EXT-X-KEY:URI=\"key.bin\"\n#EXTINF:10,\nseg.ts\n"
            )
        )
        // METHOD terkutip tidak terbaca regex: fail-closed juga.
        assertTrue(
            isEncryptedHlsPlaylist(
                "#EXTM3U\n#EXT-X-KEY:METHOD=\"AES-128\",URI=\"key.bin\"\n#EXTINF:10,\nseg.ts\n"
            )
        )
        // METHOD sebagai atribut kedua (sesudah URI) tetap tertangkap.
        assertTrue(
            isEncryptedHlsPlaylist(
                "#EXTM3U\n#EXT-X-KEY:URI=\"key.bin\",METHOD=AES-128\n#EXTINF:10,\nseg.ts\n"
            )
        )
        // Tag kunci berindentasi spasi tetap tertangkap (issue #599):
        // parser lain men-trim baris, jadi gerbang tak boleh fail-open.
        assertTrue(
            isEncryptedHlsPlaylist(
                "#EXTM3U\n  #EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:10,\nseg.ts\n"
            )
        )
        // Tag kunci berindentasi tab dengan METHOD=NONE tetap lolos sah.
        assertFalse(
            isEncryptedHlsPlaylist(
                "#EXTM3U\n\t#EXT-X-KEY:METHOD=NONE\n#EXTINF:10,\nseg.ts\n"
            )
        )
        // METHOD=NONE di dalam URI terkutip bukan atribut (issue #600):
        // baris tanpa METHOD sungguhan wajib fail-closed.
        assertTrue(
            isEncryptedHlsPlaylist(
                "#EXTM3U\n#EXT-X-KEY:URI=\"https://x.example/key?METHOD=NONE\"\n#EXTINF:10,\nseg.ts\n"
            )
        )
        // METHOD=NONE sungguhan tetap lolos walau URI memuat METHOD lain.
        assertFalse(
            isEncryptedHlsPlaylist(
                "#EXTM3U\n#EXT-X-KEY:METHOD=NONE,URI=\"https://x.example/key?METHOD=AES-128\"\n#EXTINF:10,\nseg.ts\n"
            )
        )
        // URI terkutip TAK DITUTUP + METHOD=NONE sesudahnya (issue
        // #617): baris berkutip tak seimbang dibaca apa adanya, jadi
        // METHOD asli terlihat dan playlist tak terenkripsi ini tidak
        // ditolak sebagai positif palsu.
        assertFalse(
            isEncryptedHlsPlaylist(
                "#EXTM3U\n#EXT-X-KEY:URI=\"key.bin,METHOD=NONE\n#EXTINF:10,\nseg.ts\n"
            )
        )
        // Simetrisnya: METHOD=AES-128 sesudah kutip ganjil tetap
        // tertangkap sebagai terenkripsi.
        assertTrue(
            isEncryptedHlsPlaylist(
                "#EXTM3U\n#EXT-X-KEY:URI=\"key.bin,METHOD=AES-128\n#EXTINF:10,\nseg.ts\n"
            )
        )
    }

    @Test
    fun `parseMaster - nilai terkutip tak ditutup tidak menutupi atribut asli`() {
        // Issue #617: NAME tak ditutup (baris terpotong) — BANDWIDTH
        // dan RESOLUTION asli sesudahnya wajib tetap terbaca karena
        // baris berkutip tak seimbang tidak di-mask.
        val variants = HlsParser.parseMaster(
            "#EXTM3U\n" +
                "#EXT-X-STREAM-INF:NAME=\"promo,BANDWIDTH=800000,RESOLUTION=1280x720\n" +
                "v.m3u8\n",
            "https://cdn.example.com/master.m3u8"
        )!!
        assertEquals(1, variants.size)
        assertEquals(800000L, variants[0].bandwidth)
        assertEquals(720, variants[0].height)
    }

    @Test
    fun `tanpaNilaiKutip - kontrak masking langsung`() {
        // Issue #620: kontrak helper dikunci langsung, bukan hanya
        // tak langsung lewat parseMaster — isi kutip menjadi spasi,
        // tanda kutip dan panjang baris dipertahankan.
        assertEquals(
            "NAME=\"             \",BANDWIDTH=800000",
            HlsParser.tanpaNilaiKutip("NAME=\"BANDWIDTH=999\",BANDWIDTH=800000")
        )
        // Tanpa kutip: baris kembali apa adanya.
        assertEquals(
            "BANDWIDTH=800000",
            HlsParser.tanpaNilaiKutip("BANDWIDTH=800000")
        )
        // Kutip tak seimbang: baris rusak dikembalikan utuh (#617).
        assertEquals(
            "NAME=\"promo,BANDWIDTH=800000",
            HlsParser.tanpaNilaiKutip("NAME=\"promo,BANDWIDTH=800000")
        )
    }

    @Test
    fun `parseAudioRenditions - DEFAULT di dalam NAME terkutip bukan default`() {
        // Issue #620: teks DEFAULT=YES sebagai isi NAME tak boleh
        // menandai rendisi sebagai default; yang berlaku hanya
        // atribut DEFAULT sungguhan di luar kutip.
        val body = """
            #EXTM3U
            #EXT-X-MEDIA:URI="audio.m3u8",TYPE=AUDIO,GROUP-ID="aud",NAME="DEFAULT=YES",DEFAULT=NO
            #EXT-X-STREAM-INF:BANDWIDTH=800000,AUDIO="aud"
            v.m3u8
        """.trimIndent()
        val renditions = HlsParser.parseAudioRenditions(body, "https://cdn.example.com/videos/master.m3u8")
        assertEquals(1, renditions.size)
        assertFalse(renditions[0].isDefault)
    }

    @Test
    fun `parseMasterLenient - atribut hantu di dalam NAME terkutip diabaikan`() {
        // Issue #620: jalur lenient memakai masking yang sama —
        // BANDWIDTH=1 di dalam NAME bukan atribut; yang terbaca
        // BANDWIDTH asli di luar kutip.
        val body = """
            #EXTM3U
            #EXT-X-STREAM-INF:NAME="BANDWIDTH=1",BANDWIDTH=500000
            v.m3u8
        """.trimIndent()
        val variants = HlsParser.parseMasterLenient(body, "https://cdn.example.com/videos/master.m3u8")!!
        assertEquals(1, variants.size)
        assertEquals(500000L, variants[0].bandwidth)
    }

    @Test
    fun `isEncryptedHlsPlaylist - KEYFORMAT dan METHOD di luar tag kunci tak memicu`() {
        // Issue #577: tag KEYFORMAT/KEYFORMATVERSIONS bukan tag kunci;
        // playlist tanpa satu pun #EXT-X-KEY tidak terenkripsi.
        assertFalse(
            isEncryptedHlsPlaylist(
                "#EXTM3U\n#EXT-X-KEYFORMAT=\"identity\"\n#EXT-X-KEYFORMATVERSIONS=\"1\"\n#EXTINF:10,\nseg.ts\n"
            )
        )
        // Teks METHOD= di judul #EXTINF bukan atribut tag kunci: yang
        // berlaku hanya METHOD pada baris #EXT-X-KEY itu sendiri.
        assertFalse(
            isEncryptedHlsPlaylist(
                "#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:10,METHOD=AES-128\nseg.ts\n"
            )
        )
    }

    @Test
    fun `isMapHlsPlaylist - mendeteksi tag segmen init fMP4`() {
        // Issue #514: gerbang fail-fast fMP4 bertumpu pada deteksi ini.
        assertTrue(
            isMapHlsPlaylist(
                "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:10,\nseg1.m4s\n"
            )
        )
        assertFalse(
            isMapHlsPlaylist("#EXTM3U\n#EXTINF:10,\nseg.ts\n")
        )
    }

    @Test
    fun `buangSufiksEkstensi - tak peka kapital dan berurutan`() {
        // Issue #522: nama kustom ber-ekstensi kapital tidak boleh
        // menghasilkan nama terbit berekstensi ganda.
        assertEquals("Film", buangSufiksEkstensi("Film.MP4", ".ts", ".mp4"))
        assertEquals("Film", buangSufiksEkstensi("Film.TS", ".ts", ".mp4"))
        assertEquals("Film", buangSufiksEkstensi("Film.webm", ".mp4", ".webm"))
        assertEquals("Film", buangSufiksEkstensi("Film.mp4", ".ts", ".mp4"))
        assertEquals("Film.mkv", buangSufiksEkstensi("Film.mkv", ".ts", ".mp4"))
        assertEquals("Film.Ts", buangSufiksEkstensi("Film.Ts.Mp4", ".ts", ".mp4"))
    }
}
