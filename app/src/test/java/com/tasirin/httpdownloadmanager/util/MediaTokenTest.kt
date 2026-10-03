package com.tasirin.httpdownloadmanager.util

import org.junit.Assert.assertNull
import org.junit.Test

class MediaTokenTest {

    @Test
    fun `token raksasa - ditolak sebelum decode`() {
        // Guard panjang wajib bekerja tanpa menyentuh Base64 (tanpa alokasi besar).
        assertNull(MediaLibrary.decodeToken("a".repeat(MediaLibrary.MAX_TOKEN_LENGTH + 1)))
        assertNull(MediaLibrary.decodeToken("a".repeat(1_000_000)))
    }
}
