package com.tasirin.httpdownloadmanager.util

import java.util.Locale

object FileNames {

    private const val UNIQUE_MAX_SUFFIX = 999

    fun unique(fileName: String, taken: (String) -> Boolean): String {
        if (!taken(fileName)) return fileName
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var i = 1
        while (i <= UNIQUE_MAX_SUFFIX && taken("$base ($i)$ext")) i++
        if (i <= UNIQUE_MAX_SUFFIX) return "$base ($i)$ext"
        val fallback = "$base (${java.util.UUID.randomUUID().toString().take(8)})$ext"
        if (!taken(fallback)) return fallback
        return "$base (${System.nanoTime()})$ext"
    }

    // Sanitasi nama file: buang separator, karakter kontrol, karakter
    // terlarang Windows (: * ? " < > |) dan nama reserved "." / ".." /
    // CON/PRN/AUX/NUL/COM1-9/LPT1-9; pangkas titik/spasi akhir dan batasi
    // panjang agar aman dipakai sebagai File(downloadDir, nama).
    fun safe(fileName: String): String {
        var clean = fileName.replace('/', '_').replace('\\', '_')
        val sb = StringBuilder(clean.length)
        for (c in clean) {
            if (c.code in 0x00..0x1F || c.code == 0x7F ||
                c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|'
            ) sb.append('_')
            else sb.append(c)
        }
        clean = sb.toString().trim()
        clean = clean.trimEnd('.', ' ')
        if (clean.isEmpty() || clean == "." || clean == "..") return "download"
        val base = clean.substringBefore('.').uppercase(Locale.ROOT)
        if (base in RESERVED_NAMES) clean = "_$clean"
        if (clean.length > 200) {
            // Pertahankan ekstensi utuh seperti jalur byte di bawah:
            // pangkas BASIS ke 200 - panjang ekstensi. Cara lama
            // (substring(0,190) + ekstensi) menggandakan fragmen
            // ekstensi dan bisa menghasilkan 240+ char, sedangkan
            // titik di posisi 191+ jatuh ke potongan polos yang
            // memotong ekstensi ('.mp4' -> '.mp') (issue #934).
            // Ekstensi raksasa (>= 200) diperlakukan sebagai nama
            // biasa, sejajar perlakuan jalur byte (#336). Kelayakan
            // ekstensi dinilai dari PANJANGNYA saja, bukan posisi
            // titik: basis yang sendiri >199 char (titik di indeks
            // 200+) tetap berhak mempertahankan ekstensinya (issue
            // #939, menyamai semantik unique() di berkas ini).
            val dot = clean.lastIndexOf('.')
            val ext = if (dot > 0) clean.substring(dot) else ""
            clean = if (ext.isNotEmpty() && ext.length < 200) {
                clean.substring(0, 200 - ext.length) + ext
            } else {
                clean.substring(0, 200)
            }
        }
        // Batas filesystem dalam byte (255): 200 char CJK = 600 byte sehingga
        // gagal ENAMETOOLONG. Pangkas per karakter (tak belah UTF-8) sambil
        // mempertahankan ekstensi.
        if (clean.toByteArray(Charsets.UTF_8).size > 240) {
            val dot = clean.lastIndexOf('.')
            val extRaw = if (dot in 1..239) clean.substring(dot) else ""
            // Ekstensi yang SENDIRINYA >= 240 byte (nama sampah dari
            // server, mis. "a." + 150 huruf CJK) tidak dipertahankan
            // (#336): budget lama menjadi negatif, loop potong berhenti
            // di cut=0, dan hasilnya ekstensi utuh >240 byte yang tetap
            // gagal ENAMETOOLONG. Ekstensi raksasa diperlakukan sebagai
            // bagian nama biasa lalu dipangkas per byte seperti sisanya.
            val ext = if (extRaw.toByteArray(Charsets.UTF_8).size < 240) extRaw else ""
            val base = if (ext.isNotEmpty()) clean.substring(0, dot) else clean
            val budget = 240 - ext.toByteArray(Charsets.UTF_8).size
            // Hitung per karakter sekali jalan (surrogate pair = 4 byte):
            // loop lama meng-encode ulang seluruh base tiap dropLast (O(n^2)).
            var used = 0
            var cut = 0
            while (cut < base.length) {
                val c = base[cut]
                val w = if (Character.isHighSurrogate(c) && cut + 1 < base.length &&
                    Character.isLowSurrogate(base[cut + 1])) 4
                else when {
                    c <= '\u007F' -> 1
                    c <= '\u07FF' -> 2
                    else -> 3
                }
                if (used + w > budget) break
                used += w
                cut += if (w == 4) 2 else 1
            }
            clean = (base.substring(0, cut) + ext).trimEnd('.', ' ')
            if (clean.isEmpty()) clean = "download"
        }
        return clean
    }

    private val RESERVED_NAMES = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )
}
