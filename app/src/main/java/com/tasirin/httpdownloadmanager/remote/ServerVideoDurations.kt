package com.tasirin.httpdownloadmanager.remote

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toUri
import com.tasirin.httpdownloadmanager.util.MediaLibrary
import org.json.JSONObject
import java.io.File

/** Probe & cache durasi video galeri — dipisah dari HttpControlServer supaya
 *  file server tidak semakin panjang (pola ServerThumbnail). Semua akses
 *  cache diserialisasi satu lock: banyak thread nanohttpd bisa membuka galeri
 *  bersamaan, dan org.json JSONObject tidak thread-safe (put/optLong paralel
 *  bisa korup atau 500). */
class ServerVideoDurations(
    private val context: Context,
    private val isFsPathAllowed: (String) -> Boolean,
    private val isMediaUriAllowed: (Uri) -> Boolean
) {
    companion object {
        const val MAX_ENTRIES = 2000
        const val TARGET_ENTRIES = 1500
        const val PROBE_FAILURE_TTL_MS = 10L * 60 * 1000

        /** Batasi cache bila melebihi [MAX_ENTRIES] (sisakan [TARGET_ENTRIES]).
         *  Murni (tanpa Android) supaya bisa di-unit-test. */
        fun prune(cache: JSONObject): JSONObject {
            if (cache.length() > MAX_ENTRIES) {
                val iter = cache.keys()
                var removed = 0
                val toRemove = cache.length() - TARGET_ENTRIES
                while (iter.hasNext() && removed < toRemove) {
                    iter.next()
                    iter.remove()
                    removed++
                }
            }
            return cache
        }

        /** true bila kegagalan probe masih dalam TTL (belum perlu dicoba lagi). */
        fun isProbeFailureThrottled(
            storedAt: Long?,
            now: Long,
            ttlMs: Long = PROBE_FAILURE_TTL_MS
        ): Boolean = storedAt != null && now - storedAt < ttlMs
    }

    private val file = File(context.filesDir, "video_durations.json")
    private val lock = Any()

    /** Cache durasi per token; null = belum dimuat dari disk (dimuat malas). */
    private var cache: JSONObject? = null

    private fun cacheLocked(): JSONObject {
        cache?.let { return it }
        val loaded = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
        cache = loaded
        return loaded
    }

    /** Timestamp kegagalan probe per token — probe ulang hanya setelah TTL. */
    private val probeFailAt = HashMap<String, Long>()

    fun cache(): JSONObject = synchronized(lock) { cacheLocked() }

    /** Baca durasi token sambil prune cache bila terlalu besar. */
    fun of(token: String): Long = synchronized(lock) {
        prune(cacheLocked())
        cacheLocked().optLong(token, 0L)
    }

    fun cacheDuration(token: String, ms: Long) {
        synchronized(lock) {
            cacheLocked().put(token, ms)
            probeFailAt.remove(token)
        }
    }

    fun recordProbeFailure(token: String) {
        synchronized(lock) {
            if (probeFailAt.size > MAX_ENTRIES) {
                val cutoff = System.currentTimeMillis() - PROBE_FAILURE_TTL_MS
                probeFailAt.entries.removeAll { it.value < cutoff }
            }
            probeFailAt[token] = System.currentTimeMillis()
        }
    }

    fun shouldProbe(token: String): Boolean = synchronized(lock) {
        !isProbeFailureThrottled(probeFailAt[token], System.currentTimeMillis())
    }

    /** File media baru masuk/berubah: cache durasi lama tidak lagi relevan.
     *  null = muat ulang dari disk saat diakses berikutnya. */
    fun invalidate() {
        synchronized(lock) { cache = null }
    }

    /** Tulis cache ke disk (sedikit & jarang; tidak wajib thread-safe ekstra). */
    fun save() {
        synchronized(lock) {
            val c = cacheLocked()
            if (c.length() != 0) {
                runCatching { file.writeText(c.toString()) }
            }
        }
    }

    /** Probe durasi via MediaMetadataRetriever — SENYAP: kegagalan adalah hal
     *  wajar untuk video korup/format tidak dikenal, tidak perlu dicetak di
     *  log server (dulu "ERROR setDataSource failed" spam tiap request). */
    fun probeDurationMs(token: String): Long {
        val raw = MediaLibrary.decodeToken(token) ?: return 0L
        return runCatching {
            val mmr = MediaMetadataRetriever()
            try {
                when {
                    raw.startsWith("f:") -> {
                        val file = File(raw.substring(2))
                        if (!file.isFile || !isFsPathAllowed(file.absolutePath)) return 0L
                        mmr.setDataSource(file.absolutePath)
                    }
                    raw.startsWith("u:") -> {
                        val uri = raw.substring(2).toUri()
                        if (!isMediaUriAllowed(uri)) return 0L
                        mmr.setDataSource(context, uri)
                    }
                    else -> return 0L
                }
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                runCatching { mmr.release() }
            }
        }.getOrDefault(0L)
    }
}
