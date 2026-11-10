package com.tasirin.httpdownloadmanager.download

import org.junit.Assert.assertEquals
import org.junit.Test

class AttemptUrlTest {

    @Test
    fun `url koneksi attempt memakai tangkapan selama url item tak berubah`() {
        // Nilai harapan dibuktikan port Python 1:1 (audit-dm-r15/port-1446.py).
        val origin = "https://github.com/tasirin1/app/releases/download/v1/app.apk"
        val effective = "https://objects.githubusercontent.com/github-production-release-asset/app.apk?sig=abc"
        val other = "https://cdn.example.com/mirror/app.apk"

        // Tangkapan dipakai bila URL item tak berubah sejak awal attempt.
        assertEquals(effective, attemptConnectionUrl(origin, origin, effective))
        // Tanpa tangkapan -> URL item.
        assertEquals(origin, attemptConnectionUrl(origin, origin, null))
        // Tangkapan kosong -> URL item.
        assertEquals(origin, attemptConnectionUrl(origin, origin, ""))
        // Tangkapan == asal (tanpa redirect) -> sama saja.
        assertEquals(origin, attemptConnectionUrl(origin, origin, origin))
        // URL item berubah di tengah attempt -> tangkapan dibuang.
        assertEquals(other, attemptConnectionUrl(other, origin, effective))
        // URL berubah + tanpa tangkapan -> URL item segar.
        assertEquals(other, attemptConnectionUrl(other, origin, null))
        // Item sudah menunjuk URL efektif (asal beda) -> item menang.
        assertEquals(effective, attemptConnectionUrl(effective, origin, effective))
    }
}
