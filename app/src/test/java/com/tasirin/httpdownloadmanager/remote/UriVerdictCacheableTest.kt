package com.tasirin.httpdownloadmanager.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #1455: hanya MediaStore + dua authority dokumen SAF yang
 *  vonis izin URI-nya boleh di-cache TTL. Nilai harapan dibuktikan
 *  port Python 1:1 (audit-dm-r15/port-1455.py). */
class UriVerdictCacheableTest {

    @Test
    fun `authority yang membayar IPC boleh di-cache`() {
        assertTrue(isUriVerdictCacheable("media"))
        assertTrue(isUriVerdictCacheable("com.android.externalstorage.documents"))
        assertTrue(isUriVerdictCacheable("com.android.providers.downloads.documents"))
    }

    @Test
    fun `authority lain selalu dinilai segar`() {
        assertFalse(isUriVerdictCacheable("com.example.provider"))
        assertFalse(isUriVerdictCacheable(""))
        assertFalse(isUriVerdictCacheable(null))
    }
}
