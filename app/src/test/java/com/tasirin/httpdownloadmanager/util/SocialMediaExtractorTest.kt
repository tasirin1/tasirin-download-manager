package com.tasirin.httpdownloadmanager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class SocialMediaExtractorTest {

    // audit-ignore: maintenance_marker (nama domain resmi situs, bukan marker)
    private val hhDomain = "hentaihaven.xxx"

    @Test
    fun `isSocialMediaUrl - deteksi URL beranda sosial`() {
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://www.youtube.com/watch?v=abc"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://youtu.be/abc"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://www.tiktok.com/@user/video/123"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://vm.tiktok.com/abc/"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://www.instagram.com/p/abc/"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://www.instagram.com/reel/abc/"))
        assertTrue(SocialMediaExtractor.isSocialMediaUrl("https://x.com/username/status/123"))
        // Facebook tidak lagi didukung — jangan dikenali sebagai media sosial.
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("https://www.facebook.com/share/v/abc/"))
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
        // CDN Facebook tidak dikenali (fitur FB dihapus).
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
    fun `parsePornhubPage - quality tanpa kutip dan ber-akhiran p`() {
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
    fun `isSocialMediaUrl - URL kosong dan bukan HTTP`() {
        assertFalse(SocialMediaExtractor.isSocialMediaUrl(""))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("ftp://youtube.com/watch"))
        assertFalse(SocialMediaExtractor.isSocialMediaUrl("file:///etc/passwd"))
    }
}
