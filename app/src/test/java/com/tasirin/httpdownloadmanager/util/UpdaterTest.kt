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
    fun `tag rusak dan nama tanpa kode menghasilkan null`() {
        assertNull(Updater.codeFromRelease("release-foo", "tasirin-download-manager-v1.0.1191.apk"))
        assertNull(Updater.codeFromRelease("v1.0.1191", "aplikasi.apk"))
        assertNull(Updater.codeFromRelease("", ""))
    }
}
