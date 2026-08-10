package com.tasirin.httpdownloadmanager.data

import android.content.Context
import com.tasirin.httpdownloadmanager.util.Crypto
import java.util.Collections

class DownloadRepository(context: Context) {

    private val prefs = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)
    // Cache hasil enkripsi kredensial per pasangan plaintext: menghindari AES
    // + IV acak diulang tiap kali save penuh (hot path download aktif).
    // synchronizedMap: save bisa dipanggil dari thread UI (flush) dan IO (job).
    private val credCache = Collections.synchronizedMap(HashMap<String, Pair<String, String>>())

    fun load(): List<DownloadItem> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        val items = DownloadItemCodec.decode(raw).map { item ->
            // Kredensial disimpan terenkripsi (API 23+); Android 5.0-5.1
            // menyimpan plaintext (Keystore AES belum tersedia) — aman di-dekripsi.
            item.copy(
                username = Crypto.decrypt(item.username),
                password = Crypto.decrypt(item.password)
            )
        }
        return DownloadItemCodec.overlayProgress(items, prefs.getString(KEY_PROGRESS, null))
    }

    /** Simpan progres kompak (id -> bytes/total) tanpa enkripsi & tanpa detail
     *  segmen; dipanggil berkala selama download aktif. */
    fun saveProgress(items: List<DownloadItem>) {
        prefs.edit().putString(KEY_PROGRESS, DownloadItemCodec.encodeProgress(items)).apply()
    }

    fun save(items: List<DownloadItem>) {
        val encItems = items.map { item ->
            val (encUser, encPass) = encryptedCreds(item)
            item.copy(username = encUser, password = encPass)
        }
        // Snapshot penuh sudah memuat progres terbaru -> hapus progres ringan
        // supaya tidak menimpa data yang lebih lama saat load berikutnya.
        prefs.edit()
            .putString(KEY_ITEMS, DownloadItemCodec.encode(encItems))
            .remove(KEY_PROGRESS)
            .apply()
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

    companion object {
        private const val KEY_ITEMS = "items"
        private const val KEY_PROGRESS = "progress"
        private const val MAX_CRED_CACHE = 128
    }
}
