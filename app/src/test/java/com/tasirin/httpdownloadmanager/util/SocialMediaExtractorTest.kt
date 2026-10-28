package com.tasirin.httpdownloadmanager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class SocialMediaExtractorTest {

    // audit-ignore: maintenance_marker (nama domain resmi situs, bukan marker)
    private val hhDomain = "hentaihaven.xxx"

    @Test
    fun `parseUserHeaders memparse format Key-value per baris`() {
        val parsed = SocialMediaExtractor.parseUserHeaders(
            "Cookie: session=abc; theme=dark\nReferer: https://example.com/watch\n"
        )
        assertEquals("session=abc; theme=dark", parsed["Cookie"])
        assertEquals("https://example.com/watch", parsed["Referer"])
        // Baris sampah dibuang, user boleh menimpa default platform.
        val dirty = SocialMediaExtractor.parseUserHeaders("\n\nTanpaColon\n: tanpaKey\nKeyTanpaValue:\nCookie: x=1\nCookie: y=2\n")
        assertEquals(mapOf("Cookie" to "y=2"), dirty)
        assertTrue(SocialMediaExtractor.parseUserHeaders("").isEmpty())
        assertTrue(SocialMediaExtractor.parseUserHeaders("   ").isEmpty())
        // Nilai boleh mengandung ':' (URL, waktu).
        val colon = SocialMediaExtractor.parseUserHeaders("Referer: https://example.com:8080/a?x=1:2")
        assertEquals("https://example.com:8080/a?x=1:2", colon["Referer"])
    }

    @Test
    fun `isSocialMediaUrl - deteksi URL beranda sosial`() {
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://www.youtube.com/watch?v=abc"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://youtu.be/abc"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://www.tiktok.com/@user/video/123"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://vm.tiktok.com/abc/"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://www.instagram.com/p/abc/"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://www.instagram.com/reel/abc/"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://x.com/username/status/123"))
        // Facebook didukung lagi atas permintaan owner (sempat dihapus total).
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://www.facebook.com/share/v/abc/"))
    }

    @Test
    fun `isSocialMediaUrl - CDN media bukan URL sosial`() {
        // CDN media sudah di-extract; jangan salah deteksi ulang.
        assertFalse(SocialMediaExtractor.isSocialMediaUrl(
            "https://scontent-cdn1-1.cdninstagram.com/v/t51.2885-15/123.jpg"
        ))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl(
            "https://v16m.tiktokcdn-us.com/video/tos/123.mp4"
        ))
        // CDN Facebook adalah media hasil ekstraksi, bukan URL halaman.
        assertFalse(SocialMediaExtractor.isSocialMediaUrl(
            "https://lookaside.fbsbx.com/lookaside/crawler/media/?media_id=1020806507787076"
        ))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl(
            "https://video.fbcdn.net/v/t43.1/123.mp4"
        ))
    }

    @Test
    fun `isSocialMediaUrl - false positive mencegah domain mirip`() {
        // notyoutube.com seharusnya TIDAK terdeteksi sebagai YouTube
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("https://notyoutube.com/video/123"))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("https://evil-tiktok.com/steal"))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("https://fakeinstagram.com/p/abc"))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("https://x-twitter.com/malicious"))
    }

    @Test
    fun `isSocialMediaUrl - path tanpa slashaman bukan URL sosial`() {
        // youtube.com tanpa pathslash (bare domain) tidak terdeteksi
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("https://youtube.com"))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("https://youtu.be"))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("https://tiktok.com"))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("https://instagram.com"))
    }

    @Test
    fun `isSocialMediaUrl - subdomain mobile ikut terdeteksi`() {
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://m.youtube.com/watch?v=abc"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://music.youtube.com/watch?v=abc"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://m.tiktok.com/@user/video/123"))
        // Subdomain asing tetap ditolak (bukan m./music./www.).
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("https://evil.youtube.com/watch?v=abc"))
    }

    @Test
    fun `isSocialMediaUrl - HTTP juga terdeteksi`() {
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("http://youtube.com/watch?v=abc"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("http://tiktok.com/@user/video/123"))
    }

    @Test
    fun `isSocialMediaUrl - xvideos terdeteksi`() {
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://www.xvideos.com/video.12345/judul-video"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("http://m.xvideos.com/video.123/judul"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://mobile.xvideos.com/video.123/judul"))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("https://notxvideos.com/video/123"))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("https://xvideos.com"))
    }

    @Test
    fun `isSocialMediaUrl - xnxx terdeteksi`() {
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://www.xnxx.com/video-abc/judul"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("http://m.xnxx.com/video-abc/judul"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://www.xnxxvideos.me/video-abc/judul"))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("https://notxnxx.com/video/123"))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("https://xnxx.com"))
    }

    @Test
    fun `parseXnxxPage - prefix XNXX dan suffix judul`() {
        val html = "<html><head><title>Great video - XNXX.COM</title></head><body>" +
            "<script>html5player.setVideoUrlHigh('https://cdn.xnxx-cdn.com/high.mp4');" +
            "html5player.setVideoUrlLow('https://cdn.xnxx-cdn.com/low.mp4');</script></body></html>"
        val opts = SocialMediaExtractor.parseXnxxPage(html)
        assertEquals(2, opts.size)
        assertEquals("Great video", opts[0].title)
        assertTrue(opts[0].fileName!!.startsWith("XNXX_"))
        assertTrue(opts[0].fileName!!.endsWith("_HD.mp4"))
        assertEquals("HD", opts[0].quality)
        assertEquals("SD", opts[1].quality)
    }

    @Test
    fun `parseXVideosPage - high low hls`() {
        val html = "<html><head><title>Cool video - XVIDEOS.COM</title></head><body>" +
            "<script>html5player.setVideoTitle('Cool video');" +
            "html5player.setVideoUrlHigh('https://cdn77-vid.xvideos-cdn.com/abc-high.mp4');" +
            "html5player.setVideoUrlLow('https://cdn77-vid.xvideos-cdn.com/abc-low.mp4');" +
            "html5player.setVideoHLS('https://cdn77-vid.xvideos-cdn.com/hls/abc.m3u8');</script></body></html>"
        val opts = SocialMediaExtractor.parseXVideosPage(html)
        assertEquals(3, opts.size)
        assertEquals("HD", opts[0].quality)
        assertTrue(opts[0].directUrl.endsWith("-high.mp4"))
        assertEquals("SD", opts[1].quality)
        assertTrue(opts[2].isHls)
        assertTrue(opts[2].directUrl.endsWith(".m3u8"))
    }

    @Test
    fun `parseXVideosPage - fallback og video`() {
        val html = "<html><head>" +
            "<meta property=\"og:title\" content=\"OG Title\"/>" +
            "<meta property=\"og:video\" content=\"https://cdn.xvideos-cdn.com/og.mp4\"/>" +
            "</head><body>watch</body></html>"
        val opts = SocialMediaExtractor.parseXVideosPage(html)
        assertEquals(1, opts.size)
        assertEquals("OG Title", opts[0].title)
        assertTrue(opts[0].directUrl.endsWith("og.mp4"))
    }

    @Test
    fun `parseXVideosPage - kosong bila tak ada video`() {
        assertTrue(SocialMediaExtractor.parseXVideosPage("<html><body>hello</body></html>").isEmpty())
    }

    @Test
    fun `isSocialMediaUrl - hentaihaven terdeteksi`() {
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://$hhDomain/watch/muchuu-no-tou/"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://www.$hhDomain/watch/some-episode-1/"))
        assertTrue(SocialMediaExtractor.isHentaiHavenUrl("https://$hhDomain/watch/abc/"))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("https://not$hhDomain/watch/abc/"))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("https://$hhDomain"))
        // Host media situs yang sama bukan halaman watch tapi tetap satu domain;
        // tidak boleh salah deteksi sebagai CDN generik pihak ketiga.
        assertFalse(SocialMediaExtractor.isHentaiHavenUrl("https://img.$hhDomain/images/a.jpg"))
    }

    @Test
    fun `parseHentaiHavenPage - tag video dan judul`() {
        val html = "<html><head><title>Muchuu no Tou Episode 1 - Hentai Haven</title></head><body>" +
            "<video src=\"https://cdn.example.com/vid123.mp4\"></video></body></html>"
        val opts = SocialMediaExtractor.parseHentaiHavenPage(html)
        assertEquals(1, opts.size)
        assertEquals("Muchuu no Tou Episode 1", opts[0].title)
        assertTrue(opts[0].fileName!!.startsWith("HentaiHaven_"))
        assertTrue(opts[0].fileName!!.endsWith(".mp4"))
        assertEquals("Video", opts[0].quality)
        assertEquals("https://cdn.example.com/vid123.mp4", opts[0].directUrl)
    }

    @Test
    fun `parseHentaiHavenPage - data-src dan twitter stream`() {
        val html = "<html><head><title>Data Test - Hentai Haven</title>" +
            "<meta name=\"twitter:player:stream\" content=\"https://cdn.example.com/tw.mp4\"/>" +
            "</head><body>" +
            "<video><source data-src=\"https://cdn.example.com/lazy.mp4\"/></video>" +
            "</body></html>"
        val opts = SocialMediaExtractor.parseHentaiHavenPage(html)
        assertTrue(opts.isNotEmpty())
        // Lazy-load data-src diutamakan sebelum meta twitter stream.
        assertEquals("https://cdn.example.com/lazy.mp4", opts[0].directUrl)
        assertTrue(opts.any { it.directUrl.endsWith("tw.mp4") } || opts.size == 1)
    }

    @Test
    fun `parseHentaiHavenPage - mp4 diutamakan dari m3u8`() {
        val html = "<html><head><title>Both - Hentai Haven</title></head><body>" +
            "<script>var p={file:'https://cdn.example.com/a.m3u8'};</script>" +
            "<video src=\"https://cdn.example.com/b.mp4\"></video></body></html>"
        val opts = SocialMediaExtractor.parseHentaiHavenPage(html)
        assertTrue(opts.size >= 2)
        assertTrue(opts[0].directUrl.endsWith(".mp4"))
    }

    @Test
    fun `parseHentaiHavenPage - hls dan og fallback`() {
        val html = "<html><head>" +
            "<meta property=\"og:title\" content=\"OG Title\"/>" +
            "<meta property=\"og:video\" content=\"https://cdn.example.com/og.m3u8\"/>" +
            "</head><body>watch</body></html>"
        val opts = SocialMediaExtractor.parseHentaiHavenPage(html)
        assertEquals(1, opts.size)
        assertTrue(opts[0].isHls)
        assertTrue(opts[0].directUrl.endsWith(".m3u8"))
        assertTrue(SocialMediaExtractor.parseHentaiHavenPage("<html><body>hello</body></html>").isEmpty())
    }

    @Test
    fun `parseEpisodeLinks - saring watch, buang duplikat dan halaman aktif`() {
        val json = "[{\"url\":\"https://$hhDomain/watch/ep-2/\",\"title\":\"Ep 2\"}," +
            "{\"url\":\"https://$hhDomain/watch/ep-1/\",\"title\":\"Ep 1\"}," +
            "{\"url\":\"https://$hhDomain/watch/ep-2/\",\"title\":\"Ep 2 dup\"}," +
            "{\"url\":\"https://$hhDomain/about/\",\"title\":\"About\"}," +
            "{\"url\":\"not a url\",\"title\":\"Bad\"}]"
        val eps = SocialMediaExtractor.parseEpisodeLinks(json, "https://$hhDomain/watch/ep-1/")
        assertEquals(1, eps.size)
        assertEquals("https://$hhDomain/watch/ep-2/", eps[0].url)
        assertEquals("Ep 2", eps[0].title)
    }

    @Test
    fun `parseEpisodeLinks - json rusak dan batas 50`() {
        assertTrue(SocialMediaExtractor.parseEpisodeLinks("bukan json").isEmpty())
        assertTrue(SocialMediaExtractor.parseEpisodeLinks("[]").isEmpty())
        val big = (1..60).joinToString(",", "[", "]") {
            "{\"url\":\"https://$hhDomain/watch/ep-$it/\",\"title\":\"Ep $it\"}"
        }
        assertEquals(50, SocialMediaExtractor.parseEpisodeLinks(big).size)
    }

    @Test
    fun `isSocialMediaUrl - pornhub terdeteksi`() {
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://www.pornhub.com/view_video.php?viewkey=abc123"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("http://m.pornhub.com/view_video.php?viewkey=abc123"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://www.pornhubpremium.com/view_video.php?viewkey=abc123"))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("https://notpornhub.com/view_video.php?viewkey=abc"))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("https://pornhub.com"))
    }

    @Test
    fun `parsePornhubPage - mediaDefinitions urut kualitas`() {
        val html = "<html><head><title>Hot video - Pornhub.com</title></head><body><script>" +
            "{\"mediaDefinitions\":[{\"videoUrl\":\"https://cdn.ph.com/low.mp4\",\"quality\":\"480\"},{\"videoUrl\":\"https://cdn.ph.com/high.mp4\",\"quality\":\"720\"}]}" +
            "</script></body></html>"
        val opts = SocialMediaExtractor.parsePornhubPage(html)
        assertEquals(2, opts.size)
        assertEquals("Hot video", opts[0].title)
        assertTrue(opts[0].fileName!!.startsWith("Pornhub_"))
        assertEquals("HD", opts[0].quality)
        assertTrue(opts[0].directUrl.endsWith("high.mp4"))
        assertEquals("480p", opts[1].quality)
    }

    @Test
    fun `parsePornhubPage - flashvars dan fallback og`() {
        val html = "<html><head>" +
            "<meta property=\"og:title\" content=\"OG Title\"/>" +
            "<meta property=\"og:video\" content=\"https://cdn.ph.com/og.mp4\"/>" +
            "</head><body><script>var flashvars={\"quality_480p\":\"https://cdn.ph.com/fv480.mp4\"};</script></body></html>"
        val opts = SocialMediaExtractor.parsePornhubPage(html)
        assertEquals(1, opts.size)
        assertTrue(opts[0].directUrl.endsWith("fv480.mp4"))
        val ogOnly = SocialMediaExtractor.parsePornhubPage("<html><head>" +
            "<meta property=\"og:title\" content=\"OG Title\"/>" +
            "<meta property=\"og:video\" content=\"https://cdn.ph.com/og.mp4\"/>" +
            "</head><body>watch</body></html>")
        assertEquals(1, ogOnly.size)
        assertTrue(ogOnly[0].directUrl.endsWith("og.mp4"))
        assertTrue(SocialMediaExtractor.parsePornhubPage("<html><body>hello</body></html>").isEmpty())
    }

    @Test
    fun `parsePornhubPage - quality angka tanpa kutip`() {
        val html = "<html><head><title>Clip - Pornhub.com</title></head><body><script>" +
            "{\"mediaDefinitions\":[{\"videoUrl\":\"https://cdn.ph.com/a.mp4\",\"quality\":480}]}" +
            "</script></body></html>"
        val opts = SocialMediaExtractor.parsePornhubPage(html)
        assertEquals(1, opts.size)
        assertTrue(opts[0].directUrl.endsWith("a.mp4"))
        assertEquals("480p", opts[0].quality)
    }

    @Test
    fun `parsePornhubPage - quality ber-akhiran p`() {
        val html = "<html><head><title>Clip - Pornhub.com</title></head><body><script>" +
            "{\"mediaDefinitions\":[{\"videoUrl\":\"https://cdn.ph.com/b.mp4\",\"quality\":\"720p\"}]}" +
            "</script></body></html>"
        val opts = SocialMediaExtractor.parsePornhubPage(html)
        assertEquals(1, opts.size)
        assertTrue(opts[0].directUrl.endsWith("b.mp4"))
        assertEquals("HD", opts[0].quality)
    }

    @Test
    fun `parsePornhubPage - quality campuran urut menurun`() {
        val html = "<html><head><title>Clip - Pornhub.com</title></head><body><script>" +
            "{\"mediaDefinitions\":[{\"videoUrl\":\"https://cdn.ph.com/a.mp4\",\"quality\":480}," +
            "{\"videoUrl\":\"https://cdn.ph.com/b.mp4\",\"quality\":\"720p\"}]}" +
            "</script></body></html>"
        val opts = SocialMediaExtractor.parsePornhubPage(html)
        assertEquals(2, opts.size)
        assertTrue(opts[0].directUrl.endsWith("b.mp4"))
        assertEquals("HD", opts[0].quality)
        assertTrue(opts[1].directUrl.endsWith("a.mp4"))
        assertEquals("480p", opts[1].quality)
    }

    @Test
    fun `parsePornhubPage - field panjang satu objek dan videoUrl tanpa quality dilewati`() {
        val longNote = "x".repeat(4096)
        val html = "<html><head><title>Clip - Pornhub.com</title></head><body><script>" +
            "{\"mediaDefinitions\":[{\"videoUrl\":\"https://cdn.ph.com/long.mp4\",\"note\":\"$longNote\",\"quality\":\"1080\"}," +
            "{\"videoUrl\":\"https://cdn.ph.com/no-quality.mp4\",\"format\":\"mp4\"}]}" +
            "</script></body></html>"
        val opts = SocialMediaExtractor.parsePornhubPage(html)
        assertEquals(1, opts.size)
        assertTrue(opts[0].directUrl.endsWith("long.mp4"))
        assertEquals("HD", opts[0].quality)
    }

    @Test
    fun `parsePornhubPage - cookie diteruskan ke hasil`() {
        val html = "<html><head><title>Clip - Pornhub.com</title></head><body><script>" +
            "{\"mediaDefinitions\":[{\"videoUrl\":\"https://cdn.ph.com/a.mp4\",\"quality\":\"480\"}]}" +
            "</script></body></html>"
        val opts = SocialMediaExtractor.parsePornhubPage(html, "ph=abc123")
        assertEquals(1, opts.size)
        assertEquals("ph=abc123", opts[0].cookies)
        assertEquals("", SocialMediaExtractor.parsePornhubPage(html)[0].cookies)
    }

    @Test
    fun `preferProgressiveMp4 - MP4 terbaik dipilih walau HLS lebih tinggi`() {
        val html = "<html><head><title>Clip - Pornhub.com</title></head><body><script>" +
            "{\"mediaDefinitions\":[{\"videoUrl\":\"https://cdn.ph.com/a.mp4\",\"quality\":\"480\"}," +
            "{\"videoUrl\":\"https://cdn.ph.com/master.m3u8\",\"quality\":\"1080\"}]}" +
            "</script></body></html>"
        val opts = SocialMediaExtractor.parsePornhubPage(html)
        assertEquals(2, opts.size)
        val best = SocialMediaExtractor.preferProgressiveMp4(opts)
        assertTrue(best!!.directUrl.endsWith("a.mp4"))
        val hlsOnly = SocialMediaExtractor.preferProgressiveMp4(opts.filter { it.isHls })
        assertTrue(hlsOnly!!.isHls)
        assertEquals(null, SocialMediaExtractor.preferProgressiveMp4(emptyList()))
    }

    @Test
    fun `isExtractRedirectAllowed - hanya http dan bukan loopback`() {
        assertTrue(SocialMediaExtractor.isExtractRedirectAllowed("https://cdn.example.com/v.mp4"))
        assertFalse(SocialMediaExtractor.isExtractRedirectAllowed("http://127.0.0.1:8080/api/snapshot"))
        assertFalse(SocialMediaExtractor.isExtractRedirectAllowed("http://localhost/x"))
        assertFalse(SocialMediaExtractor.isExtractRedirectAllowed("ftp://cdn.example.com/v.mp4"))
        assertFalse(SocialMediaExtractor.isExtractRedirectAllowed("file:///etc/passwd"))
        assertFalse(SocialMediaExtractor.isExtractRedirectAllowed("bukan url"))
    }

    @Test
    fun `isExtractRedirectAllowed - tolak metadata dan samaran loopback`() {
        // Guard validasi redirect resolveInvidiousLatest: instance pihak ketiga
        // tak boleh mengarahkan ke metadata cloud / wildcard / loopback samaran.
        assertFalse(SocialMediaExtractor.isExtractRedirectAllowed("http://169.254.169.254/latest/meta-data/"))
        assertFalse(SocialMediaExtractor.isExtractRedirectAllowed("http://0.0.0.0/x"))
        assertFalse(SocialMediaExtractor.isExtractRedirectAllowed("http://2130706433/x"))
        assertFalse(SocialMediaExtractor.isExtractRedirectAllowed("http://127.1/x"))
        // CDN video sah tetap lolos.
        assertTrue(SocialMediaExtractor.isExtractRedirectAllowed("https://rr1---sn.googlevideo.com/videoplayback?x=1"))
        assertTrue(SocialMediaExtractor.isExtractRedirectAllowed("http://192.168.1.10/nas/film.mp4"))
    }

    @Test
    fun `refererOrigin - turun ke origin saja`() {
        assertEquals(
            "https://www.youtube.com",
            SocialMediaExtractor.refererOrigin("https://www.youtube.com/watch?v=abc123")
        )
        assertEquals(
            "http://example.com:8080",
            SocialMediaExtractor.refererOrigin("http://example.com:8080/a?x=1")
        )
        // Port default tak ditulis ulang; skema aneh/sampah = null (Referer dibuang).
        assertEquals("https://example.com", SocialMediaExtractor.refererOrigin("https://example.com:443/x"))
        assertEquals(null, SocialMediaExtractor.refererOrigin("ftp://example.com/x"))
        assertEquals(null, SocialMediaExtractor.refererOrigin("bukan url"))
        assertEquals(null, SocialMediaExtractor.refererOrigin(""))
    }

    @Test
    fun `unescapeJsToken - unescape token contextJSON Instagram`() {
        assertEquals(
            "https://example.com/a/b?x=1&y=2",
            SocialMediaExtractor.unescapeJsToken("https:\\/\\/example.com\\/a\\/b?x=1\\u0026y=2")
        )
        assertEquals(
            "{\"a\":\"b/c\"}",
            SocialMediaExtractor.unescapeJsToken("{\\\"a\\\":\\\"b\\/c\\\"}")
        )
        assertEquals("", SocialMediaExtractor.unescapeJsToken(""))
    }

    @Test
    fun `isSocialMediaUrl - URL kosong dan bukan HTTP`() {
        assertFalse(SocialMediaExtractor.isSocialMediaUrl(""))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("ftp://youtube.com/watch"))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("file:///etc/passwd"))
    }

    @Test
    fun `isSocialMediaUrl - Google Drive hanya link berkas`() {
        assertTrue(SocialMediaExtractor.isSocialMediaUrl(
            "https://drive.google.com/file/d/1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs/view?usp=sharing"
        ))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl(
            "https://drive.google.com/open?id=1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs"
        ))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl(
            "https://drive.google.com/uc?id=1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs&export=download"
        ))
        // Bentuk multi-akun /drive/u/<n>/ dari UI Drive (#243).
        assertTrue(SocialMediaExtractor.isSocialMediaUrl(
            "https://drive.google.com/drive/u/0/file/d/1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs/view?usp=sharing"
        ))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl(
            "https://drive.google.com/drive/u/12/open?id=1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs"
        ))
        // Link folder dan beranda Drive bukan unduhan berkas.
        assertFalse(SocialMediaExtractor.isSocialMediaUrl(
            "https://drive.google.com/drive/folders/1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs"
        ))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl(
            "https://drive.google.com/drive/u/0/folders/1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs"
        ))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("https://drive.google.com/"))
        // Domain mirip tidak boleh lolos.
        assertFalse(SocialMediaExtractor.isSocialMediaUrl(
            "https://drive.google.com.evil.com/file/d/1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs/view"
        ))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl(
            "https://notdrive.google.com/file/d/1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs/view"
        ))
    }

    @Test
    fun `parseDriveFileId - tiga bentuk link berbagi`() {
        val id = "1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs"
        assertEquals(id, SocialMediaExtractor.parseDriveFileId(
            "https://drive.google.com/file/d/$id/view?usp=sharing"))
        assertEquals(id, SocialMediaExtractor.parseDriveFileId(
            "https://drive.google.com/open?id=$id"))
        assertEquals(id, SocialMediaExtractor.parseDriveFileId(
            "https://drive.google.com/uc?id=$id&export=download"))
        assertEquals(id, SocialMediaExtractor.parseDriveFileId(
            "https://drive.google.com/drive/u/0/file/d/$id/view?usp=sharing"))
        assertEquals(null, SocialMediaExtractor.parseDriveFileId(
            "https://drive.google.com/drive/folders/$id"))
        assertEquals(null, SocialMediaExtractor.parseDriveFileId(
            "https://drive.google.com/file/d/pendek/view"))
    }

    @Test
    fun `driveDownloadUrl - URL awal unduh Drive`() {
        assertEquals(
            "https://drive.google.com/uc?export=download&id=1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs",
            SocialMediaExtractor.driveDownloadUrl("1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs")
        )
    }

    @Test
    fun `parseDriveConfirmUrl - rakit URL dari form halaman perantara`() {
        val html = """
            <form id="download-form" action="https://drive.usercontent.google.com/download" method="get">
            <input type="hidden" name="id" value="1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs">
            <input type="hidden" name="export" value="download">
            <input type="hidden" name="confirm" value="t">
            <input type="hidden" name="uuid" value="12345678-1234-1234-1234-123456789abc">
            </form>
        """.trimIndent()
        val expected = "https://drive.usercontent.google.com/download" +
            "?id=1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs&export=download&confirm=t" +
            "&uuid=12345678-1234-1234-1234-123456789abc"
        assertEquals(expected, SocialMediaExtractor.parseDriveConfirmUrl(html))
        // Urutan atribut value-sebelum-name tetap terbaca.
        val swapped = html.replace(
            "name=\"uuid\" value=\"12345678-1234-1234-1234-123456789abc\"",
            "value=\"12345678-1234-1234-1234-123456789abc\" name=\"uuid\""
        )
        assertEquals(expected, SocialMediaExtractor.parseDriveConfirmUrl(swapped))
        // Form asing lebih dulu di dokumen tidak membuat menyerah, dan
        // field asing bernama sama di luar form unduh tidak mencemari (#247).
        val decoy = "<form action=\"https://example.com/search\">" +
            "<input name=\"id\" value=\"JAHAT\"></form>" + html +
            "<input name=\"id\" value=\"JAHAT2\">"
        assertEquals(expected, SocialMediaExtractor.parseDriveConfirmUrl(decoy))
        // Action berfragmen (#324): query ditempel SEBELUM '#',
        // bukan sesudah fragmen (server tak akan menerimanya).
        val frag = html.replace(
            "action=\"https://drive.usercontent.google.com/download\"",
            "action=\"https://drive.usercontent.google.com/download#frag\""
        )
        assertEquals(
            expected + "#frag",
            SocialMediaExtractor.parseDriveConfirmUrl(frag)
        )
        // Halaman kuota habis / tanpa form unduh = null.
        assertEquals(null, SocialMediaExtractor.parseDriveConfirmUrl(
            "<html><body>Too many users have viewed this file</body></html>"))
        // Form tanpa uuid tidak lengkap = null.
        assertEquals(null, SocialMediaExtractor.parseDriveConfirmUrl(
            html.replace("name=\"uuid\"", "name=\"bukanuuid\"")))
    }

    @Test
    fun `parseDriveConfirmUrl - form tak lengkap pertama dilewati`() {
        // Form unduh pertama yang field-nya tak lengkap tidak boleh
        // menggugurkan form unduh sah sesudahnya (#284).
        val good = """
            <form id="download-form" action="https://drive.usercontent.google.com/download" method="get">
            <input type="hidden" name="id" value="FILEID">
            <input type="hidden" name="export" value="download">
            <input type="hidden" name="uuid" value="UUID-1">
            </form>
        """.trimIndent()
        val incomplete = """
            <form action="https://drive.usercontent.google.com/download" method="get">
            <input type="hidden" name="id" value="FILEID">
            </form>
        """.trimIndent()
        assertEquals(
            "https://drive.usercontent.google.com/download?id=FILEID&export=download&uuid=UUID-1",
            SocialMediaExtractor.parseDriveConfirmUrl(incomplete + good)
        )
        // Action yang sudah membawa query digabung dengan &, bukan ? kedua.
        assertEquals(
            "https://drive.usercontent.google.com/download?at=xyz&id=FILEID&export=download&uuid=UUID-1",
            SocialMediaExtractor.parseDriveConfirmUrl(
                good.replace("/download\"", "/download?at=xyz\"")
            )
        )
    }

    @Test
    fun `parseDriveDisplayName - nama dari halaman perantara`() {
        val html = """<span class="uc-name-size"><a href="/open?id=x">My Video.mp4</a> (700M)</span>"""
        assertEquals("My Video.mp4", SocialMediaExtractor.parseDriveDisplayName(html))
        assertEquals(null, SocialMediaExtractor.parseDriveDisplayName("<html></html>"))
    }

    @Test
    fun `isSocialMediaUrl - URL pembungkus berisi teks platform ditolak`() {
        // Host sebenarnya evil.example; teks platform hanya di query (#245).
        assertFalse(SocialMediaExtractor.isSocialMediaUrl(
            "https://evil.example/?next=https://www.facebook.com/watch/?v=1020806507787076"))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl(
            "https://evil.example/?u=https://www.tiktok.com/@user/video/7123456789"))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl(
            "https://evil.example/?u=https://drive.google.com/file/d/1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs/view"))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl(
            "https://evil.example/?u=https://x.com/user/status/1234567890"))
    }

    @Test
    fun `isSocialMediaUrl - Facebook halaman video, reel, dan share`() {
        assertTrue(SocialMediaExtractor.isSocialMediaUrl(
            "https://www.facebook.com/watch/?v=1020806507787076"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl(
            "https://www.facebook.com/somepage/videos/1020806507787076/"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl(
            "https://www.facebook.com/reel/1020806507787076"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://fb.watch/abcDEF123/"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl(
            "https://m.facebook.com/watch/?v=1020806507787076"))
        // Domain mirip tidak boleh lolos.
        assertFalse(SocialMediaExtractor.isSocialMediaUrl(
            "https://facebook.com.evil.com/watch/?v=1020806507787076"))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl(
            "https://notfacebook.com/watch/?v=1020806507787076"))
    }

    @Test
    fun `parseFacebookVideoId - dari URL dan JSON halaman`() {
        val id = "1020806507787076"
        assertEquals(id, SocialMediaExtractor.parseFacebookVideoId(
            "https://www.facebook.com/watch/?v=$id", ""))
        assertEquals(id, SocialMediaExtractor.parseFacebookVideoId(
            "https://www.facebook.com/somepage/videos/$id/", ""))
        assertEquals(id, SocialMediaExtractor.parseFacebookVideoId(
            "https://www.facebook.com/reel/$id", ""))
        assertEquals(id, SocialMediaExtractor.parseFacebookVideoId(
            "https://www.facebook.com/share/v/abcDEF123/",
            "{\"video_id\":\"$id\"}"))
        assertEquals(null, SocialMediaExtractor.parseFacebookVideoId(
            "https://www.facebook.com/share/v/abcDEF123/", ""))
    }

    @Test
    fun `parseFacebookPage - browser native HD dan SD ter-unescaping`() {
        val html = """{"require":[["VideoPlayer","video_id":"1020806507787076"]],"video":{"browser_native_hd_url":"https:\/\/video-jkt-1.xx.fbcdn.net\/o1\/v\/t2\/hd.mp4?_nc_cat=108\u0026oe=66F0","browser_native_sd_url":"https:\/\/video-jkt-1.xx.fbcdn.net\/o1\/v\/t2\/sd.mp4?_nc_cat=108\u0026oe=66F0"},"pad":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}"""
        val options = SocialMediaExtractor.parseFacebookPage(html, "1020806507787076", "c_user=1")
        assertEquals(2, options.size)
        assertEquals(
            "https://video-jkt-1.xx.fbcdn.net/o1/v/t2/hd.mp4?_nc_cat=108&oe=66F0",
            options[0].directUrl
        )
        assertEquals("HD", options[0].quality)
        assertEquals("Facebook_1020806507787076.mp4", options[0].fileName)
        assertEquals("c_user=1", options[0].cookies)
        assertEquals(
            "https://video-jkt-1.xx.fbcdn.net/o1/v/t2/sd.mp4?_nc_cat=108&oe=66F0",
            options[1].directUrl
        )
        assertEquals("SD", options[1].quality)
        // Halaman hanya berisi redirect lookaside = tidak ada media sah.
        val lookasideOnly = """{"u":"https://lookaside.fbsbx.com/lookaside/crawler/media/?media_id=1020806507787076","pad":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}"""
        assertTrue(SocialMediaExtractor.parseFacebookPage(lookasideOnly, "1", "").isEmpty())
        // Halaman terlalu pendek ditolak.
        assertTrue(SocialMediaExtractor.parseFacebookPage("{}", "1", "").isEmpty())
    }

    @Test
    fun `parseFacebookPage - teks panjang tanpa kutip tidak menelan URL mp4 sah`() {
        val junk = "x".repeat(13_000) + ".mp4"
        val html = """{"pad":"$junk","u":"https://video.xx.fbcdn.net/v/valid.mp4?x=1","tail":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}"""
        val options = SocialMediaExtractor.parseFacebookPage(html, "123", "")
        assertEquals(1, options.size)
        assertTrue(options[0].directUrl.endsWith("valid.mp4?x=1"))
    }

    @Test
    fun `isValidFacebookMediaUrl - lookaside dan fragmen DASH ditolak`() {
        assertTrue(SocialMediaExtractor.isValidFacebookMediaUrl(
            "https://video-jkt-1.xx.fbcdn.net/o1/v/t2/hd.mp4?_nc_cat=108&oe=66F0"))
        assertFalse(SocialMediaExtractor.isValidFacebookMediaUrl(
            "https://lookaside.fbsbx.com/lookaside/crawler/media/?media_id=1020806507787076"))
        assertFalse(SocialMediaExtractor.isValidFacebookMediaUrl(
            "https://video.xx.fbcdn.net/BaseURL/seg.mp4"))
        assertFalse(SocialMediaExtractor.isValidFacebookMediaUrl("bukan url"))
    }

    @Test
    fun `unescapeFb - escape JSON Facebook`() {
        assertEquals(
            "https://video.xx.fbcdn.net/v/a.mp4?x=1&y=2&z=3",
            SocialMediaExtractor.unescapeFb(
                "https:\\/\\/video.xx.fbcdn.net\\/v\\/a.mp4?x=1\\u0026y=2\\x26z=3"
            )
        )
    }

    @Test
    fun `unescapeFb - varian huruf hex dan titik dua`() {
        // \u002f (f kecil), \u003D (D besar), dan \u003a (titik dua)
        // wajib ter-dekode seperti ejaan lama (#283): escape \uXXXX
        // dalam JSON sah memakai hex tak peka huruf besar/kecil.
        assertEquals(
            "https://video.xx.fbcdn.net/v/a.mp4?x=1&y=2",
            SocialMediaExtractor.unescapeFb(
                "https\\u003a\\u002f\\u002fvideo.xx.fbcdn.net\\u002fv\\u002fa.mp4?x\\u003d1\\u0026y\\u003D2"
            )
        )
        // URL yang lolos dekode penuh juga harus lolos validasi media.
        assertTrue(SocialMediaExtractor.isValidFacebookMediaUrl(
            SocialMediaExtractor.unescapeFb(
                "https\\u003a\\u002f\\u002fvideo.xx.fbcdn.net\\u002fv\\u002fa.mp4"
            )
        ))
    }

    @Test
    fun `unescapeFb - satu lintasan tanpa dekode ganda`() {
        // \\u005c adalah backslash HASIL dekode: pasangan 'x26'
        // sesudahnya adalah data, bukan escape \\x yang baru —
        // lintasan terpisah lama mengubahnya menjadi '&' (#323).
        assertEquals(
            "\\x26",
            SocialMediaExtractor.unescapeFb("\\u005cx26")
        )
        // Simetris: \\u0026 sesudah backslash hasil dekode juga
        // berhenti sebagai literal, bukan ikut ter-dekode.
        assertEquals(
            "\\u0026",
            SocialMediaExtractor.unescapeFb("\\u005cu0026")
        )
    }

    @Test
    fun `mergeCookiesIntoHeaders - cookie user tidak ditimpa cookie ekstraksi`() {
        // Tanpa header lama: baris Cookie baru.
        assertEquals("Cookie: a=1; b=2",
            SocialMediaExtractor.mergeCookiesIntoHeaders("", "a=1; b=2"))
        // Header lain dipertahankan, Cookie ditambahkan di akhir.
        assertEquals("Referer: https://x/\nCookie: a=1",
            SocialMediaExtractor.mergeCookiesIntoHeaders("Referer: https://x/", "a=1"))
        // Nama yang sama: nilai user menang; nama baru dari ekstraksi ditambahkan.
        assertEquals("Cookie: session=U; NID=xyz",
            SocialMediaExtractor.mergeCookiesIntoHeaders(
                "Cookie: session=U", "session=J; NID=xyz"))
        // Kunci cookie huruf kecil tetap dikenali.
        assertEquals("Cookie: s=U; n=1",
            SocialMediaExtractor.mergeCookiesIntoHeaders("cookie: s=U", "n=1"))
        // Cookie ekstraksi kosong: header tidak berubah.
        assertEquals("Cookie: a=1",
            SocialMediaExtractor.mergeCookiesIntoHeaders("Cookie: a=1", ""))
        // Dua baris Cookie lama disatukan jadi satu baris.
        assertEquals("Cookie: a=1; b=2; c=3",
            SocialMediaExtractor.mergeCookiesIntoHeaders(
                "Cookie: a=1\nCookie: b=2", "c=3"))
    }

    @Test
    fun `mergeCookiePairs - pasangan user menang, nama baru ditambahkan`() {
        assertEquals("session=U; NID=1",
            SocialMediaExtractor.mergeCookiePairs("session=U", "session=J; NID=1"))
        assertEquals("a=1", SocialMediaExtractor.mergeCookiePairs("", "a=1"))
        assertEquals("a=1", SocialMediaExtractor.mergeCookiePairs("a=1", ""))
    }

    @Test
    fun `cookieJarVisible - hanya host sama atau domain induk sama`() {
        assertTrue(SocialMediaExtractor.cookieJarVisible(
            "drive.google.com", "drive.usercontent.google.com"))
        assertTrue(SocialMediaExtractor.cookieJarVisible(
            "google.com", "mail.google.com"))
        assertTrue(SocialMediaExtractor.cookieJarVisible(
            "EXAMPLE.com", "example.com"))
        assertFalse(SocialMediaExtractor.cookieJarVisible(
            "drive.google.com", "evil.com"))
        assertFalse(SocialMediaExtractor.cookieJarVisible(
            "google.com", "google.com.evil.com"))
        assertFalse(SocialMediaExtractor.cookieJarVisible("", "x.com"))
        assertFalse(SocialMediaExtractor.cookieJarVisible("x.com", ""))
        // Akhiran publik dua lapis (#285): a.co.uk dan b.co.uk adalah
        // dua pemilik berbeda; subdomain satu domain teregistrasi sama
        // tetap satu induk.
        assertFalse(SocialMediaExtractor.cookieJarVisible("a.co.uk", "b.co.uk"))
        // Akhiran multi-penyewa (#322): penyewa berbeda di bawah
        // suffix yang sama adalah pemilik berbeda.
        assertFalse(SocialMediaExtractor.cookieJarVisible(
            "a.blogspot.com", "b.blogspot.com"))
        assertFalse(SocialMediaExtractor.cookieJarVisible(
            "a.netlify.app", "b.netlify.app"))
        assertTrue(SocialMediaExtractor.cookieJarVisible(
            "www.a.blogspot.com", "a.blogspot.com"))
        assertTrue(SocialMediaExtractor.cookieJarVisible(
            "a.example.co.uk", "b.example.co.uk"))
        assertFalse(SocialMediaExtractor.cookieJarVisible(
            "tenant1.github.io", "tenant2.github.io"))
        // Alamat IP hanya terlihat oleh dirinya sendiri (#285).
        assertFalse(SocialMediaExtractor.cookieJarVisible("10.0.0.7", "192.168.0.7"))
        assertTrue(SocialMediaExtractor.cookieJarVisible("10.0.0.7", "10.0.0.7"))
    }

    @Test
    fun `bestAdaptivePair - seri codec dimenangkan height lalu bitrate tertinggi`() {
        // Urutan itag menaik seperti respons Innertube nyata: 144p AVC
        // datang lebih dulu, tetapi 1080p AVC wajib menang (issue #388);
        // VP9 1080p tak mengalahkan kelas AVC; seri 1080p AVC dimenangkan
        // bitrate lebih besar.
        val sd = org.json.JSONObject(
            """{"adaptiveFormats":[
              {"itag":160,"mimeType":"video/mp4; codecs=\"avc1.42\"","url":"https://x/160","height":144,"bitrate":100},
              {"itag":136,"mimeType":"video/mp4; codecs=\"avc1.4d\"","url":"https://x/136","height":720,"bitrate":800},
              {"itag":248,"mimeType":"video/webm; codecs=\"vp9\"","url":"https://x/248","height":1080,"bitrate":2500},
              {"itag":299,"mimeType":"video/mp4; codecs=\"avc1.64\"","url":"https://x/299","height":1080,"bitrate":1500},
              {"itag":137,"mimeType":"video/mp4; codecs=\"avc1.64\"","url":"https://x/137","height":1080,"bitrate":2000},
              {"itag":139,"mimeType":"audio/mp4","url":"https://x/139"},
              {"itag":140,"mimeType":"audio/mp4","url":"https://x/140"}
            ]}"""
        )
        val (video, audio) = SocialMediaExtractor.bestAdaptivePair(sd)
        assertEquals("https://x/137", video)
        assertEquals("https://x/140", audio)
        assertEquals("" to "", SocialMediaExtractor.bestAdaptivePair(null))
    }

    @Test
    fun `bestAdaptivePair - seri peringkat audio dimenangkan bitrate tertinggi`() {
        // Tanpa itag 139/140 semua audio berbagi peringkat 2: bitrate
        // tertinggi wajib menang walau datang belakangan (issue #421);
        // ekspektasi dibuktikan lebih dulu lewat port Python 1:1.
        val sd = org.json.JSONObject(
            """{"adaptiveFormats":[
              {"itag":256,"mimeType":"audio/mp4","url":"https://x/256","bitrate":60000},
              {"itag":141,"mimeType":"audio/mp4","url":"https://x/141","bitrate":256000}
            ]}"""
        )
        assertEquals("https://x/141", SocialMediaExtractor.bestAdaptivePair(sd).second)
        // Peringkat itag tetap utama: 139 (peringkat 1) mengalahkan
        // peringkat 2 berbitrate lebih besar.
        val sd2 = org.json.JSONObject(
            """{"adaptiveFormats":[
              {"itag":141,"mimeType":"audio/mp4","url":"https://x/141","bitrate":256000},
              {"itag":139,"mimeType":"audio/mp4","url":"https://x/139","bitrate":48000}
            ]}"""
        )
        assertEquals("https://x/139", SocialMediaExtractor.bestAdaptivePair(sd2).second)
    }

    @Test
    fun `parseContentDispositionFileName - bentuk quoted, token, dan RFC 5987`() {
        assertEquals("clip final.mp4", SocialMediaExtractor.parseContentDispositionFileName(
            "attachment; filename=\"clip final.mp4\""))
        assertEquals("clip.mp4", SocialMediaExtractor.parseContentDispositionFileName(
            "attachment; filename=clip.mp4"))
        assertEquals("video baru.mp4", SocialMediaExtractor.parseContentDispositionFileName(
            "attachment; filename*=UTF-8''video%20baru.mp4"))
        assertEquals(null, SocialMediaExtractor.parseContentDispositionFileName(null))
        assertEquals(null, SocialMediaExtractor.parseContentDispositionFileName("attachment"))
    }
}
