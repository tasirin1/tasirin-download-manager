package com.tasirin.httpdownloadmanager.download

/** Parser playlist master HLS (m3u8) — murni JVM, bisa diuji unit.
 *  Mengembalikan daftar varian (kualitas), null bila bukan master playlist. */
object HlsParser {

    /** Parse isi master playlist. baseUrl dipakai untuk melengkapi URL varian
     *  yang relatif. Hasil diurutkan menurun menurut bandwidth. */
    fun parseMaster(body: String, baseUrl: String): List<HlsVariant>? {
        if (!body.contains("#EXT-X-STREAM-INF")) return null
        val variants = mutableListOf<HlsVariant>()
        val lines = body.split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val next = lines.getOrNull(i + 1)?.trim().orEmpty()
                if (next.isNotEmpty() && !next.startsWith("#")) {
                    val bandwidth = Regex("BANDWIDTH=(\\d+)")
                        .find(line)?.groupValues?.get(1)?.toLongOrNull()
                    val res = Regex("RESOLUTION=(\\d+x\\d+)")
                        .find(line)?.groupValues?.get(1)
                    val name = Regex("NAME=\"([^\"]+)\"")
                        .find(line)?.groupValues?.get(1)
                    val kbps = bandwidth?.div(1000L) ?: 0L
                    val label = name
                        ?: (res?.let { "$it · $kbps kbps" })
                        ?: "$kbps kbps"
                    variants.add(HlsVariant(label, resolveUrl(baseUrl, next), bandwidth ?: 0L))
                }
                i += 2
                continue
            }
            i++
        }
        return if (variants.isEmpty()) null else variants.sortedByDescending { it.bandwidth }
    }

    /** Gabungkan URL varian relatif dengan URL base playlist. */
    fun resolveUrl(base: String, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        if (relative.startsWith("/")) {
            val u = java.net.URL(base)
            return "${u.protocol}://${u.host}${if (u.port > 0) ":${u.port}" else ""}$relative"
        }
        val idx = base.lastIndexOf('/')
        return if (idx > 0) base.substring(0, idx + 1) + relative else relative
    }
}
