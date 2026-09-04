package com.tasirin.httpdownloadmanager.download

data class HlsVariant(
    val name: String,
    val url: String,
    val bandwidth: Long,
    val codecs: String = "",
    val audioGroupId: String? = null,
    val frameRate: Int = 0,
    val height: Int = 0
)

data class HlsRendition(
    val groupId: String,
    val url: String,
    val isDefault: Boolean,
    val language: String = "",
    val name: String = ""
)

/** Parser playlist master HLS (m3u8) — murni JVM, bisa diuji unit.
 *  Mengembalikan daftar varian (kualitas), null bila bukan master playlist. */
object HlsParser {
    private val BANDWIDTH_RE: Regex = Regex("BANDWIDTH=(\\d+)")
    private val RESOLUTION_RE: Regex = Regex("RESOLUTION=(\\d+x\\d+)")
    private val NAME_RE: Regex = Regex("NAME=\"([^\"]+)\"")
    private val AUDIO_RE: Regex = Regex("AUDIO=\"([^\"]+)\"")
    private val CODECS_RE: Regex = Regex("CODECS=\"([^\"]+)\"")
    private val FRAME_RATE_RE: Regex = Regex("FRAME-RATE=([\\d.]+)")
    private val RESOLUTION_HEIGHT_RE: Regex = Regex("RESOLUTION=([\\d]+)x([\\d]+)")
    private val MEDIA_TYPE_RE: Regex = Regex("TYPE=([A-Z]+)")
    private val MEDIA_GROUP_RE: Regex = Regex("GROUP-ID=\"([^\"]+)\"")
    private val MEDIA_URI_RE: Regex = Regex("URI=\"([^\"]+)\"")

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
                    val bandwidth = BANDWIDTH_RE
                        .find(line)?.groupValues?.get(1)?.toLongOrNull()
                    val res = RESOLUTION_RE
                        .find(line)?.groupValues?.get(1)
                    val name = NAME_RE
                        .find(line)?.groupValues?.get(1)
                    val audioGroup = AUDIO_RE
                        .find(line)?.groupValues?.get(1)
                    val frameRate = FRAME_RATE_RE
                        .find(line)?.groupValues?.get(1)?.toDoubleOrNull()?.toInt() ?: 0
                    val height = RESOLUTION_HEIGHT_RE
                        .find(line)?.groupValues?.let { g ->
                            val w = g[1].toIntOrNull()
                            val h = g[2].toIntOrNull()
                            if (w != null && h != null) minOf(w, h) else 0
                        } ?: 0
                    val codecs = CODECS_RE
                        .find(line)?.groupValues?.get(1).orEmpty()
                    val kbps = bandwidth?.div(1000L) ?: 0L
                    val label = name
                        ?: (res?.let { "$it · $kbps kbps" })
                        ?: "$kbps kbps"
                    variants.add(
                        HlsVariant(
                            label, resolveUrl(baseUrl, next),
                            bandwidth ?: 0L, codecs, audioGroup, frameRate, height
                        )
                    )
                }
                i += 2
                continue
            }
            i++
        }
        return if (variants.isEmpty()) null else variants.sortedByDescending { it.bandwidth }
    }

    /** Parse rendition audio (#EXT-X-MEDIA:TYPE=AUDIO) pada master playlist. */
    fun parseAudioRenditions(body: String, baseUrl: String): List<HlsRendition> {
        val renditions = mutableListOf<HlsRendition>()
        for (raw in body.lines()) {
            val line = raw.trim()
            if (!line.startsWith("#EXT-X-MEDIA")) continue
            val attrs = line.substringAfter(":")
            if (MEDIA_TYPE_RE.find(attrs)?.groupValues?.get(1) != "AUDIO") continue
            val groupId = MEDIA_GROUP_RE.find(attrs)?.groupValues?.get(1) ?: continue
            val uri = MEDIA_URI_RE.find(attrs)?.groupValues?.get(1) ?: continue
            val language = LANGUAGE_RE.find(attrs)?.groupValues?.get(1).orEmpty()
            val name = NAME_RE.find(attrs)?.groupValues?.get(1).orEmpty()
            renditions.add(HlsRendition(groupId, resolveUrl(baseUrl, uri), attrs.contains("DEFAULT=YES"), language, name))
        }
        return renditions
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
