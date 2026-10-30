package com.tasirin.httpdownloadmanager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdaterTest {

    @Test
    fun `nama lawas dibaca dari kode di nama file`() {
        assertEquals(
            101191,
            Updater.codeFromRelease(
                "v1.0.1191",
                "tasirin-download-manager-v1.0.1191-101191.apk"
            )
        )
    }

    @Test
    fun `nama pendek dibaca dari tag rilis`() {
        assertEquals(
            101191,
            Updater.codeFromRelease("v1.0.1191", "tasirin-download-manager-v1.0.1191.apk")
        )
    }

    @Test
    fun `tag skema rollover dibaca sebagai minor kali 1000 tambah patch`() {
        // Keputusan owner 2026-10-28: v1.0.999 lanjut v1.1.0, bukan
        // v1.0.1000+. Nomor lari minor*1000+patch menjaga kode tetap
        // 100000+lari sehingga urutan rilis lama dan baru menyatu.
        assertEquals(
            101545,
            Updater.codeFromRelease("v1.1.545", "tasirin-download-manager-v1.1.545.apk")
        )
        assertEquals(
            101000,
            Updater.codeFromRelease("v1.1.0", "tasirin-download-manager-v1.1.0.apk")
        )
        // Tag lama ber-patch >999 (terbit sebelum skema rollover)
        // terpetakan ke kode yang sama dengan tag barunya.
        assertEquals(
            101545,
            Updater.codeFromRelease("v1.0.1545", "tasirin-download-manager-v1.0.1545.apk")
        )
    }

    @Test
    fun `tag mayor di atas satu terbaca dan selalu di atas kode satu x`() {
        // Issue #889: regex lama mengunci mayor '1' sehingga rilis
        // v2.x ditolak total dan tak pernah ditawarkan. Mayor >= 2
        // kini dipetakan ke pita major*100jt+lari.
        assertEquals(
            200000000,
            Updater.codeFromRelease("v2.0.0", "tasirin-download-manager-v2.0.0.apk")
        )
        assertEquals(
            200001005,
            Updater.codeFromRelease("v2.1.5", "tasirin-download-manager-v2.1.5.apk")
        )
        // Pita mayor 2 di atas kode 1.x maksimum yang mungkin terbit
        // (minor 20000 + patch 20000000 -> lari 20000000 -> 20100000).
        assertEquals(
            20100000,
            Updater.codeFromRelease("v1.20000.0", "tasirin-download-manager-v1.20000.0.apk")
        )
    }

    @Test
    fun `tag mayor nol dan mayor raksasa ditolak`() {
        assertNull(
            Updater.codeFromRelease("v0.9.9", "tasirin-download-manager-v0.9.9.apk")
        )
        assertNull(
            Updater.codeFromRelease("v21.0.0", "tasirin-download-manager-v21.0.0.apk")
        )
    }

    @Test
    fun `tag tanpa awalan v tetap valid`() {
        assertEquals(
            100005,
            Updater.codeFromRelease("1.0.5", "tasirin-download-manager-v1.0.5.apk")
        )
    }

    @Test
    fun `nama lawas menang atas tag yang beda`() {
        assertEquals(
            101191,
            Updater.codeFromRelease(
                "v1.0.5",
                "tasirin-download-manager-v1.0.5-101191.apk"
            )
        )
    }

    @Test
    fun `nama apk generik ikut kode tag yang valid`() {
        // Nama asset hanya dipakai untuk format lawas; pemanggil memfilter
        // `.apk` dulu sehingga asset generik sah memakai kode dari tag.
        assertEquals(
            101191,
            Updater.codeFromRelease("v1.0.1191", "aplikasi.apk")
        )
    }

    @Test
    fun `kode lawas overflow ditolak bukan jatuh ke tag`() {
        assertNull(
            Updater.codeFromRelease(
                "v1.0.5",
                "tasirin-download-manager-v1.0.5-99999999999.apk"
            )
        )
    }

    @Test
    fun `rilis tanpa apk menghasilkan null`() {
        val r = org.json.JSONObject()
            .put("tag_name", "v1.0.9")
            .put("html_url", "https://example.com/r")
            .put("assets", org.json.JSONArray().put(org.json.JSONObject()
                .put("name", "mapping.txt").put("browser_download_url", "https://example.com/m")))
        org.junit.Assert.assertNull(Updater.bestFromRelease(r))
    }

    @Test
    fun `daftar rilis memilih kode tertinggi lintas rilis`() {
        fun rel(tag: String, code: Int) = org.json.JSONObject()
            .put("tag_name", tag).put("html_url", "https://example.com/" + tag)
            .put("assets", org.json.JSONArray().put(org.json.JSONObject()
                .put("name", "tasirin-download-manager-" + tag + ".apk")
                .put("browser_download_url", "https://example.com/" + code + ".apk")
                .put("size", 1)))
        val arr = org.json.JSONArray().put(rel("v1.0.5", 100005)).put(rel("v1.0.9", 100009))
        org.junit.Assert.assertEquals(100009, Updater.bestFromReleasesList(arr)!!.versionCode)
        org.junit.Assert.assertNull(Updater.bestFromReleasesList(org.json.JSONArray()))
    }

    @Test
    fun `prerelease berkode lebih tinggi tidak pernah menang atas rilis stabil`() {
        fun rel(tag: String, pre: Boolean) = org.json.JSONObject()
            .put("tag_name", tag).put("html_url", "https://example.com/" + tag)
            .put("prerelease", pre)
            .put("assets", org.json.JSONArray().put(org.json.JSONObject()
                .put("name", "tasirin-download-manager-" + tag + ".apk")
                .put("browser_download_url", "https://example.com/" + tag + ".apk")
                .put("size", 1)))
        // Prerelease tunggal -> null (issue #637).
        assertNull(Updater.bestFromRelease(rel("v1.0.99", true)))
        // Campuran: yang stabil menang walau kodenya lebih rendah.
        val arr = org.json.JSONArray().put(rel("v1.0.99", true)).put(rel("v1.0.9", false))
        assertEquals(100009, Updater.bestFromReleasesList(arr)!!.versionCode)
    }

    @Test
    fun `tag rusak dan nama tanpa kode menghasilkan null`() {
        assertNull(Updater.codeFromRelease("release-foo", "tasirin-download-manager-v1.0.1191.apk"))
        assertNull(Updater.codeFromRelease("release-foo", "mapping.txt"))
        assertNull(Updater.codeFromRelease("", ""))
    }
}
