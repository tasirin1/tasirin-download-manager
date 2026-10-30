package com.tasirin.httpdownloadmanager.data

import org.json.JSONArray
import org.json.JSONObject

/** Serialisasi daftar unduhan ke/dari JSON — murni JVM (tanpa Android),
 *  supaya roundtrip bisa diuji unit di CI. Format JSON identik dengan versi
 *  lama agar data prefs tetap terbaca (tidak perlu migrasi). */
object DownloadItemCodec {

    // Plafon segmen yang diterima jalur muat (issue #733): rencana sah
    // paling banyak 8 segmen (jepit segmentCount di StoragePrefs), jadi
    // 64 memberi ruang kepala besar tanpa membuka alokasi raksasa.
    private const val MAX_DECODED_SEGMENT_INDEX = 64
    private const val MAX_DECODED_SEGMENTS = 64

    fun encode(items: List<DownloadItem>): String {
        val arr = JSONArray()
        items.forEach { item ->
            val o = JSONObject()
            o.put("id", item.id)
            o.put("url", item.url)
            o.put("fileName", item.fileName)
            o.put("state", item.state.name)
            o.put("bytesDownloaded", item.bytesDownloaded)
            o.put("totalBytes", item.totalBytes)
            item.error?.let { o.put("error", it) }
            item.contentUri?.let { o.put("contentUri", it) }
            item.filePath?.let { o.put("filePath", it) }
            o.put("addedAt", item.addedAt)
            o.put("finishedAt", item.finishedAt)
            o.put("nameIsCustom", item.nameIsCustom)
            o.put("autoResume", item.autoResume)
            o.put("username", item.username)
            o.put("password", item.password)
            o.put("headers", item.headers)
            o.put("destination", item.destination)
            o.put("folderPath", item.folderPath)
            o.put("speedLimitKbps", item.speedLimitKbps)
            o.put("priority", item.priority)
            o.put("checksum", item.checksum)
            o.put("checksumVerified", item.checksumVerified)
            val mirrorArr = JSONArray()
            item.mirrors.forEach { mirrorArr.put(it) }
            o.put("mirrors", mirrorArr)
            o.put("monitor", item.monitor)
            o.put("replacesItemId", item.replacesItemId)
            o.put("method", item.method)
            o.put("postBody", item.postBody)
            o.put("etag", item.etag)
            val segArr = JSONArray()
            item.segments.forEach { seg ->
                val so = JSONObject()
                so.put("index", seg.index)
                so.put("start", seg.start)
                so.put("end", seg.end)
                so.put("downloaded", seg.downloaded)
                segArr.put(so)
            }
            o.put("segments", segArr)
            o.put("preferredHeight", item.preferredHeight)
            if (item.preferredAudioLang.isNotEmpty()) o.put("preferredAudioLang", item.preferredAudioLang)
            o.put("progressPercentOverride", item.progressPercentOverride)
            if (item.retryCount > 0) o.put("retryCount", item.retryCount)
            if (item.totalBytesEstimated) o.put("totalBytesEstimated", true)
            arr.put(o)
        }
        return arr.toString()
    }

    /** Parse daftar dari JSON; satu entry korup tidak menghapus daftar
     *  (entry itu dilewati). Bila coerceActiveToPaused, download yang sedang
     *  berjalan saat proses restart dianggap dijeda. Antrean PENDING bukan
     *  status berjalan sehingga dibiarkan agar queue lanjut otomatis. */
    fun decode(raw: String, coerceActiveToPaused: Boolean = true): List<DownloadItem> {
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return decode(arr, coerceActiveToPaused)
    }

    /** Varian array: pemanggil yang sudah memegang JSONArray terparse (mis.
     *  DownloadRepository.load yang butuh panjang + kredensial dari blob yang
     *  sama) tak perlu parse ulang blob besar yang sama berkali-kali. */
    fun decode(arr: JSONArray, coerceActiveToPaused: Boolean = true): List<DownloadItem> {
        val items = mutableListOf<DownloadItem>()
        val seenIds = HashSet<String>()
        for (i in 0 until arr.length()) {
            val item = parseItem(arr.optJSONObject(i), coerceActiveToPaused) ?: continue
            // Id duplikat dari blob korup/asing (issue #942): engine
            // menilai per-id dengan first-match, jadi duplikat kedua
            // akan membeku permanen dan remove(id) menghapus daftarnya
            // tanpa menghapus berkasnya. Pertahankan entri pertama per
            // id — konsisten semantik engine — dan lewati sisanya,
            // sejajar postur defensif codec (#733/#748).
            if (!seenIds.add(item.id)) continue
            items.add(item)
        }
        return items
    }

    private fun parseItem(o: JSONObject?, coerceActiveToPaused: Boolean): DownloadItem? {
        if (o == null) return null
        return runCatching {
            // State tak dikenal berarti entri korup: lewati entri ini saja
            // (sesuai kontrak tes) agar tidak meracuni daftar dan lapisan hilir.
            val rawState = DownloadState.valueOf(o.getString("state"))
            // Hanya DOWNLOADING yang di-coerce: PENDING adalah antrean sah
            // yang harus lanjut otomatis setelah restart, bukan ikut dijeda.
            val state = if (coerceActiveToPaused && rawState == DownloadState.DOWNLOADING) {
                DownloadState.PAUSED
            } else {
                rawState
            }
            // Entri tanpa id/url atau byte negatif adalah data korup:
            // lewati agar tidak meracuni daftar dan lapisan hilir.
            val id = o.optString("id")
            val url = o.optString("url")
            if (id.isBlank() || url.isBlank()) return null
            DownloadItem(
                id = id,
                url = url,
                fileName = o.optString("fileName", "unknown"),
                state = state,
                bytesDownloaded = o.optLong("bytesDownloaded", 0).coerceAtLeast(0),
                totalBytes = o.optLong("totalBytes", 0).coerceAtLeast(0),
                error = o.optString("error").ifEmpty { null },
                contentUri = o.optString("contentUri").ifEmpty { null },
                filePath = o.optString("filePath").ifEmpty { null },
                addedAt = o.optLong("addedAt", 0),
                finishedAt = o.optLong("finishedAt", 0),
                nameIsCustom = o.optBoolean("nameIsCustom", false),
                autoResume = o.optBoolean("autoResume", false),
                username = o.optString("username"),
                password = o.optString("password"),
                headers = o.optString("headers"),
                destination = o.optString("destination"),
                folderPath = o.optString("folderPath"),
                speedLimitKbps = o.optInt("speedLimitKbps", 0),
                priority = o.optInt("priority", 0),
                checksum = o.optString("checksum"),
                checksumVerified = o.optBoolean("checksumVerified", false),
                mirrors = parseStringList(o.optJSONArray("mirrors")),
                method = o.optString("method", "GET"),
                postBody = o.optString("postBody"),
                monitor = o.optBoolean("monitor", false),
                replacesItemId = o.optString("replacesItemId", ""),
                etag = o.optString("etag"),
                segments = parseSegments(o),
                preferredHeight = o.optInt("preferredHeight", 0),
                preferredAudioLang = o.optString("preferredAudioLang"),
                progressPercentOverride = o.optInt("progressPercentOverride", -1),
                retryCount = o.optInt("retryCount", 0),
                totalBytesEstimated = o.optBoolean("totalBytesEstimated", false)
            )
        }.getOrNull()
    }

    private fun parseStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return runCatching {
            buildList {
                for (j in 0 until arr.length()) {
                    val v = arr.optString(j)
                    if (v.isNotBlank()) add(v)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun parseSegments(o: JSONObject): List<DownloadSegment> {
        return runCatching {
            val segArr = o.getJSONArray("segments")
            buildList {
                for (j in 0 until segArr.length()) {
                    val so = segArr.getJSONObject(j)
                    val index = so.getInt("index")
                    val start = so.getLong("start")
                    val end = so.getLong("end")
                    val downloaded = so.getLong("downloaded")
                    // Entri segmen korup (negatif/terbalik) dilewati agar tidak
                    // meracuni resume multi-segmen; segmen valid tetap dipakai.
                    if (index < 0 || start < 0 || end < start || downloaded < 0) continue
                    // Batas ketangguhan muat (issue #733): segmen buatan
                    // aplikasi selalu <= 8 (jepit segmentCount di prefs),
                    // jadi index/jumlah di luar plafon ini pasti blob korup
                    // atau asing. Tanpa batas, encodeProgress mengalokasi
                    // LongArray sebesar index terbesar + 1 di tiap tick
                    // simpan progres (index raksasa = OOM / luapan negatif).
                    // downloaded juga dijepit ke panjang segmennya sendiri,
                    // sejajar jepit overlayProgress di berkas ini.
                    if (index > MAX_DECODED_SEGMENT_INDEX) continue
                    if (size >= MAX_DECODED_SEGMENTS) break
                    // Panjang segmen inklusif dihitung jenuh: end-start+1
                    // polos meluap membungkus ke Long.MIN_VALUE untuk
                    // end = Long.MAX_VALUE, dan coerceAtMost lalu
                    // mengembalikan downloaded negatif raksasa (issue
                    // #748, sisa sudut #733).
                    val segLen = end - start
                    val maxDownloaded =
                        if (segLen == Long.MAX_VALUE) Long.MAX_VALUE else segLen + 1
                    add(
                        DownloadSegment(
                            index, start, end,
                            downloaded.coerceAtMost(maxDownloaded)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Progres kompak (id -> bytes/total + downloaded per segmen) untuk
     *  persistensi berkala tanpa membangun JSON besar; dipakai
     *  DownloadRepository.saveProgress. Rincian segmen ("s", larik menurut
     *  index segmen) wajib ikut (issue #648): tanpa itu proses yang mati di
     *  tengah attempt segmen hanya memulihkan total byte, dan resume
     *  membuang progres segmen satu attempt walau berkas parsialnya utuh. */
    fun encodeProgress(items: List<DownloadItem>): String {
        val o = JSONObject()
        items.forEach { item ->
            if (item.state == DownloadState.DOWNLOADING || item.state == DownloadState.PENDING ||
                (item.bytesDownloaded > 0 && item.state == DownloadState.PAUSED)
            ) {
                val entry = JSONObject()
                    .put("b", item.bytesDownloaded)
                    .put("t", item.totalBytes)
                if (item.segments.isNotEmpty()) {
                    val perIndex = LongArray(item.segments.maxOf { it.index } + 1)
                    item.segments.forEach { seg -> perIndex[seg.index] = seg.downloaded }
                    val segArr = JSONArray()
                    perIndex.forEach { segArr.put(it) }
                    entry.put("s", segArr)
                }
                o.put(item.id, entry)
            }
        }
        return o.toString()
    }

    /** Terapkan progres ringan di atas snapshot terakhir; hanya nilai yang
     *  lebih besar yang dipakai (progres tidak pernah mundur). */
    fun overlayProgress(items: List<DownloadItem>, progressRaw: String?): List<DownloadItem> {
        if (progressRaw.isNullOrBlank()) return items
        val prog = runCatching { JSONObject(progressRaw) }.getOrNull() ?: return items
        return items.map { item ->
            val p = prog.optJSONObject(item.id) ?: return@map item
            val b = p.optLong("b", item.bytesDownloaded)
            val t = p.optLong("t", item.totalBytes)
            // Ambil max per field: bila hanya total yang tumbuh (kasus save &
            // saveProgress balapan), bytes versi lama tidak boleh menimpa mundur.
            // Clamp bytes ke total: progres basi yang lebih besar dari total
            // (mis. setelah reset resume) tidak boleh membuat persen >100%.
            val nt = maxOf(t, item.totalBytes)
            val nb = maxOf(b, item.bytesDownloaded).let { if (nt > 0) minOf(it, nt) else it }
            // Segmen: monoton per index seperti byte, dijepit ke panjang
            // segmennya sendiri — progres dari segmentasi lama yang lebih
            // besar tak boleh membuat segmen tampak melebihi ukurannya
            // (resumeAction tetap mendamaikan ulang terhadap berkas nyata).
            val segArr = p.optJSONArray("s")
            val nsegs = if (segArr != null && item.segments.isNotEmpty()) {
                item.segments.map { seg ->
                    if (seg.index in 0 until segArr.length()) {
                        // Panjang segmen inklusif dihitung jenuh seperti
                        // parseSegments (issue #748): end = Long.MAX_VALUE
                        // membuat +1 polos membungkus ke Long.MIN_VALUE
                        // dan minOf menghasilkan nilai negatif (issue #789).
                        val segLen = seg.end - seg.start
                        val cap = if (segLen == Long.MAX_VALUE) Long.MAX_VALUE else segLen + 1
                        val sv = minOf(segArr.optLong(seg.index, seg.downloaded), cap)
                        if (sv > seg.downloaded) seg.copy(downloaded = sv) else seg
                    } else {
                        seg
                    }
                }
            } else {
                item.segments
            }
            if (nb != item.bytesDownloaded || nt != item.totalBytes || nsegs != item.segments) {
                item.copy(bytesDownloaded = nb, totalBytes = nt, segments = nsegs)
            } else {
                item
            }
        }
    }
}
