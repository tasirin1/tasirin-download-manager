package com.tasirin.httpdownloadmanager.download

import java.util.Locale

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
    private val LANGUAGE_RE: Regex = Regex("LANGUAGE=\"([^\"]+)\"")
    // Prefiks skema URI (RFC 3986): ALPHA *(ALPHA/DIGIT/+/-/.) lalu ":".
    // Himpunan karakternya tak memuat "/", jadi kecocokan menjamin titik
    // dua berada di segmen pertama — skema tanpa "//" (data:, urn:,
    // http:foo) ikut tertangkap, sedangkan nama relatif ber-titik-dua
    // SESUDAH "/" pertama (dir/a:b.ts) tidak (issue #464).
    private val SCHEME_RE: Regex = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    /** True bila body adalah media playlist HLS (daftar segmen langsung),
     *  bukan master playlist: tidak ada STREAM-INF tapi ada tag media/segmen.
     *  Murni; dipakai probe agar playlist single-quality tidak disangka
     *  "bukan HLS" (null) melainkan "HLS tanpa pilihan kualitas" (kosong). */
    fun isMediaPlaylist(body: String): Boolean {
        if (body.contains("#EXT-X-STREAM-INF")) return false
        return body.contains("#EXTINF") || body.contains("#EXT-X-TARGETDURATION")
    }

    /** URI absolut berskema selain http/https (ftp:, rtmp:, data:,
     *  urn:, ...): tak bisa diunduh engine, jadi tak boleh masuk daftar
     *  kandidat varian, rendisi, atau segmen (issue #453; predikat
     *  bersama). */
    private fun isForeignAbsoluteUri(uri: String): Boolean =
        SCHEME_RE.containsMatchIn(uri) &&
            !uri.startsWith("http://", ignoreCase = true) &&
            !uri.startsWith("https://", ignoreCase = true)

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
                if (next.isNotEmpty() && !next.startsWith("#") && !isForeignAbsoluteUri(next)) {
                    // Guard contains dulu: regex find 6x per baris varian boros
                    // bila atributnya tidak ada di baris tersebut.
                    val bandwidth = if (line.contains("BANDWIDTH")) BANDWIDTH_RE
                        .find(line)?.groupValues?.get(1)?.toLongOrNull() else null
                    val res = if (line.contains("RESOLUTION")) RESOLUTION_RE
                        .find(line)?.groupValues?.get(1) else null
                    val name = if (line.contains("NAME=")) NAME_RE
                        .find(line)?.groupValues?.get(1) else null
                    val audioGroup = if (line.contains("AUDIO")) AUDIO_RE
                        .find(line)?.groupValues?.get(1) else null
                    val frameRate = if (line.contains("FRAME-RATE")) FRAME_RATE_RE
                        .find(line)?.groupValues?.get(1)?.toDoubleOrNull()?.toInt() ?: 0 else 0
                    // Guard contains seperti regex lain di atas: baris varian
                    // tanpa RESOLUTION/CODECS tak perlu membayar find().
                    val height = if (line.contains("RESOLUTION")) RESOLUTION_HEIGHT_RE
                        .find(line)?.groupValues?.let { g ->
                            // Label kualitas = dimensi terkecil: video portrait
                            // 720x1280 tetap "720p" (sesuai HlsParserTest).
                            val w = g[1].toIntOrNull()
                            val h = g[2].toIntOrNull()
                            if (w != null && h != null) minOf(w, h) else 0
                        } ?: 0 else 0
                    val codecs = if (line.contains("CODECS")) CODECS_RE
                        .find(line)?.groupValues?.get(1).orEmpty() else ""
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
                    i += 2
                    continue
                }
            }
            i++
        }
        return if (variants.isEmpty()) null else variants.sortedByDescending { it.bandwidth }
    }

    /** Fallback longgar bila [parseMaster] gagal (format STREAM-INF tak umum):
     *  pindai pasangan `#EXT-X-STREAM-INF:` + baris URI absolut berikutnya.
     *  Dipindah dari DownloadEngine agar regex varian hanya ada di satu tempat.
     *  Murni; diurut menurun menurut bandwidth, null bila tak ada varian. */
    fun parseMasterLenient(body: String, baseUrl: String): List<HlsVariant>? {
        val out = mutableListOf<HlsVariant>()
        val lines = body.lines()
        for (idx in lines.indices) {
            val ln = lines[idx].trim()
            if (!ln.startsWith("#EXT-X-STREAM-INF:")) continue
            val next = lines.getOrNull(idx + 1)?.trim().orEmpty()
            if (next.isEmpty() || next.startsWith("#")) continue
            if (!next.startsWith("http", ignoreCase = true) && !next.startsWith("/")) continue
            val bw = BANDWIDTH_RE.find(ln)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            // Semantik height SAMA seperti parseMaster (issue #393):
            // dimensi terkecil, agar video portrait 720x1280 tercatat
            // 720 — bukan tinggi mentah 1280 yang mengacaukan peringkat
            // kedekatan resolusi dan label.
            val h = RESOLUTION_HEIGHT_RE.find(ln)?.groupValues?.let { g ->
                val w = g[1].toIntOrNull()
                val hh = g[2].toIntOrNull()
                if (w != null && hh != null) minOf(w, hh) else 0
            } ?: 0
            val codecs = CODECS_RE.find(ln)?.groupValues?.get(1).orEmpty()
            val fps = FRAME_RATE_RE.find(ln)?.groupValues?.get(1)?.toDoubleOrNull()?.toInt() ?: 0
            out.add(HlsVariant("$h p", resolveUrl(baseUrl, next), bw, codecs, null, fps, h))
        }
        return if (out.isEmpty()) null else out.sortedByDescending { it.bandwidth }
    }

    /** Hasil media playlist langsung: daftar URL segmen + durasi #EXTINF-nya.
     *  Satu lintasan (tanpa split/chaining ganda): playlist probe bisa 1 MB
     *  sehingga dua kali [String.lines] + empat list sementara itu boros GC. */
    data class DirectMedia(val segments: List<String>, val durationsUs: List<Long>)

    /** Parse media playlist langsung (bukan master) dalam satu lintasan.
     *  Hanya baris URI segmen yang sah (bukan tag `#`/baris kosong); URL
     *  relatif dilengkapi via [resolveUrl], non-http dibuang. Murni. */
    fun parseDirectMedia(body: String, baseUrl: String): DirectMedia {
        val segments = mutableListOf<String>()
        val durations = mutableListOf<Long>()
        // Durasi dipasangkan ke segmen BERIKUTNYA yang diterima (issue
        // #394): sebelumnya kedua daftar dibangun independen — EXTINF
        // yang segmennya terbuang (URI non-http) tetap terhitung dan
        // menggeser indeks durasi terhadap segmen, padahal konsumen
        // remux membaca durationsUs sejajar indeks segmen. Kini setiap
        // segmen yang diterima selalu mendapat tepat satu durasi
        // (EXTINF tertunda, atau 0 bila tak ada).
        var pendingDurationUs: Long? = null
        for (raw in body.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF:")) {
                val dur = line.substringAfter(":").substringBefore(",").trim()
                    .toDoubleOrNull() ?: 0.0
                pendingDurationUs = hlsSegmentDurationUs(dur)
            } else if (!line.startsWith("#")) {
                // URI absolut berskema non-http (ftp:, dst.) dibuang
                // SEBELUM resolveUrl: resolveUrl memperlakukan baris itu
                // sebagai path relatif dan menempelkannya ke basis http,
                // sehingga hasil rusak "https://host/ftp://..." LOLOS
                // saringan startsWith("http") di bawah sebagai segmen
                // (membatalkan maksud #394; uji pendampingnya gagal di CI
                // run 40705353588 — terbukti lewat port Python 1:1).
                val foreignAbsolute = isForeignAbsoluteUri(line)
                if (!foreignAbsolute) {
                    val url = resolveUrl(baseUrl, line)
                    // Skema tak peka huruf (RFC 3986): segmen HTTPS://
                    // kapital sah dan resolveUrl mempertahankannya —
                    // saringan peka huruf membuangnya diam-diam (issue #463).
                    if (url.startsWith("http", ignoreCase = true)) {
                        segments.add(url)
                        durations.add(pendingDurationUs ?: 0L)
                    }
                }
                pendingDurationUs = null
            }
        }
        return DirectMedia(segments, durations)
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
            if (isForeignAbsoluteUri(uri)) continue
            val language = LANGUAGE_RE.find(attrs)?.groupValues?.get(1).orEmpty()
            val name = NAME_RE.find(attrs)?.groupValues?.get(1).orEmpty()
            renditions.add(HlsRendition(groupId, resolveUrl(baseUrl, uri), attrs.contains("DEFAULT=YES"), language, name))
        }
        return renditions
    }

    /** Gabungkan URL varian relatif dengan URL base playlist.
     *  Tak pernah melempar: base malformed mengembalikan relative apa adanya
     *  agar parser murni tidak men-crash pemanggil engine. */
    fun resolveUrl(base: String, relative: String): String {
        if (relative.isBlank()) return base
        if (relative.startsWith("http://", ignoreCase = true) ||
            relative.startsWith("https://", ignoreCase = true)
        ) return relative
        // URI absolut berskema asing (ftp:, rtmp:, data:, ...) dikembalikan
        // apa adanya: menempelkannya ke direktori basis menghasilkan URL
        // rusak yang justru lolos saringan startsWith("http") di
        // pemanggil seperti mediaSegmentsWithDurations (issue #452;
        // pola yang sama dengan penolakan parseDirectMedia a5b8651).
        if (SCHEME_RE.containsMatchIn(relative)) return relative
        // Basis dipisahkan dari query/fragment dulu: '/' di dalam query
        // (mis. "?next=a/b") tidak boleh dianggap pemisah direktori, dan
        // query basis tidak diwariskan ke segmen (sesuai RFC 3986).
        val cleanBase = base.substringBefore('?').substringBefore('#')
        if (relative.startsWith("//")) {
            // Protocol-relative: warisi skema basis (default https aman
            // bila basis rusak; hanya http/https yang diizinkan).
            val scheme = cleanBase.substringBefore("://", "https").lowercase(Locale.ROOT)
                .takeIf { it == "http" || it == "https" } ?: "https"
            return "$scheme:$relative"
        }
        if (relative.startsWith("/")) {
            // Root-relative butuh host valid; base rusak -> kembalikan
            // relative agar pemanggil memutuskan, bukan crash di sini.
            val root = runCatching {
                val u = java.net.URL(cleanBase)
                "${u.protocol}://${u.host}${if (u.port > 0) ":${u.port}" else ""}"
            }.getOrNull() ?: return relative
            return root + relative
        }
        // Direktori = sampai '/' terakhir, tapi harus di belakang otoritas:
        // pada base tanpa path (mis. "https://host") lastIndexOf jatuh di
        // "//" skema dan akan menelan host bila tidak dijaga.
        val schemeEnd = cleanBase.indexOf("://")
        val authEnd = if (schemeEnd >= 0) schemeEnd + 3 else 0
        val idx = cleanBase.lastIndexOf('/')
        if (idx < authEnd) return "$cleanBase/$relative"
        return cleanBase.substring(0, idx + 1) + relative
    }
}
