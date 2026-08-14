package com.tasirin.httpdownloadmanager.util

/** Deteksi checksum dari header respons HTTP (RFC 3230 `Digest`, `Content-MD5`,
 *  `X-Checksum-*`). Murni Kotlin tanpa Android supaya bisa di-unit-test JVM.
 *  Output ternormalisasi "algo:hex" huruf kecil (sha256:/sha1:/md5:), format
 *  yang sama dengan input manual di MainActivity/remote web. */
object Checksums {

    /** Cari nilai header dengan nama case-insensitive. */
    fun header(headers: Map<String, String>, name: String): String? {
        val wanted = name.lowercase()
        for ((k, v) in headers) if (k.lowercase() == wanted) return v
        return null
    }

    /** Ekstrak checksum dari header respons; null bila tidak ada yang dikenali. */
    fun fromHeaders(headers: Map<String, String>): String? {
        val digest = header(headers, "Digest")?.let { parseDigestHeader(it) }
        if (digest != null) return digest
        val xSha256 = header(headers, "X-Checksum-Sha256")?.let { toHex(it, "SHA-256") }
        if (xSha256 != null) return "sha256:$xSha256"
        val xSha1 = header(headers, "X-Checksum-Sha1")?.let { toHex(it, "SHA-1") }
        if (xSha1 != null) return "sha1:$xSha1"
        val xMd5 = header(headers, "X-Checksum-MD5")?.let { toHex(it, "MD5") }
        if (xMd5 != null) return "md5:$xMd5"
        val contentMd5 = header(headers, "Content-MD5")?.let { toHex(it, "MD5") }
        if (contentMd5 != null) return "md5:$contentMd5"
        return null
    }

    /** RFC 3230: `Digest: sha-256=<nilai>, sha-1=<nilai>` — pilih sha-256 dulu. */
    fun parseDigestHeader(value: String): String? {
        val parts = value.split(',').map { it.trim() }
        val preferred = listOf("sha-256" to "SHA-256", "sha-1" to "SHA-1", "md5" to "MD5")
        for ((token, algo) in preferred) {
            val hit = parts.firstOrNull { it.startsWith("$token=", ignoreCase = true) } ?: continue
            val hex = toHex(hit.substringAfter('=').trim(), algo) ?: continue
            return algoPrefix(algo) + hex
        }
        return null
    }

    /** Terima hex polos (32/40/64 karakter) atau base64 (dengan/tanpa padding);
     *  hasil selalu hex huruf kecil. */
    fun toHex(value: String, algo: String): String? {
        val v = value.trim()
        if (v.isEmpty()) return null
        val hexLen = when (algo) {
            "MD5" -> 32
            "SHA-1" -> 40
            else -> 64
        }
        if (v.length == hexLen && v.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return v.lowercase()
        }
        val decoded = base64Decode(v) ?: return null
        if (decoded.isEmpty()) return null
        return Hex.encode(decoded)
    }

    /** Decode base64 standar (alphabet A-Za-z0-9+/), padding opsional. */
    fun base64Decode(input: String): ByteArray? {
        val clean = input.filter { !it.isWhitespace() }
        if (clean.isEmpty() || clean.length % 4 == 1) return null
        var s = clean
        while (s.length % 4 != 0) s += "="
        val out = ArrayList<Byte>(s.length * 3 / 4)
        var i = 0
        while (i < s.length) {
            val c1 = if (s[i] == '=') return null else dec(s[i]) ?: return null
            val c2 = if (s[i + 1] == '=') return null else dec(s[i + 1]) ?: return null
            val c3 = if (s[i + 2] == '=') 0 else dec(s[i + 2]) ?: return null
            val c4 = if (s[i + 3] == '=') 0 else dec(s[i + 3]) ?: return null
            out.add(((c1 shl 2) or (c2 shr 4)).toByte())
            if (s[i + 2] != '=') {
                out.add(((c2 shl 4) or (c3 shr 2)).toByte())
                if (s[i + 3] != '=') out.add(((c3 shl 6) or c4).toByte())
            }
            i += 4
        }
        return out.toByteArray()
    }

    private fun algoPrefix(algo: String): String = when (algo) {
        "SHA-256" -> "sha256:"
        "SHA-1" -> "sha1:"
        else -> "md5:"
    }

    private fun dec(c: Char): Int? = when (c) {
        in 'A'..'Z' -> c - 'A'
        in 'a'..'z' -> c - 'a' + 26
        in '0'..'9' -> c - '0' + 52
        '+' -> 62
        '/' -> 63
        else -> null
    }
}
