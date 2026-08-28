package com.tasirin.httpdownloadmanager.data

import org.json.JSONArray
import org.json.JSONObject

/** Serialisasi daftar unduhan ke/dari JSON — murni JVM (tanpa Android),
 *  supaya roundtrip bisa diuji unit di CI. Format JSON identik dengan versi
 *  lama agar data prefs tetap terbaca (tidak perlu migrasi). */
object DownloadItemCodec {

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
            arr.put(o)
        }
        return arr.toString()
    }

    /** Parse daftar dari JSON; satu entry korup tidak menghapus daftar
     *  (entry itu dilewati). Bila coerceActiveToPaused, download yang sedang
     *  berjalan saat proses restart dianggap dijeda. */
    fun decode(raw: String, coerceActiveToPaused: Boolean = true): List<DownloadItem> {
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val items = mutableListOf<DownloadItem>()
        for (i in 0 until arr.length()) {
            parseItem(arr.optJSONObject(i), coerceActiveToPaused)?.let { items.add(it) }
        }
        return items
    }

    private fun parseItem(o: JSONObject?, coerceActiveToPaused: Boolean): DownloadItem? {
        if (o == null) return null
        return runCatching {
            val rawState = DownloadState.valueOf(o.getString("state"))
            val state = if (coerceActiveToPaused &&
                (rawState == DownloadState.DOWNLOADING || rawState == DownloadState.PENDING)
            ) {
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
                monitor = o.optBoolean("monitor", false),
                etag = o.optString("etag"),
                segments = parseSegments(o),
                preferredHeight = o.optInt("preferredHeight", 0)
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

    /** Progres kompak (id -> bytes/total) untuk persistensi berkala tanpa
     *  membangun JSON besar; dipakai DownloadRepository.saveProgress. */
    fun encodeProgress(items: List<DownloadItem>): String {
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
            if (b > item.bytesDownloaded || t > item.totalBytes) {
                item.copy(bytesDownloaded = b, totalBytes = t)
            } else {
                item
            }
        }
    }
}
