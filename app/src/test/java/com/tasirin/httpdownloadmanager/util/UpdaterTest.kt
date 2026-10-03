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
    fun `tag rusak dan nama tanpa kode menghasilkan null`() {
        assertNull(Updater.codeFromRelease("release-foo", "tasirin-download-manager-v1.0.1191.apk"))
        assertNull(Updater.codeFromRelease("release-foo", "mapping.txt"))
        assertNull(Updater.codeFromRelease("", ""))
    }
}
