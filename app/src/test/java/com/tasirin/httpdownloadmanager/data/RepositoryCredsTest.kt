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
    fun `field sehat - jangan timpa simpanan`() {
        assertFalse(shouldRestoreStored("user", "v1:abc"))
        assertFalse(shouldRestoreStored("", ""))
        assertFalse(shouldRestoreStored("", null))
        assertFalse(shouldRestoreStored("user", ""))
    }
}
