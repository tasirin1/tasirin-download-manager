package com.tasirin.httpdownloadmanager.data

import android.content.Context
import androidx.core.content.edit
import com.tasirin.httpdownloadmanager.util.Crypto
import java.util.Collections

class DownloadRepository(context: Context) {

    private val prefs = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)
    // Berkas prefs TERPISAH khusus progres (issue #1433): commit
    // SharedPreferences menulis ulang SELURUH berkas, jadi progres kecil
    // yang di-commit tiap ±2 dtk dulu ikut menulis ulang blob antrean
    // penuh (KEY_ITEMS) + fsync tiap kalinya. Di berkas sendiri, commit
    // progres hanya membayar ukuran progres itu sendiri. Koordinasi
    // snapshot-vs-progres tetap lewat monitor kelas ini + nomor urut
    // progressVersion (lihat saveProgress/writePrepared).
    private val progressPrefs =
        context.getSharedPreferences("downloads_progress", Context.MODE_PRIVATE)
    // Cache hasil enkripsi kredensial per kombinasi empat field sensitif: menghindari AES
    // + IV acak diulang tiap kali save penuh (hot path download aktif).
    // synchronizedMap: save bisa dipanggil dari thread UI (flush) dan IO (job).
    private val credCache = Collections.synchronizedMap(HashMap<String, Quad>())

    // True bila load terakhir terdegradasi (blob ada tapi tak terbaca):
    // save kosong diblokir agar tak menimpa blob mentah secara permanen.
    @Volatile private var degradedLoad = false
    // Kredensial terenkripsi apa adanya per id, untuk memulihkan field yang
    // gagal di-decrypt (keystore invalid) agar save tak menimpa permanen "".
    private val storedCreds = HashMap<String, Quad>()
    // Penjaga urutan snapshot PreparedSave dan versi progres ringan;
    // keduanya hanya disentuh di dalam monitor persist (issue #370).
    private var persistSeq = 0L
    private var writtenPersistSeq = 0L
    private var progressVersion = 0L

    // True bila load terakhir menemukan kredensial tersimpan lemah
    // (plaintext eksplisit/legacy) — engine menindaklanjutinya dengan
    // penulisan ulang terenkripsi saat start (issue #209; isWeakStorage
    // sebelumnya tak punya konsumen produksi sama sekali).
    @Volatile private var weakStorageFound = false

    fun hasWeakStoredCredentials(): Boolean = weakStorageFound

    /** Apakah prefs menyimpan blob antrean (bedakan fresh install vs corrupt). */
    fun hadStoredItems(): Boolean = prefs.contains(KEY_ITEMS)

    // Sinkron dengan persistItems: save dari thread UI (flush) dan IO (job)
    // bisa tumpang tindih dan merusak storedCreds (HashMap polos) sehingga
    // kredensial pulihan-decrypt tertimpa/nol permanen.
    @Synchronized
    fun load(): List<DownloadItem> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        // Satu parse untuk tiga kebutuhan (daftar + panjang degradasi +
        // kredensial tersimpan): blob antrean besar sebelumnya di-parse 3x
        // tiap load (length + decode + readStoredCreds).
        val arr = runCatching { org.json.JSONArray(raw) }.getOrNull()
        val items = if (arr != null) DownloadItemCodec.decode(arr).map { item ->
            // Kredensial dan secret header/body disimpan terenkripsi (API 23+);
            // data era Android 5.0-5.1 tersimpan plaintext (Keystore AES belum tersedia)
            // dan nilai lama tanpa prefix lolos apa adanya lewat decrypt.
            item.copy(
                username = Crypto.decrypt(item.username),
                password = Crypto.decrypt(item.password),
                headers = Crypto.decrypt(item.headers),
                postBody = Crypto.decrypt(item.postBody)
            )
        } else emptyList()
        degradedLoad = items.isEmpty() && (arr?.length() ?: -1) != 0
        storedCreds.clear()
        if (arr != null) storedCreds.putAll(readStoredCreds(arr))
        weakStorageFound = storedCreds.values.any { q ->
            Crypto.isWeakStorage(q.first) || Crypto.isWeakStorage(q.second) ||
                Crypto.isWeakStorage(q.third) || Crypto.isWeakStorage(q.fourth)
        }
        return DownloadItemCodec.overlayProgress(items, readProgressRaw())
    }

    /** Progres mentah dari berkas progres baru; migrasi satu kali dari
     *  berkas lama (issue #1433): instalasi pra-pemisahan menyimpan
     *  KEY_PROGRESS di berkas "downloads" — nilai itu dipindah ke berkas
     *  progres (ditulis ke berkas baru, lalu dihapus dari berkas lama)
     *  agar resume sesudah upgrade tak kehilangan progres terakhir.
     *  Hanya dipanggil dari load yang @Synchronized. */
    private fun readProgressRaw(): String? {
        val newRaw = progressPrefs.getString(KEY_PROGRESS, null)
        val legacyRaw = if (newRaw == null) prefs.getString(KEY_PROGRESS, null) else null
        return when (progressSource(newRaw != null, legacyRaw != null)) {
            ProgressSource.NEW_FILE -> newRaw
            ProgressSource.LEGACY_MIGRATE -> {
                val value = legacyRaw!!
                progressPrefs.edit(commit = true) { putString(KEY_PROGRESS, value) }
                prefs.edit(commit = true) { remove(KEY_PROGRESS) }
                value
            }
            ProgressSource.NONE -> null
        }
    }

    /** Baca kredensial tersimpan (masih terenkripsi) per id dari array yang
     *  sudah terparse oleh load (tanpa parse ulang blob mentah). */
    private fun readStoredCreds(arr: org.json.JSONArray): Map<String, Quad> {
        val out = HashMap<String, Quad>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            // Duplikat id: pertahankan entri PERTAMA (issue #1059),
            // sejajar dedupe codec #942. Menimpa tanpa syarat membuat
            // peta kredensial berisi entri terakhir — preparePersist
            // bisa menyuntikkan kredensial entri kedua ke item pertama
            // yang bertahan (yang kredensialnya gagal decrypt), dan
            // kredensial salah itu tersimpan permanen pada save berikut.
            if (out.containsKey(id)) continue
            out[id] = Quad(
                o.optString("username", ""),
                o.optString("password", ""),
                o.optString("headers", ""),
                o.optString("postBody", "")
            )
        }
        return out
    }

    /** Simpan progres kompak (id -> bytes/total + downloaded per segmen pada
     *  larik `s`, sejak issue #648) tanpa enkripsi; dipanggil berkala selama
     *  download aktif. Commit sinkron (bukan
     *  apply): apply async bisa mendarat SETELAH commit snapshot penuh
     *  (persistItems + penghapusan progres) sehingga progres basi menimpa
     *  data segar saat load. Menulis ke berkas progres sendiri (issue
     *  #1433) agar commit ±2 dtk ini tak menulis ulang blob antrean.
     *  Dipanggil dari thread IO engine, bukan main. */
    // Sinkron dengan persistItems/load: tanpa lock, commit progres bisa
    // mendarat SEBELUM commit snapshot penuh yang di-encode lebih dulu
    // (persistItems + penghapusan progres), sehingga progres segar terhapus
    // dan load memakai byte satu tick lebih tua. Satu monitor mengurutkan
    // commit sesuai urutan encode — kini lintas dua berkas prefs, dengan
    // progressVersion sebagai penengahnya (lihat writePrepared).
    @Synchronized
    fun saveProgress(items: List<DownloadItem>) {
        progressVersion++
        progressPrefs.edit(commit = true) {
            putString(KEY_PROGRESS, DownloadItemCodec.encodeProgress(items))
        }
    }

    fun save(items: List<DownloadItem>) {
        persistItems(items, blocking = false)
    }

    // Tulis sinkron (commit) untuk jalur flush: pause/cancel/selesai/add.
    // apply() async bisa hilang bila proses mati sebelum antrean tulis jalan.
    fun saveImmediate(items: List<DownloadItem>) {
        persistItems(items, blocking = true)
    }

    /** Snapshot yang SUDAH ter-encode sekali untuk pola apply-dulu +
     *  commit-kemudian pada flush main thread (issue #370): tanpa ini
     *  flushSave membayar encode JSON + peta kredensial yang sama dua
     *  kali untuk data yang identik. Urutan antar-snapshot dijaga nomor
     *  urut di writePrepared — snapshot basi tidak menimpa yang baru. */
    class PreparedSave internal constructor(
        internal val seq: Long,
        internal val encoded: String,
        internal val progressVersion: Long
    )

    fun prepareSave(items: List<DownloadItem>): PreparedSave? =
        preparePersist(items, userCleared = false)

    fun applyPrepared(prepared: PreparedSave) = writePrepared(prepared, blocking = false)

    fun commitPrepared(prepared: PreparedSave) = writePrepared(prepared, blocking = true)

    /** Penghapusan eksplisit pengguna saat antrean kosong: tulis blob kosong
     *  dan lepas flag degraded agar sisa blob korup tak menetap selamanya.
     *  Hanya untuk aksi clear/hapus eksplisit, bukan save rutin. */
    fun clearAll() {
        persistItems(emptyList(), blocking = true, userCleared = true)
    }

    private fun persistItems(items: List<DownloadItem>, blocking: Boolean, userCleared: Boolean = false) {
        val prepared = preparePersist(items, userCleared) ?: return
        writePrepared(prepared, blocking)
    }

    @Synchronized
    private fun preparePersist(items: List<DownloadItem>, userCleared: Boolean): PreparedSave? {
        // Load terdegradasi + daftar kosong = blob corrupt, bukan antrean
        // kosong sungguhan: jangan timpa blob mentah (forensik + pemulihan).
        // Pengecualian: penghapusan eksplisit pengguna (niat jelas) agar
        // blob korup tak menetap selamanya dan tak bisa pulih via clear.
        if (shouldBlockEmptySave(items.isEmpty(), degradedLoad, userCleared)) return null
        val encItems = items.map { item ->
            val stored = storedCreds[item.id]
            if (stored != null && (shouldRestoreStored(item.username, stored.first) ||
                        shouldRestoreStored(item.password, stored.second) ||
                        shouldRestoreStored(item.headers, stored.third) ||
                        shouldRestoreStored(item.postBody, stored.fourth))
            ) {
                // Sebagian field gagal di-decrypt: enkripsi ulang per field
                // yang sehat, pertahankan blob asli untuk yang gagal.
                item.copy(
                    username = if (shouldRestoreStored(item.username, stored.first)) stored.first
                    else Crypto.encrypt(item.username),
                    password = if (shouldRestoreStored(item.password, stored.second)) stored.second
                    else Crypto.encrypt(item.password),
                    headers = if (shouldRestoreStored(item.headers, stored.third)) stored.third
                    else Crypto.encrypt(item.headers),
                    postBody = if (shouldRestoreStored(item.postBody, stored.fourth)) stored.fourth
                    else Crypto.encrypt(item.postBody)
                )
            } else {
                val (encUser, encPass, encHeaders, encBody) = encryptedCreds(item)
                item.copy(username = encUser, password = encPass, headers = encHeaders, postBody = encBody)
            }
        }
        storedCreds.clear()
        encItems.forEach { storedCreds[it.id] = Quad(it.username, it.password, it.headers, it.postBody) }
        if (items.isNotEmpty() || userCleared) degradedLoad = false
        return PreparedSave(++persistSeq, DownloadItemCodec.encode(encItems), progressVersion)
    }

    @Synchronized
    private fun writePrepared(prepared: PreparedSave, blocking: Boolean) {
        // Snapshot basi (nomor urut lebih kecil dari yang sudah tertulis)
        // tidak boleh menimpa snapshot yang lebih baru.
        if (prepared.seq < writtenPersistSeq) return
        prefs.edit(commit = blocking) {
            putString(KEY_ITEMS, prepared.encoded)
        }
        // Snapshot penuh memuat progres saat ia di-encode -> hapus
        // progres ringan hanya bila belum ada progres yang lebih
        // baru mendarat sesudah encode itu. Progres kini di berkas
        // sendiri (issue #1433): penghapusan adalah commit terpisah,
        // tetapi tetap di dalam monitor yang sama dengan saveProgress
        // sehingga tak ada progres baru yang bisa menyelinap di antara
        // cek versi dan penghapusan; putus proses di antara kedua
        // commit hanya menyisakan progres senilai encode snapshot itu
        // sendiri, yang overlay-nya ke snapshot adalah no-op.
        if (progressVersion == prepared.progressVersion) {
            progressPrefs.edit(commit = blocking) { remove(KEY_PROGRESS) }
        }
        writtenPersistSeq = prepared.seq
    }

    private fun encryptedCreds(item: DownloadItem): Quad {
        // headers/postBody bisa memuat token Authorization dan secret POST,
        // jadi ikut dienkripsi. Kunci cache adalah hash SHA-256 agar plaintext
        // kredensial tidak tertahan lama di memori sebagai kunci HashMap.
        val cacheKey = cacheKeyOf(item.username, item.password, item.headers, item.postBody)
        credCache[cacheKey]?.let { return it }
        val pair = Quad(
            Crypto.encrypt(item.username),
            Crypto.encrypt(item.password),
            Crypto.encrypt(item.headers),
            Crypto.encrypt(item.postBody)
        )
        // Hasil fallback plaintext TIDAK ikut ter-cache, issue #353:
        // satu glitch Keystore sesaat tidak boleh membuat Quad plain:
        // dipakai ulang seluruh sesi proses; tanpa cache, save
        // berikutnya mencoba enkripsi lagi dan pulih sendiri begitu
        // Keystore sehat.
        val hasPlainFallback = listOf(pair.first, pair.second, pair.third, pair.fourth)
            .any { it.startsWith(Crypto.PLAIN_PREFIX) }
        if (hasPlainFallback) return pair
        // Cache dibatasi: sesi panjang dengan banyak kredensial unik tidak
        // boleh menumpuk (entries lama yang tidak terpakai dibuang).
        // synchronized(credCache): eviction (size + iterator + remove) harus atomic
        // supaya tidak ada dua thread yang sama-sama evict secara bersamaan.
        synchronized(credCache) {
            if (credCache.size > MAX_CRED_CACHE) {
                val half = credCache.size / 2
                var removed = 0
                val iter = credCache.keys.iterator()
                while (iter.hasNext() && removed < half) { iter.next(); iter.remove(); removed++ }
            }
            credCache[cacheKey] = pair
        }
        return pair
    }

    private fun cacheKeyOf(vararg parts: String): String {
        // Tanpa Formatter per byte + tanpa list/array antara: hash string langsung.
        return com.tasirin.httpdownloadmanager.util.Hex.encode(
            com.tasirin.httpdownloadmanager.util.sha256Strings(*parts)
        )
    }

    // Nilai terenkripsi empat field sensitif; dipakai sebagai value cache.
    private data class Quad(val first: String, val second: String, val third: String, val fourth: String)


    companion object {
        private const val KEY_ITEMS = "items"
        private const val KEY_PROGRESS = "progress"
        private const val MAX_CRED_CACHE = 128
    }
}

/** Pulihkan nilai tersimpan bila field polos kosong tapi simpanan tak kosong:
 *  decrypt yang gagal tak pernah menghasilkan "" dari simpanan tak kosong
 *  (plaintext lama lolos apa adanya, prefix "plain:" kembali "" hanya bila
 *  aslinya memang kosong), dan engine tak pernah mengosongkan kredensial
 *  item yang sudah ada — jadi ini selalu berarti kegagalan decrypt, bukan
 *  pengosongan oleh user. */
internal fun shouldRestoreStored(currentPlain: String, storedEnc: String?): Boolean =
    currentPlain.isEmpty() && !storedEnc.isNullOrEmpty()

/** Tulis daftar kosong diblokir bila load terakhir terdegradasi (blob
 *  korup), kecuali penghapusan eksplisit pengguna. Murni agar bisa diuji. */
internal fun shouldBlockEmptySave(itemsEmpty: Boolean, degraded: Boolean, userCleared: Boolean): Boolean =
    itemsEmpty && degraded && !userCleared

/** Sumber progres saat load (issue #1433): berkas progres baru selalu
 *  menang; warisan di berkas antrean lama hanya dipakai (dan dimigrasi)
 *  bila berkas baru belum punya progres. Murni agar bisa diuji; nilai
 *  harapan dibuktikan port Python 1:1 (audit-dm-r14/port-1433.py). */
internal enum class ProgressSource { NEW_FILE, LEGACY_MIGRATE, NONE }

internal fun progressSource(newHas: Boolean, legacyHas: Boolean): ProgressSource = when {
    newHas -> ProgressSource.NEW_FILE
    legacyHas -> ProgressSource.LEGACY_MIGRATE
    else -> ProgressSource.NONE
}
