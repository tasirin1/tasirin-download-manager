package com.tasirin.httpdownloadmanager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinUtilTest {

    @Test
    fun normalizePinHash_kosong_returnsNull() {
        assertNull(StoragePrefs.normalizePinHash(""))
    }

    @Test
    fun normalizePinHash_spasi_dihashBukanNull() {
        // normalisasi murni tidak trim (trim dilakukan getServerPin sebelum
        // memanggilnya): string spasi tetap menjadi hash.
        assertEquals(sha256Hex("  "), StoragePrefs.normalizePinHash("  "))
    }

    @Test
    fun normalizePinHash_hash64hex_dipakaiLangsung() {
        val hash = sha256Hex("1234")
        assertEquals(hash, StoragePrefs.normalizePinHash(hash))
    }

    @Test
    fun normalizePinHash_plaintextLama_dihash() {
        assertEquals(sha256Hex("1234"), StoragePrefs.normalizePinHash("1234"))
    }

    @Test
    fun constantEquals_sama_true() {
        assertTrue(StoragePrefs.constantEquals("abc", "abc"))
    }

    @Test
    fun constantEquals_beda_false() {
        assertFalse(StoragePrefs.constantEquals("abc", "abd"))
        assertFalse(StoragePrefs.constantEquals("abc", "abcd"))
        assertFalse(StoragePrefs.constantEquals("", "x"))
    }

    @Test
    fun pinHash_menghasilkanSaltDanVerifikasiBenar() {
        val stored = PinHash.hash("123456")
        assertTrue(stored.startsWith("\$pbkdf2-sha1\$"))
        assertTrue(PinHash.isModern(stored))
        assertTrue(PinHash.verify("123456", stored))
        assertFalse(PinHash.verify("123457", stored))
        assertNotEquals(PinHash.hash("123456"), stored)
    }

    @Test
    fun pinHash_deterministikPadaSaltSama() {
        val salt = ByteArray(16) { it.toByte() }
        assertEquals(PinHash.hash("1234", salt, 10_000), PinHash.hash("1234", salt, 10_000))
    }

    @Test
    fun pinHash_menolakHexHurufBesar() {
        val salt = ByteArray(16) { it.toByte() }
        val valid = PinHash.hash("1234", salt, 10_000)
        assertTrue(PinHash.verify("1234", valid))
        // Huruf-besarkan HANYA salt+digest (prefix/iterasi utuh): memastikan
        // penolakan datang dari parser hex, bukan dari prefix yang rusak.
        val last = valid.lastIndexOf('$')
        val prev = valid.lastIndexOf('$', last - 1)
        val upper = valid.substring(0, prev + 1) + valid.substring(prev + 1).uppercase()
        assertFalse(PinHash.verify("1234", upper))
    }

    @Test
    fun pinHash_menolakFormatRusak() {
        assertFalse(PinHash.isModern(""))
        assertFalse(PinHash.isModern("plain"))
        assertFalse(PinHash.verify("1234", "bukan-token"))
    }
}
