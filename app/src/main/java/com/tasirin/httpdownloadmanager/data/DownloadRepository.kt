package com.tasirin.httpdownloadmanager.data

import android.content.Context
import com.tasirin.httpdownloadmanager.util.Crypto
import org.json.JSONArray
import org.json.JSONObject

class DownloadRepository(context: Context) {

    private val prefs = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)
    // Cache hasil enkripsi kredensial per pasangan plaintext: menghindari AES
    // + IV acak diulang tiap kali save penuh (hot path download aktif).
    // synchronizedMap: save bisa dipanggil dari thread UI (flush) dan IO (job).
    private val credCache = Collections.synchronizedMap(HashMap<String, Pair<String, String>>())

    fun load(): List<DownloadItem> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val items = mutableListOf<DownloadItem>()
        for (i in 0 until arr.length()) {
            // Satu entry korup tidak boleh menghapus seluruh daftar.
            parseItem(arr.optJSONObject(i))?.let { items.add(it) }
        }
        return overlayProgress(items)
    }

    /** Terapkan progres ringan (id -> bytes/total) di atas snapshot terakhir.
     *  Snapshot penuh hanya disimpan saat state berubah; progres yang sedang
     *  berjalan disimpan terpisah supaya resume tetap akurat tanpa JSON besar. */
    private fun overlayProgress(items: List<DownloadItem>): List<DownloadItem> {
        val prog = runCatching {
            val raw = prefs.getString(KEY_PROGRESS, null) ?: return items
            JSONObject(raw)
        }.getOrNull() ?: return items
        return items.map { item ->
            val p = prog.optJSONObject(item.id) ?: return@map item
            val b = p.optLong("b", item.bytesDownloaded)
            val t = p.optLong("t", item.totalBytes)
            if (b > item.bytesDownloaded || t > item.totalBytes) {
                item.copy(bytesDownloaded = b, totalBytes = t)
            } else {
                item
            }
        }
    }

    /** Simpan progres kompak (id -> bytes/total) tanpa enkripsi & tanpa detail
     *  segmen; dipanggil berkala selama download aktif. */
    fun saveProgress(items: List<DownloadItem>) {
        val o = JSONObject()
        items.forEach { item ->
            if (item.state == DownloadState.DOWNLOADING || item.state == DownloadState.PENDING ||
                (item.bytesDownloaded > 0 && item.state == DownloadState.PAUSED)
            ) {
                o.put(
                    item.id,
                    JSONObject()
                        .put("b", item.bytesDownloaded)
                        .put("t", item.totalBytes)
                )
            }
        }
        prefs.edit().putString(KEY_PROGRESS, o.toString()).apply()
    }

    private fun parseItem(o: JSONObject?): DownloadItem? {
        if (o == null) return null
        return runCatching {
            val rawState = DownloadState.valueOf(o.getString("state"))
            // Setelah proses di-restart, download yang tadi berjalan dianggap dijeda.
            val state = if (rawState == DownloadState.DOWNLOADING || rawState == DownloadState.PENDING) {
                DownloadState.PAUSED
            } else {
                rawState
            }
            DownloadItem(
                id = o.getString("id"),
                url = o.getString("url"),
                fileName = o.getString("fileName"),
                state = state,
                bytesDownloaded = o.optLong("bytesDownloaded", 0),
                totalBytes = o.optLong("totalBytes", 0),
                error = o.optString("error").ifEmpty { null },
                contentUri = o.optString("contentUri").ifEmpty { null },
                filePath = o.optString("filePath").ifEmpty { null },
                addedAt = o.optLong("addedAt", 0),
                nameIsCustom = o.optBoolean("nameIsCustom", false),
                autoResume = o.optBoolean("autoResume", false),
                username = Crypto.decrypt(o.optString("username")),
                password = Crypto.decrypt(o.optString("password")),
                headers = o.optString("headers"),
                destination = o.optString("destination"),
                folderPath = o.optString("folderPath"),
                speedLimitKbps = o.optInt("speedLimitKbps", 0),
                priority = o.optInt("priority", 0),
                checksum = o.optString("checksum"),
                checksumVerified = o.optBoolean("checksumVerified", false),
                mirrors = parseStringList(o.optJSONArray("mirrors")),
                monitor = o.optBoolean("monitor", false),
                etag = o.optString("etag"),
                segments = parseSegments(o)
            )
        }.getOrNull()
    }

    fun save(items: List<DownloadItem>) {
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
            o.put("nameIsCustom", item.nameIsCustom)
            o.put("autoResume", item.autoResume)
            val (encUser, encPass) = encryptedCreds(item)
            o.put("username", encUser)
            o.put("password", encPass)
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
            arr.put(o)
        }
        // Snapshot penuh sudah memuat progres terbaru -> hapus progres ringan
        // supaya tidak menimpa data yang lebih lama saat load berikutnya.
        prefs.edit().putString(KEY_ITEMS, arr.toString()).remove(KEY_PROGRESS).apply()
    }

    private fun encryptedCreds(item: DownloadItem): Pair<String, String> {
        val plain = item.username + "\u0000" + item.password
        credCache[plain]?.let { return it }
        val pair = Crypto.encrypt(item.username) to Crypto.encrypt(item.password)
        // Cache dibatasi: sesi panjang dengan banyak kredensial unik tidak
        // boleh menumpuk (entries lama yang tidak terpakai dibuang).
        if (credCache.size > MAX_CRED_CACHE) credCache.clear()
        credCache[plain] = pair
        return pair
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
                    add(
                        DownloadSegment(
                            index = so.getInt("index"),
                            start = so.getLong("start"),
                            end = so.getLong("end"),
                            downloaded = so.getLong("downloaded")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val KEY_ITEMS = "items"
        private const val KEY_PROGRESS = "progress"
        private const val MAX_CRED_CACHE = 128
    }
}
