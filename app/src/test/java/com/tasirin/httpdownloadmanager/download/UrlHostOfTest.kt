package com.tasirin.httpdownloadmanager.download

import org.junit.Assert.assertEquals
import org.junit.Test

/** Uji helper host urlHostOf (issue #843/#844): klasifikasi platform
 *  di engine berbasis host ter-parse, bukan substring URL utuh.
 *  Nilai harapan dibuktikan dengan port Python 1:1 dari semantiknya
 *  (parse URL -> host -> trim -> buang titik akhir -> huruf kecil;
 *  gagal parse = ""). */
class UrlHostOfTest {

    @Test
    fun `host dinormalkan huruf kecil`() {
        assertEquals("www.youtube.com", urlHostOf("https://WWW.YouTube.com/watch?v=1"))
        assertEquals("example.org", urlHostOf("ftp://EXAMPLE.org./f"))
    }

    @Test
    fun `subdomain dan titik akhir host`() {
        assertEquals(
            "rr1---sn-abc.googlevideo.com",
            urlHostOf("https://rr1---sn-abc.googlevideo.com/videoplayback?x=1")
        )
        assertEquals("googlevideo.com", urlHostOf("https://googlevideo.com./videoplayback"))
        assertEquals(
            "scontent.fcgk4-1.fna.fbcdn.net",
            urlHostOf("https://scontent.fcgk4-1.fna.fbcdn.net/v/t50/x.jpg")
        )
        assertEquals("cdninstagram.com", urlHostOf("https://cdninstagram.com/a"))
    }

    @Test
    fun `domain platform di query bukan host`() {
        // Inti #843/#844: string platform di luar host tak boleh
        // mengklasifikasikan URL sebagai milik platform itu.
        assertEquals("files.example.com", urlHostOf("https://files.example.com/?src=youtube.com/abc"))
    }

    @Test
    fun `url tak valid menghasilkan string kosong`() {
        assertEquals("", urlHostOf("not a url"))
        assertEquals("", urlHostOf(""))
    }
}
