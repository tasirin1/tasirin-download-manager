package com.tasirin.httpdownloadmanager.data

import android.content.Context
import androidx.core.content.edit
import com.tasirin.httpdownloadmanager.util.Crypto
import java.util.Collections

class DownloadRepository(context: Context) {

    private val prefs = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)
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

    /** Apakah prefs menyimpan blob antrean (bedakan fresh install vs corrupt). */
    fun hadStoredItems(): Boolean = prefs.contains(KEY_ITEMS)

    // Sinkron dengan persistItems: save dari thread UI (flush) dan IO (job)
    // bisa tumpang tindih dan merusak storedCreds (HashMap polos) sehingga
    // kredensial pulihan-decrypt tertimpa/nol permanen.
    @Synchronized
    fun load(): List<DownloadItem> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        val arrLen = runCatching { org.json.JSONArray(raw).length() }.getOrDefault(-1)
        val items = DownloadItemCodec.decode(raw).map { item ->
            // Kredensial dan secret header/body disimpan terenkripsi (API 23+);
            // Android 5.0-5.1 menyimpan plaintext (Keystore AES belum tersedia)
            // dan nilai lama tanpa prefix lolos apa adanya lewat decrypt.
            item.copy(
                username = Crypto.decrypt(item.username),
                password = Crypto.decrypt(item.password),
                headers = Crypto.decrypt(item.headers),
                postBody = Crypto.decrypt(item.postBody)
            )
        }
        degradedLoad = items.isEmpty() && (arrLen != 0)
        storedCreds.clear()
        storedCreds.putAll(readStoredCreds(raw))
        return DownloadItemCodec.overlayProgress(items, prefs.getString(KEY_PROGRESS, null))
    }

    /** Baca kredensial tersimpan (masih terenkripsi) per id dari blob mentah. */
    private fun readStoredCreds(raw: String): Map<String, Quad> {
        val out = HashMap<String, Quad>()
        val arr = runCatching { org.json.JSONArray(raw) }.getOrNull() ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            out[id] = Quad(
                o.optString("username", ""),
                o.optString("password", ""),
                o.optString("headers", ""),
                o.optString("postBody", "")
            )
        }
        return out
    }

    /** Simpan progres kompak (id -> bytes/total) tanpa enkripsi & tanpa detail
     *  segmen; dipanggil berkala selama download aktif. Commit sinkron (bukan
     *  apply): apply async bisa mendarat SETELAH commit snapshot penuh
     *  (persistItems + remove KEY_PROGRESS) sehingga progres basi menimpa
     *  data segar saat load. Dipanggil dari thread IO engine, bukan main. */
    fun saveProgress(items: List<DownloadItem>) {
        prefs.edit(commit = true) {
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

    @Synchronized
    private fun persistItems(items: List<DownloadItem>, blocking: Boolean) {
        // Load terdegradasi + daftar kosong = blob corrupt, bukan antrean
        // kosong sungguhan: jangan timpa blob mentah (forensik + pemulihan).
        if (items.isEmpty() && degradedLoad) return
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
        if (items.isNotEmpty()) degradedLoad = false
        // Snapshot penuh sudah memuat progres terbaru -> hapus progres ringan
        // supaya tidak menimpa data yang lebih lama saat load berikutnya.
        prefs.edit(commit = blocking) {
            putString(KEY_ITEMS, DownloadItemCodec.encode(encItems))
            remove(KEY_PROGRESS)
        }
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
