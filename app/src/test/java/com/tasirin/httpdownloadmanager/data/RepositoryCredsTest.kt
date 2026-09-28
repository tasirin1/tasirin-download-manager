package com.tasirin.httpdownloadmanager.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryCredsTest {

    @Test
    fun `simpanan tak kosong tapi polos kosong - pulihkan`() {
        assertTrue(shouldRestoreStored("", "v1:abc"))
        assertTrue(shouldRestoreStored("", "plain:user"))
        assertTrue(shouldRestoreStored("", "legacy-plaintext"))
    }

    @Test
    fun `kosong terdegradasi tanpa clear - blokir tulis`() {
        assertTrue(shouldBlockEmptySave(true, true, false))
    }

    @Test
    fun `kosong terdegradasi via clear eksplisit - tulis`() {
        assertFalse(shouldBlockEmptySave(true, true, true))
    }

    @Test
    fun `kosong sehat atau isi - tulis`() {
        assertFalse(shouldBlockEmptySave(true, false, false))
        assertFalse(shouldBlockEmptySave(false, true, false))
        assertFalse(shouldBlockEmptySave(false, false, false))
    }

    @Test
    fun `field sehat - jangan timpa simpanan`() {
        assertFalse(shouldRestoreStored("user", "v1:abc"))
        assertFalse(shouldRestoreStored("", ""))
        assertFalse(shouldRestoreStored("", null))
        assertFalse(shouldRestoreStored("user", ""))
    }
}
