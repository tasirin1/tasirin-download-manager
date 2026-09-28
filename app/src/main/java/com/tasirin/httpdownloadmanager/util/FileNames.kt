package com.tasirin.httpdownloadmanager.util

import java.util.Locale

object FileNames {

    fun unique(fileName: String, taken: (String) -> Boolean): String {
        if (!taken(fileName)) return fileName
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var i = 1
        while (taken("$base ($i)$ext")) i++
        return "$base ($i)$ext"
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
            val dot = clean.lastIndexOf('.')
            clean = if (dot in 1..190) clean.substring(0, 190) + clean.substring(dot)
            else clean.substring(0, 200)
        }
        // Batas filesystem dalam byte (255): 200 char CJK = 600 byte sehingga
        // gagal ENAMETOOLONG. Pangkas per karakter (tak belah UTF-8) sambil
        // mempertahankan ekstensi.
        if (clean.toByteArray(Charsets.UTF_8).size > 240) {
            val dot = clean.lastIndexOf('.')
            val ext = if (dot in 1..239) clean.substring(dot) else ""
            var base = if (dot in 1..239) clean.substring(0, dot) else clean
            val budget = 240 - ext.toByteArray(Charsets.UTF_8).size
            while (base.isNotEmpty() && base.toByteArray(Charsets.UTF_8).size > budget) {
                base = base.dropLast(1)
            }
            clean = (base + ext).trimEnd('.', ' ')
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
