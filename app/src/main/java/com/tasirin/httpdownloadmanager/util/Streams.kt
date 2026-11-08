package com.tasirin.httpdownloadmanager.util

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Batas baca 0-byte beruntun: kontrak InputStream membolehkan read() = 0
 *  (bukan EOF). Tanpa guard, stream socket yang macet membuat loop busy-loop
 *  selamanya menahan thread pemanggil — pola sama seperti guard MAX_ZERO_READS
 *  di HttpBody (jilid 12) yang belum mencakup helper bersama ini. */
private const val MAX_ZERO_READS = 32

/** Decode nilai persen-enkode dengan charset bernama (dipakai
 *  filename* RFC 5987, issue #1242): TIDAK seperti URLDecoder,
 *  '+' adalah literal di sini (bukan spasi) dan charset dari label
 *  nilai dihormati, bukan dipaksa UTF-8. Urutan persen rusak
 *  dibiarkan apa adanya. */
fun decodePercentEncoded(encoded: String, charsetName: String): String? =
    runCatching {
        val bytes = java.io.ByteArrayOutputStream(encoded.length)
        var i = 0
        while (i < encoded.length) {
            val c = encoded[i]
            if (c == '%' && i + 2 < encoded.length) {
                // Hanya digit heksa sungguhan: toIntOrNull mengikuti
                // parseInt yang menerima tanda, jadi "%-1"/"%+2" akan
                // ter-decode jadi byte bila tak disaring dulu (issue #1261).
                val hex = encoded.substring(i + 1, i + 3)
                val v = if (hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
                    hex.toIntOrNull(16) else null
                if (v != null) {
                    bytes.write(v)
                    i += 3
                    continue
                }
            }
            bytes.write(c.code and 0xFF)
            i++
        }
        String(bytes.toByteArray(), charset(charsetName))
    }.getOrNull()

/** Baca stream paling banyak [max] byte; aman untuk probe URL yang
 *  tidak dikenal (hindari OOM dari body raksasa). Stream TIDAK ditutup
 *  oleh fungsi ini (issue #422): kepemilikan tetap di pemanggil, yang
 *  wajib menutupnya sendiri (use{} / disconnect()) — dok lama yang
 *  menjanjikan "lalu tutup" membuat pemanggil baru bisa lupa menutup
 *  dan membocorkan koneksi. Dipindah ke sini agar bisa diuji unit di
 *  CI (murni JVM). */
fun readBounded(input: InputStream, max: Int): String =
    readBoundedFlagged(input, max).text

/** Hasil baca terbatas + penanda terpotong batas (issue #1241):
 *  pemanggil yang memvonis isi dari EKOR badan (mis. #EXT-X-ENDLIST
 *  di playlist HLS) wajib membedakan "isi memang tak ada" dari
 *  "isi terpotong plafon", kalau tidak vonisnya bohong. */
class BoundedRead(val text: String, val truncated: Boolean)

fun readBoundedFlagged(input: InputStream, max: Int): BoundedRead {
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
    // Plafon tercapai persis: satu byte probe membedakan aliran yang
    // memang habis dari yang terpotong batas kita.
    val truncated = remaining == 0 &&
        runCatching { input.read() }.getOrDefault(0) != -1
    return BoundedRead(String(out.toByteArray(), Charsets.UTF_8), truncated)
}
