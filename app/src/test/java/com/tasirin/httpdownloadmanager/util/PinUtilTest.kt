package com.tasirin.httpdownloadmanager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
