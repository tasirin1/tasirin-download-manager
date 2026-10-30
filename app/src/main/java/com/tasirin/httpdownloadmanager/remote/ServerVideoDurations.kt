package com.tasirin.httpdownloadmanager.remote

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toUri
import com.tasirin.httpdownloadmanager.util.MediaLibrary
import com.tasirin.httpdownloadmanager.util.readBounded
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

        /** Kunci cache durasi untuk satu entri galeri: token + cap ubah
         *  sumber (issue #417). Token saja diturunkan murni dari path,
         *  jadi berkas yang DIGANTI di luar aplikasi pada path yang sama
         *  mewarisi durasi video lama selamanya. Cap ubah (mtime berkas /
         *  DATE_MODIFIED MediaStore) berubah saat isi berganti, sehingga
         *  kunci lama tak lagi cocok dan durasi diprobe/dibaca ulang.
         *  Ukuran ikut dikunci (issue #441, pola cap thumbnail #399):
         *  penggantian yang MEMPERTAHANKAN mtime (salinan penjaga cap,
         *  DATE_MODIFIED se-detik) tetap mengubah kunci bila ukurannya
         *  beda. Cap tak tersedia (<= 0) jatuh ke token polos seperti
         *  dulu. Murni agar bisa di-unit-test. */
        fun keyFor(token: String, modified: Long, size: Long): String =
            if (modified > 0) "$token@$modified:$size" else token
    }

    private val file = File(context.filesDir, "video_durations.json")
    private val lock = Any()

    /** Nomor generasi cache: naik setiap invalidate(). Probe berjalan
     *  LAMBAT di luar kunci (retriever), jadi hasil probe hanya boleh
     *  ditulis bila generasi belum berubah sejak probe dimulai —
     *  kalau tidak, durasi berkas lama hidup lagi untuk path yang
     *  sudah berganti isi dan menggagalkan invalidasi (#326). */
    private var generation = 0L

    fun currentGeneration(): Long = synchronized(lock) { generation }

    /** Cache durasi per token; null = belum dimuat dari disk (dimuat malas). */
    private var cache: JSONObject? = null

    private fun cacheLocked(): JSONObject {
        cache?.let { return it }
        // Batas 512KB: baca selalu bounded (readBounded) agar file korup yang
        // membengkak di antara cek length dan baca tak membuat OOM; mulai dari
        // cache kosong bila JSON terpotong/gagal parse.
        val loaded = runCatching {
            if (file.length() > 524_288L) JSONObject()
            else JSONObject(file.inputStream().use { readBounded(it, 524_288) })
        }.getOrDefault(JSONObject())
        cache = loaded
        return loaded
    }

    /** Timestamp kegagalan probe per token — probe ulang hanya setelah TTL. */
    private val probeFailAt = HashMap<String, Long>()

    /** Baca durasi token: tanpa prune per-baca (of dipanggil per item galeri,
     *  prune di sini berarti cek batas N kali per request). Prune hanya saat
     *  tulis (cacheDurationIfCurrent) dan sekali baca di dalam lock yang sama. */
    fun of(token: String): Long = synchronized(lock) {
        cacheLocked().optLong(token, 0L)
    }

    /** Tulis hasil probe HANYA bila [expectedGeneration] masih
     *  generasi berjalan (belum ada invalidate sejak probe mulai). */
    fun cacheDurationIfCurrent(token: String, ms: Long, expectedGeneration: Long): Boolean {
        synchronized(lock) {
            if (generation != expectedGeneration) return false
            prune(cacheLocked()).put(token, ms)
            probeFailAt.remove(token)
            return true
        }
    }

    /** Catat gagal-probe hanya bila generasi belum berubah: throttle
     *  dari probe basi tidak boleh menahan probe ulang berkas BARU
     *  di path yang sama sesudah invalidasi. */
    fun recordProbeFailureIfCurrent(token: String, expectedGeneration: Long) {
        // Cek generasi + catat dalam SATU blok kunci (issue #352):
        // sebelumnya keduanya terpisah sehingga invalidate() yang
        // mendarat di celahnya membuat catatan basi tetap tertulis
        // sesudah invalidasi (meniru cacheDurationIfCurrent di atas).
        synchronized(lock) {
            if (generation != expectedGeneration) return
            recordProbeFailureLocked(token)
        }
    }

    /** Isi recordProbeFailureIfCurrent; pemanggil WAJIB sudah memegang [lock]. */
    private fun recordProbeFailureLocked(token: String) {
        if (probeFailAt.size > MAX_ENTRIES) {
            val cutoff = System.currentTimeMillis() - PROBE_FAILURE_TTL_MS
            probeFailAt.entries.removeAll { it.value < cutoff }
            // Hanya hapus kedaluwarsa tak cukup bila gagal massal dalam TTL:
            // paksa buang setengah tertua agar map tak tumbuh tanpa batas.
            if (probeFailAt.size > MAX_ENTRIES) {
                val drop = probeFailAt.size / 2
                probeFailAt.entries.sortedBy { it.value }.take(drop).forEach { probeFailAt.remove(it.key) }
            }
        }
        probeFailAt[token] = System.currentTimeMillis()
    }

    fun shouldProbe(token: String): Boolean = synchronized(lock) {
        !isProbeFailureThrottled(probeFailAt[token], System.currentTimeMillis())
    }

    /** File media baru masuk/berubah: cache durasi lama tidak lagi
     *  relevan. Wajib mengosongkan SUNGGUHAN (#287): versi lama hanya
     *  men-null-kan cache memori, jadi cacheLocked() memuat ulang berkas
     *  JSON di disk apa adanya dan durasi basi tidak pernah diperbarui
     *  (token diturunkan dari path — video yang diganti berkas lain di
     *  path yang sama mewarisi durasi video lama selamanya). Cache kini
     *  diganti objek kosong, berkas persisten dihapus, dan throttle
     *  gagal-probe direset, supaya galeri mem-probe ulang dan save()
     *  berikutnya menulis himpunan segar. */
    fun invalidate() {
        synchronized(lock) {
            generation++
            cache = JSONObject()
            probeFailAt.clear()
            runCatching { file.delete() }
        }
    }

    private val saveExecutor by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "durations-save").apply { isDaemon = true }
        }
    }

    @Volatile private var saveQueued = false

    /** Jadwalkan save() di thread khusus (issue #817): pemanggilnya
     *  thread request galeri — tulis disk sinkron di sana menahan
     *  respons dan lock cache sesudah anggaran probe dibayar. Antrean
     *  DIGABUNG: save membaca cache terkini saat berjalan, jadi satu
     *  save tertunda mewakili semua permintaan; bendera direset
     *  SEBELUM save berjalan agar permintaan yang datang di tengah
     *  tulis mengantrekan save berikutnya. Persistensi cache bersifat
     *  best-effort — kegagalan antre ditelan. */
    fun saveAsync() {
        synchronized(lock) {
            if (saveQueued) return
            saveQueued = true
        }
        try {
            saveExecutor.execute {
                synchronized(lock) { saveQueued = false }
                save()
            }
        } catch (_: Exception) {
            synchronized(lock) { saveQueued = false }
        }
    }

    /** Tulis cache ke disk (sedikit & jarang; tidak wajib thread-safe ekstra).
     *  Cache kosong menghapus file: tanpa ini video_durations.json basi
     *  tetap dibaca ulang setelah invalidate() walau tak ada durasi baru. */
    fun save() {
        synchronized(lock) {
            val c = cacheLocked()
            runCatching {
                if (c.length() == 0) {
                    file.delete()
                    return@runCatching
                }
                // Tulis via staging lalu rename: crash di tengah tak boleh
                // meninggalkan JSON setengah jadi yang merusak cache berikutnya.
                val staging = File(file.parentFile, file.name + ".tmp")
                try {
                    staging.writeText(c.toString())
                    if (!staging.renameTo(file)) {
                        runCatching { file.delete() }
                        if (!staging.renameTo(file) || !file.isFile) {
                            runCatching { staging.delete() }
                            runCatching { file.delete() }
                        }
                    }
                } finally {
                    if (staging.exists()) runCatching { staging.delete() }
                }
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
