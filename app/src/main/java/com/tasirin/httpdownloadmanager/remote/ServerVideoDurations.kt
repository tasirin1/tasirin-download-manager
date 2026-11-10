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

        /** Anggaran byte serialisasi cache. Plafon muat cacheLocked()
         *  adalah 512KB dan berkas yang melewatinya dibuang UTUH;
         *  tanpa anggaran ini, cache 2000 entri berpath dalam
         *  terserialisasi melewati plafon itu sehingga seluruh cache
         *  hilang di tiap start dingin dan save() menulis lagi berkas
         *  yang sama besarnya — amnesia permanen (issue #940). Margin
         *  di bawah plafon menampung pertumbuhan antar-prune. */
        const val MAX_SERIALIZED_BYTES = 448_000

        /** Durasi dari nilai cache untuk [token]: bentuk objek baru
         *  {"d": ms, "s": seq} dibaca dari "d"; nilai Long polos
         *  (berkas warisan sebelum issue #1388) adalah durasinya
         *  sendiri. 0 bila tak ada / bentuk tak dikenal. */
        fun durationOf(cache: JSONObject, token: String): Long =
            when (val v = cache.opt(token)) {
                is JSONObject -> v.optLong("d", 0L)
                is Number -> v.toLong()
                else -> 0L
            }

        /** Nomor urut eviksi dari satu nilai cache. Nilai Long polos
         *  tak punya nomor urut: diberi yang terkecil agar tereviksi
         *  lebih dulu dari nilai objek mana pun (warisan ditulis
         *  sebelum skema seq ada). */
        fun seqOf(value: Any?): Long = when (value) {
            is JSONObject -> value.optLong("s", Long.MIN_VALUE)
            else -> Long.MIN_VALUE
        }

        /** Batasi cache bila melebihi [MAX_ENTRIES] (sisakan [TARGET_ENTRIES])
         *  atau melebihi [MAX_SERIALIZED_BYTES].
         *  Korban eviksi ditentukan nomor urut eksplisit per entri
         *  (issue #1388), BUKAN urutan keys(): jar org.json tidak
         *  menjamin urutan sisip (versi 20260814 di uji JVM mengubah
         *  urutan yang teramati sesudah remove+put), jadi mengandalkan
         *  urutan map rapuh — entri yang baru di-probe ulang bisa
         *  tereviksi lebih dulu dari entri lama yang tak tersentuh.
         *  Murni (tanpa Android) supaya bisa di-unit-test. */
        fun prune(cache: JSONObject): JSONObject {
            // Pasangan (kunci, seq) diurutkan menaik sekali; kedua
            // fase eviksi membuang dari seq terkecil lewat indeks
            // berjalan yang sama.
            val entries = ArrayList<Pair<String, Long>>(cache.length())
            val iter = cache.keys()
            while (iter.hasNext()) {
                val key = iter.next()
                entries.add(key to seqOf(cache.opt(key)))
            }
            entries.sortBy { it.second }
            var idx = 0
            if (cache.length() > MAX_ENTRIES) {
                var remaining = cache.length() - TARGET_ENTRIES
                while (remaining > 0) {
                    cache.remove(entries[idx].first)
                    idx++
                    remaining--
                }
            }
            // Anggaran byte (issue #940): perkiraan konservatif per
            // entri = panjang kunci + 56 — selalu >= ukuran JSON
            // sebenarnya (kutip kunci + titik dua + koma + nilai
            // objek {"d":<19 digit>,"s":<19 digit>} = 53), jadi hasil
            // prune tak pernah melewati plafon muat. Nilai Long
            // warisan dinilai dengan rumus yang sama (konservatif
            // berlebih, aman). Buang dari seq terkecil.
            var est = 2
            for (i in idx until entries.size) est += entries[i].first.length + 56
            while (est > MAX_SERIALIZED_BYTES && idx < entries.size) {
                val key = entries[idx].first
                cache.remove(key)
                est -= key.length + 56
                idx++
            }
            return cache
        }

        /** Tulis durasi dengan nomor urut eviksi baru (issue #1388):
         *  penulis memasok [seq] dari penghitung monoton instance,
         *  sehingga entri yang baru di-probe ulang membawa seq
         *  terbesar dan selamat dari prune berikutnya — tanpa
         *  bergantung urutan sisip JSONObject sama sekali.
         *  Murni agar bisa di-unit-test. */
        fun putRefreshed(cache: JSONObject, token: String, ms: Long, seq: Long): JSONObject {
            cache.put(token, JSONObject().put("d", ms).put("s", seq))
            return cache
        }

        /** true bila kegagalan probe masih dalam TTL (belum perlu dicoba lagi).
         *  Selisih negatif (storedAt di masa depan — jam mundur) TIDAK
         *  throttled (issue #1389): bentuk lama selalu benar untuk
         *  selisih negatif sehingga stempel masa depan menahan probe
         *  ulang sampai jam melewati storedAt + TTL. */
        fun isProbeFailureThrottled(
            storedAt: Long?,
            now: Long,
            ttlMs: Long = PROBE_FAILURE_TTL_MS
        ): Boolean {
            if (storedAt == null) return false
            val age = now - storedAt
            return age >= 0 && age < ttlMs
        }

        // Mesin simpan milik PROSES, bukan instance (issue #826):
        // berkas video_durations.json + staging-nya dipakai semua
        // instance server (restart membuat instance baru), jadi
        // executor bersama meniru pola probeExecutor (satu thread per
        // proses, bukan satu per instance yang bocor seumur proses)
        // dan FILE_LOCK menserialkan tulis berkas lintas instance —
        // lock instance saja tak menolong dua instance yang menulis
        // staging yang sama.
        private val SAVE_EXECUTOR: java.util.concurrent.ExecutorService =
            java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "durations-save").apply { isDaemon = true }
            }
        private val FILE_LOCK = Any()

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

    /** Penghitung nomor urut eviksi instance (issue #1388): naik pada
     *  setiap tulis cacheDurationIfCurrent; sesudah muat dilanjutkan
     *  dari cacheLocked() supaya melampaui seq semua entri yang ada. */
    private var nextSeq = 0L

    private fun cacheLocked(): JSONObject {
        cache?.let { return it }
        // Batas 512KB: baca selalu bounded (readBounded) agar file korup yang
        // membengkak di antara cek length dan baca tak membuat OOM; mulai dari
        // cache kosong bila JSON terpotong/gagal parse.
        val loaded = runCatching {
            if (file.length() > 524_288L) JSONObject()
            else JSONObject(file.inputStream().use { readBounded(it, 524_288) })
        }.getOrDefault(JSONObject())
        // Normalisasi nilai warisan (issue #1388): berkas lama menyimpan
        // Long polos per token. Ubah menjadi objek {"d","s"} dengan
        // seq = indeks iterasi supaya semua nilai seragam dan korban
        // eviksi ditentukan seq eksplisit; nilai sampah (bukan angka /
        // bukan objek) dibuang. Iterasi atas potret names() supaya
        // mutasi di tengah jalan aman.
        val names = loaded.names()
        var maxSeq = -1L
        val garbage = ArrayList<String>()
        if (names != null) {
            for (i in 0 until names.length()) {
                val key = names.optString(i)
                when (val v = loaded.opt(key)) {
                    is JSONObject -> maxSeq = maxOf(maxSeq, v.optLong("s", -1L))
                    is Number -> {
                        loaded.put(key, JSONObject().put("d", v.toLong()).put("s", i.toLong()))
                        maxSeq = maxOf(maxSeq, i.toLong())
                    }
                    else -> garbage.add(key)
                }
            }
        }
        garbage.forEach { loaded.remove(it) }
        nextSeq = maxSeq + 1
        cache = loaded
        return loaded
    }

    /** Timestamp kegagalan probe per token — probe ulang hanya setelah TTL. */
    private val probeFailAt = HashMap<String, Long>()

    /** Baca durasi token: tanpa prune per-baca (of dipanggil per item galeri,
     *  prune di sini berarti cek batas N kali per request). Prune hanya saat
     *  tulis (cacheDurationIfCurrent) dan sekali baca di dalam lock yang sama. */
    fun of(token: String): Long = synchronized(lock) {
        durationOf(cacheLocked(), token)
    }

    /** Tulis hasil probe HANYA bila [expectedGeneration] masih
     *  generasi berjalan (belum ada invalidate sejak probe mulai). */
    fun cacheDurationIfCurrent(token: String, ms: Long, expectedGeneration: Long): Boolean {
        synchronized(lock) {
            if (generation != expectedGeneration) return false
            // Put membawa seq baru terbesar (issue #1388): entri yang
            // di-probe ulang adalah entri yang sedang dipakai, jadi
            // ia harus selamat dari prune berikutnya.
            putRefreshed(prune(cacheLocked()), token, ms, nextSeq++)
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
        // Ikut serialisasi tulis berkas (issue #841): FILE_LOCK
        // diperoleh SEBELUM lock — urutan yang sama dengan save()
        // (FILE_LOCK -> lock), jadi tak ada pembalikan urutan yang
        // membuka deadlock. Tanpa ini, invalidate yang mendarat
        // SELAMA tulis save() berjalan dikalahkan rename staging:
        // berkas yang baru dihapus hidup lagi berisi potret lama.
        synchronized(FILE_LOCK) {
            synchronized(lock) {
                generation++
                cache = JSONObject()
                nextSeq = 0L
                probeFailAt.clear()
            }
            runCatching { file.delete() }
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
            SAVE_EXECUTOR.execute {
                synchronized(lock) { saveQueued = false }
                save()
            }
        } catch (_: Exception) {
            synchronized(lock) { saveQueued = false }
        }
    }

    /** Tulis cache ke disk (sedikit & jarang; tidak wajib thread-safe ekstra).
     *  Cache kosong menghapus file: tanpa ini video_durations.json basi
     *  tetap dibaca ulang setelah invalidate() walau tak ada durasi baru.
     *  Potret string diambil di bawah kunci instance (serialisasi tak
     *  ditimpa mutasi cache di tengah), tulisnya di bawah FILE_LOCK
     *  statis (issue #826: staging dipakai lintas instance). */
    fun save() {
        val snapshot = synchronized(lock) {
            val c = cacheLocked()
            Triple(generation, c.length(), c.toString())
        }
        synchronized(FILE_LOCK) {
            // Penjaga generasi (issue #837): invalidate() yang mendarat
            // di celah potret -> tulis menaikkan generasi dan sudah
            // menghapus berkas; potret basi tak boleh menghidupkan
            // lagi berkas itu (pola expectedGeneration berkas ini).
            if (synchronized(lock) { generation } != snapshot.first) return
            runCatching {
                if (snapshot.second == 0) {
                    file.delete()
                    return@runCatching
                }
                // Tulis via staging lalu rename: crash di tengah tak boleh
                // meninggalkan JSON setengah jadi yang merusak cache berikutnya.
                val staging = File(file.parentFile, file.name + ".tmp")
                try {
                    staging.writeText(snapshot.third)
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
