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

    // Plafon kelayakan byte progres tersimpan (issue #1058): 2^40.
    private const val MAX_PLAUSIBLE_PROGRESS_BYTES = 1L shl 40

    // Nilai progres blob utama di luar 0..plafon pasti korup (issue
    // #1095, menyelaraskan parseItem dengan overlayProgress #1058):
    // nolkan agar tak lolos ke tampilan/engine dan tak ikut ter-encode
    // ulang pada save berikutnya. Jalur resume sendiri sudah aman —
    // resumeAction memvonis RESTART bila berkas parsial nyata lebih
    // kecil dari catatan — jadi ini murni pengerasan parse.
    private fun plausibleProgress(raw: Long): Long =
        if (raw in 0..MAX_PLAUSIBLE_PROGRESS_BYTES) raw else 0

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
                bytesDownloaded = plausibleProgress(o.optLong("bytesDownloaded", 0)),
                totalBytes = plausibleProgress(o.optLong("totalBytes", 0)),
                error = o.optString("error").ifEmpty { null },
                contentUri = o.optString("contentUri").ifEmpty { null },
                filePath = o.optString("filePath").ifEmpty { null },
                // Blob tanpa addedAt / bernilai tak positif (korup atau
                // format lama) diganti waktu decode (issue #1391):
                // default model adalah waktu pembuatan, jadi 0 di sini
                // membuat item tampil berumur sejak epoch di UI/sortir.
                addedAt = o.optLong("addedAt", 0).takeIf { it > 0 }
                    ?: System.currentTimeMillis(),
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

    /** Parse SATU entri segmen; null = entri dilewati (tipe salah ATAU
     *  nilai invalid — semantik lewati yang sama, issue #1057). */
    private fun parseSegmentEntry(so: JSONObject): DownloadSegment? {
        val index = so.getInt("index")
        val start = so.getLong("start")
        val end = so.getLong("end")
        val downloaded = so.getLong("downloaded")
        // Entri segmen korup (negatif/terbalik) dilewati agar tidak
        // meracuni resume multi-segmen; segmen valid tetap dipakai.
        if (index < 0 || start < 0 || end < start || downloaded < 0) return null
        // Batas ketangguhan muat (issue #733): segmen buatan
        // aplikasi selalu <= 8 (jepit segmentCount di prefs),
        // jadi index/jumlah di luar plafon ini pasti blob korup
        // atau asing. Tanpa batas, encodeProgress mengalokasi
        // LongArray sebesar index terbesar + 1 di tiap tick
        // simpan progres (index raksasa = OOM / luapan negatif).
        // downloaded juga dijepit ke panjang segmennya sendiri,
        // sejajar jepit overlayProgress di berkas ini.
        if (index > MAX_DECODED_SEGMENT_INDEX) return null
        // Panjang segmen inklusif dihitung jenuh: end-start+1
        // polos meluap membungkus ke Long.MIN_VALUE untuk
        // end = Long.MAX_VALUE, dan coerceAtMost lalu
        // mengembalikan downloaded negatif raksasa (issue
        // #748, sisa sudut #733).
        val segLen = end - start
        val maxDownloaded =
            if (segLen == Long.MAX_VALUE) Long.MAX_VALUE else segLen + 1
        return DownloadSegment(
            index, start, end,
            downloaded.coerceAtMost(maxDownloaded)
        )
    }

    private fun parseSegments(o: JSONObject): List<DownloadSegment> {
        val parsed = runCatching {
            val segArr = o.getJSONArray("segments")
            buildList {
                for (j in 0 until segArr.length()) {
                    if (size >= MAX_DECODED_SEGMENTS) break
                    // runCatching PER ENTRI (issue #1057): getJSONObject/
                    // getInt/getLong melempar pada kesalahan tipe, dan
                    // satu runCatching luar membuat SATU entri bertipe
                    // salah membuang seluruh rencana — berbeda dengan
                    // nilai invalid yang memang dilewati per entri.
                    // Kini keduanya dilewati per entri; validasi
                    // keseluruhan #1042 di bawah tetap yang memutuskan
                    // rencana akhir utuh atau dibuang.
                    val seg = runCatching {
                        parseSegmentEntry(segArr.getJSONObject(j))
                    }.getOrNull() ?: continue
                    add(seg)
                }
            }
        }.getOrDefault(emptyList())
        // Validasi KESELURUHAN rencana (issue #1042): per-entri valid tak
        // cukup — indeks duplikat membuat dua coroutine menulis berkas
        // .part yang sama, indeks hilang/tak berurutan membuat merge
        // mengambil parts yang salah, dan rentang tumpang tindih/gap
        // menggandakan/menghilangkan byte di hasil gabungan. Rencana
        // segmen sah selalu partisi persis [0,total): indeks 0..n-1 dan
        // tiap segmen mulai tepat sesudah segmen sebelumnya berakhir.
        // Rencana yang gagal dibuang SELURUHNYA (bukan diperbaiki
        // sebagian): runSegmented memperlakukan daftar kosong sebagai
        // "buat rencana baru", jadi resume mulai dari rencana bersih
        // alih-alih menerbitkan berkas korup berstatus COMPLETED.
        return if (isSegmentPlanValid(parsed)) parsed.sortedBy { it.index } else emptyList()
    }

    /** Rencana segmen sah = partisi persis: sesudah diurutkan menurut
     *  indeks, indeksnya 0..n-1 tanpa celah/duplikat, segmen pertama
     *  mulai di 0, dan tiap segmen mulai tepat di end+1 pendahulunya
     *  (tanpa tumpang tindih, tanpa gap). */
    private fun isSegmentPlanValid(segments: List<DownloadSegment>): Boolean {
        if (segments.isEmpty()) return true
        val sorted = segments.sortedBy { it.index }
        var expectedStart = 0L
        for ((pos, seg) in sorted.withIndex()) {
            if (seg.index != pos) return false
            if (seg.start != expectedStart) return false
            if (pos < sorted.lastIndex) {
                if (seg.end == Long.MAX_VALUE) return false
                expectedStart = seg.end + 1
            }
        }
        return true
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
            // Nilai progres wajib layak fisik (issue #1058): blob korup
            // {"b": Long.MAX_VALUE, "t": 0} dulu lolos utuh karena jepit
            // ke total hanya berlaku bila total diketahui — angka absurd
            // tampil di daftar dan ikut ter-encode ulang pada save
            // berikutnya. Plafon 2^40 byte (1 TiB) jauh di atas unduhan
            // terbesar realistis di perangkat sasaran (HP/TV box);
            // nilai di luar 0..plafon pasti korup dan diabaikan
            // (kembali ke nilai item sendiri).
            val bRaw = p.optLong("b", item.bytesDownloaded)
            val tRaw = p.optLong("t", item.totalBytes)
            val b = if (bRaw in 0..MAX_PLAUSIBLE_PROGRESS_BYTES) bRaw else item.bytesDownloaded
            val t = if (tRaw in 0..MAX_PLAUSIBLE_PROGRESS_BYTES) tRaw else item.totalBytes
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
