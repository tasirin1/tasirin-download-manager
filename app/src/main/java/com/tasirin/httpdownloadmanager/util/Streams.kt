package com.tasirin.httpdownloadmanager.util

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Baca stream paling banyak [max] byte lalu tutup; aman untuk probe URL yang
 *  tidak dikenal (hindari OOM dari body raksasa). Dipindah ke sini agar bisa
 *  diuji unit di CI (murni JVM). */
fun readBounded(input: InputStream, max: Int): String {
    val buf = ByteArray(16 * 1024)
    // Kapasitas awal kecil lalu tumbuh: ByteArrayOutputStream(max) mengalokasi
    // penuh di muka (s.d. 16MB) untuk tiap probe walau body aslinya kecil.
    val out = ByteArrayOutputStream(minOf(max, 16 * 1024))
    var remaining = max
    while (remaining > 0) {
        val n = input.read(buf, 0, minOf(buf.size, remaining))
        if (n < 0) break
        out.write(buf, 0, n)
        remaining -= n
    }
    return String(out.toByteArray(), Charsets.UTF_8)
}
