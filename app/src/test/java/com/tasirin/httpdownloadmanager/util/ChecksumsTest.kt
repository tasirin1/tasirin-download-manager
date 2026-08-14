package com.tasirin.httpdownloadmanager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChecksumsTest {

    // sha256("abc") = ba7816bf... ; base64 dari byte hash-nya.
    private val sha256AbcHex = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val sha256AbcB64 = "ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0="
    // md5("abc") = 90015098... ; base64 dari byte hash-nya.
    private val md5AbcHex = "900150983cd24fb0d6963f7d28e17f72"
    private val md5AbcB64 = "kAFQmDzST7DWlj99KOF/cg=="

    @Test
    fun `base64Decode - nilai dasar, padding, dan penolakan`() {
        assertEquals("base64", String(Checksums.base64Decode("YmFzZTY0")!!))
        assertEquals("Hello", String(Checksums.base64Decode("SGVsbG8=")!!))
        assertEquals("base64", String(Checksums.base64Decode(" Ym Fz ZTY0 ")!!))
        assertNull(Checksums.base64Decode(""))
        assertNull(Checksums.base64Decode("!"))
        assertNull(Checksums.base64Decode("abcde"))
    }

    @Test
    fun `toHex - hex polos dinormalisasi huruf kecil`() {
        assertEquals(md5AbcHex, Checksums.toHex(md5AbcHex.uppercase(), "MD5"))
        assertEquals(sha256AbcHex, Checksums.toHex(sha256AbcHex.uppercase(), "SHA-256"))
        assertEquals("0000000000000000000000000000000000000000000000000000000000000000",
            Checksums.toHex("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "SHA-256"))
        assertNull(Checksums.toHex("", "MD5"))
        assertNull(Checksums.toHex("ABC!", "MD5"))
    }

    @Test
    fun `parseDigestHeader - base64 dan preferensi sha-256`() {
        assertEquals("sha256:$sha256AbcHex",
            Checksums.parseDigestHeader("sha-256=$sha256AbcB64"))
        assertEquals("md5:$md5AbcHex",
            Checksums.parseDigestHeader("md5=$md5AbcB64"))
        assertEquals("sha256:$sha256AbcHex",
            Checksums.parseDigestHeader("sha-1=IGNORED, sha-256=$sha256AbcB64"))
        assertEquals("sha1:0f8ebf8d8e6f7c0b6d8f0e0d0c0b0a0908070605",
            Checksums.parseDigestHeader("SHA-1=0F8EBF8D8E6F7C0B6D8F0E0D0C0B0A0908070605"))
        assertNull(Checksums.parseDigestHeader("sha-256=!!"))
        assertNull(Checksums.parseDigestHeader("crc32=abc"))
    }

    @Test
    fun `fromHeaders - semua jenis header dan case-insensitive`() {
        assertEquals("sha256:$sha256AbcHex",
            Checksums.fromHeaders(mapOf("Digest" to "sha-256=$sha256AbcB64")))
        assertEquals("sha256:$sha256AbcHex",
            Checksums.fromHeaders(mapOf("digest" to "sha-256=$sha256AbcB64")))
        assertEquals("sha256:$sha256AbcHex",
            Checksums.fromHeaders(mapOf("X-Checksum-Sha256" to sha256AbcHex)))
        assertEquals("md5:$md5AbcHex",
            Checksums.fromHeaders(mapOf("X-Checksum-MD5" to md5AbcHex)))
        assertEquals("md5:$md5AbcHex",
            Checksums.fromHeaders(mapOf("Content-MD5" to md5AbcB64)))
        assertEquals("md5:$md5AbcHex",
            Checksums.fromHeaders(mapOf("Digest" to "md5=$md5AbcB64")))
        assertNull(Checksums.fromHeaders(emptyMap()))
        assertNull(Checksums.fromHeaders(mapOf("ETag" to "\"x\"")))
        assertNull(Checksums.fromHeaders(mapOf("Digest" to "garbage")))
    }
}
