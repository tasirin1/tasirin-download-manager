package com.tasirin.httpdownloadmanager.util

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Batas baca 0-byte beruntun: kontrak InputStream membolehkan read() = 0
 *  (bukan EOF). Tanpa guard, stream socket yang macet membuat loop busy-loop
 *  selamanya menahan thread pemanggil — pola sama seperti guard MAX_ZERO_READS
 *  di HttpBody (jilid 12) yang belum mencakup helper bersama ini. */
private const val MAX_ZERO_READS = 32

/** Baca stream paling banyak [max] byte lalu tutup; aman untuk probe URL yang
 *  tidak dikenal (hindari OOM dari body raksasa). Dipindah ke sini agar bisa
 *  diuji unit di CI (murni JVM). */
fun readBounded(input: InputStream, max: Int): String {
    val buf = ByteArray(16 * 1024)
    // Kapasitas awal kecil lalu tumbuh: ByteArrayOutputStream(max) mengalokasi
    // penuh di muka (s.d. 16MB) untuk tiap probe walau body aslinya kecil.
    val out = ByteArrayOutputStream(minOf(max, 16 * 1024))
    var remaining = max
    var zeroStreak = 0
    while (remaining > 0) {
        val n = input.read(buf, 0, minOf(buf.size, remaining))
        if (n < 0) break
        if (n == 0) {
            // Stream macet: kembalikan parsial yang sudah terkumpul, bukan
            // gantung selamanya. Pemanggil (probe HLS/ekstraktor) memperlakukan
            // body terpotong sebagai gagal parse seperti biasa.
            if (++zeroStreak >= MAX_ZERO_READS) break
            continue
        }
        zeroStreak = 0
        out.write(buf, 0, n)
        remaining -= n
    }
    return String(out.toByteArray(), Charsets.UTF_8)
}
