package com.tasirin.httpdownloadmanager.util

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import androidx.core.content.edit
import androidx.core.net.toUri

object StoragePrefs {

    const val DEFAULT_PORT = 8080

    private const val PREFS = "storage_settings"
    private var cachedPrefs: android.content.SharedPreferences? = null

    /** Cache SharedPreferences instance untuk menghindari 58x getSharedPreferences call. */
    private fun prefs(context: Context): android.content.SharedPreferences {
        return cachedPrefs ?: context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .also { cachedPrefs = it }
    }
    private const val HEX_CHARS = "0123456789abcdef"
    private const val KEY_FOLDER_URI = "folder_uri"
    private const val KEY_FOLDER_NAME = "folder_name"
    private const val KEY_BACKGROUND = "background_download"
    private const val KEY_AUTOSTART = "auto_start_boot"
    private const val KEY_SERVER_BACKGROUND = "server_background"
    private const val KEY_SERVER_AUTOSTART = "server_autostart_boot"
    private const val KEY_SERVER_READ_ONLY = "server_read_only"
    private const val KEY_TEXT_FOLDER = "text_folder_path"
    private const val KEY_BATTERY_EXEMPT = "battery_exempt"
    private const val KEY_SERVER_PIN = "server_pin"
    private const val KEY_MAX_CONCURRENT = "max_concurrent"
    private const val KEY_SPEED_LIMIT = "speed_limit_kbps"
    private const val KEY_MAX_RETRIES = "max_retries"
    private const val KEY_RECENT_URLS = "recent_urls"
    private const val KEY_SERVER_PORT = "server_port"
    private const val KEY_SEGMENTS = "segments"
    private const val KEY_SORT_MODE = "sort_mode"
    private const val KEY_AUTO_SORT = "auto_sort"
    private const val KEY_SMALL_FIRST = "small_first"
    private const val KEY_DELETE_PARTIAL_ON_CANCEL = "delete_partial_on_cancel"
    private const val KEY_PIN_ENFORCED = "pin_enforced"
    private const val KEY_FS_FULL_ACCESS = "fs_full_access"
    private const val KEY_EXTRA_FOLDERS = "extra_folders"
    private const val KEY_CONNECT_TIMEOUT_SEC = "connect_timeout_sec"
    private const val KEY_READ_TIMEOUT_SEC = "read_timeout_sec"
    private const val KEY_COLLAPSED_SECTIONS = "collapsed_sections"
    private const val KEY_THUMB_CLEANUP_LAST = "thumb_cleanup_last"
    private const val KEY_PARTIAL_STREAM_SECRET = "partial_stream_secret"
    private const val KEY_SERVER_SESSION_SECRET = "server_session_secret"

    /** Seksi pengaturan yang sedang dilipat (kartu bisa dibuka/tutup). */
    fun isSectionCollapsed(context: Context, key: String): Boolean =
        prefs(context)
            .getStringSet(KEY_COLLAPSED_SECTIONS, emptySet())
            ?.contains(key) == true

    fun setSectionCollapsed(context: Context, key: String, collapsed: Boolean) {
        val set = prefs(context)
            .getStringSet(KEY_COLLAPSED_SECTIONS, emptySet())!!.toMutableSet()
        if (collapsed) set.add(key) else set.remove(key)
        prefs(context)
            .edit {
                putStringSet(KEY_COLLAPSED_SECTIONS, set)
            }
    }

    fun getFolderUri(context: Context): Uri? {
        val raw = prefs(context)
            .getString(KEY_FOLDER_URI, null)
        return raw?.takeIf { it.isNotEmpty() }?.let { it.toUri() }
    }

    fun saveFolder(context: Context, uri: Uri?, name: String?) {
        prefs(context).edit {
            putString(KEY_FOLDER_URI, uri?.toString())
            putString(KEY_FOLDER_NAME, name)
        }
    }

    fun isBackgroundEnabled(context: Context): Boolean =
        prefs(context)
            .getBoolean(KEY_BACKGROUND, true)

    fun setBackgroundEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(KEY_BACKGROUND, enabled)
        }
    }

    fun isAutoStartEnabled(context: Context): Boolean =
        prefs(context)
            .getBoolean(KEY_AUTOSTART, true)

    fun setAutoStartEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(KEY_AUTOSTART, enabled)
        }
    }

    fun isServerBackgroundEnabled(context: Context): Boolean =
        prefs(context)
            .getBoolean(KEY_SERVER_BACKGROUND, false)

    fun setServerBackgroundEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(KEY_SERVER_BACKGROUND, enabled)
        }
    }

    fun getTextFolder(context: Context): String? =
        prefs(context)
            .getString(KEY_TEXT_FOLDER, null)?.takeIf { it.isNotBlank() }

    fun getExtraFolders(context: Context): List<String> =
        prefs(context)
            .getString(KEY_EXTRA_FOLDERS, null)
            ?.split("\n")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    fun setExtraFolders(context: Context, folders: List<String>) {
        prefs(context).edit {
            putString(
                KEY_EXTRA_FOLDERS,
                folders.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n")
            )
        }
    }

    fun setTextFolder(context: Context, path: String?) {
        prefs(context).edit {
            putString(KEY_TEXT_FOLDER, path?.takeIf { it.isNotBlank() })
        }
    }

    fun isBatteryExemptEnabled(context: Context): Boolean =
        prefs(context)
            .getBoolean(KEY_BATTERY_EXEMPT, true)

    fun setBatteryExemptEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(KEY_BATTERY_EXEMPT, enabled)
        }
    }

    fun getServerPin(context: Context): String? =
        prefs(context)
            .getString(KEY_SERVER_PIN, null)?.takeIf { it.isNotBlank() }

    /** Simpan PIN sebagai PBKDF2-SHA256; nilai kosong menghapus PIN. */
    fun setServerPin(context: Context, pin: String?) {
        val hash = pin?.trim()?.takeIf { it.isNotEmpty() }?.let { PinHash.hash(it) }
        rotateServerSessionSecret(context)
        prefs(context).edit {
            putString(KEY_SERVER_PIN, hash)
        }
    }

    /** Nilai kredensial tersimpan; cukup untuk cek ada/tidaknya PIN. */
    fun storedPinHash(context: Context): String? {
        return getServerPin(context)
    }

    /** Cek PIN yang dimasukkan terhadap yang tersimpan dengan pembandingan
     *  constant-time (anti timing attack lewat perbedaan panjang loop). */
    fun pinMatches(context: Context, pin: String): Boolean {
        val stored = getServerPin(context) ?: return false
        if (PinHash.isModern(stored)) return PinHash.verify(pin, stored)

        // Nilai lama dimigrasikan sekali setelah PIN benar, tanpa memaksa logout.
        val expected = normalizePinHash(stored) ?: return false
        if (!constantEquals(sha256Hex(pin), expected)) return false
        prefs(context).edit { putString(KEY_SERVER_PIN, PinHash.hash(pin)) }
        return true
    }

    /** Secret acak untuk tanda tangan URL stream parsial; tidak memakai nama package. */
    @Synchronized
    fun partialStreamSecret(context: Context): String {
        prefs(context).getString(KEY_PARTIAL_STREAM_SECRET, null)?.takeIf { it.isNotEmpty() }?.let { return it }
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val value = Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        prefs(context).edit { putString(KEY_PARTIAL_STREAM_SECRET, value) }
        return value
    }

    /** Cookie sesi remote harus token acak, bukan turunan langsung dari PIN. */
    @Synchronized
    fun serverSessionSecret(context: Context): String {
        prefs(context).getString(KEY_SERVER_SESSION_SECRET, null)?.takeIf { it.isNotEmpty() }?.let { return it }
        return rotateServerSessionSecret(context)
    }

    @Synchronized
    fun rotateServerSessionSecret(context: Context): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val value = Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        prefs(context).edit { putString(KEY_SERVER_SESSION_SECRET, value) }
        return value
    }

    fun isPinEnforced(context: Context): Boolean =
        prefs(context)
            .getBoolean(KEY_PIN_ENFORCED, true)

    fun setPinEnforced(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(KEY_PIN_ENFORCED, enabled)
        }
    }

    fun isFsFullAccessEnabled(context: Context): Boolean =
        prefs(context)
            .getBoolean(KEY_FS_FULL_ACCESS, false)

    fun setFsFullAccessEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(KEY_FS_FULL_ACCESS, enabled)
        }
    }

    /** Server remote read-only: upload, ubah file, dan hapus media ditolak. */
    fun isServerReadOnly(context: Context): Boolean =
        prefs(context)
            .getBoolean(KEY_SERVER_READ_ONLY, false)

    fun setServerReadOnly(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(KEY_SERVER_READ_ONLY, enabled)
        }
    }

    fun isServerAutoStartEnabled(context: Context): Boolean =
        prefs(context)
            .getBoolean(KEY_SERVER_AUTOSTART, true)

    /** Server hanya boleh start otomatis bila PIN wajib sudah disetel. */
    fun isServerStartAllowed(context: Context): Boolean =
        !isPinEnforced(context) || !getServerPin(context).isNullOrEmpty()

    fun setServerAutoStartEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(KEY_SERVER_AUTOSTART, enabled)
        }
    }

    fun maxConcurrent(context: Context): Int =
        prefs(context)
            .getInt(KEY_MAX_CONCURRENT, 2).coerceIn(1, 5)

    fun setMaxConcurrent(context: Context, value: Int) {
        prefs(context).edit {
            putInt(KEY_MAX_CONCURRENT, value.coerceIn(1, 5))
        }
    }

    fun speedLimitKbps(context: Context): Int =
        prefs(context)
            .getInt(KEY_SPEED_LIMIT, 0).coerceIn(0, 100_000)

    fun setSpeedLimitKbps(context: Context, value: Int) {
        prefs(context).edit {
            putInt(KEY_SPEED_LIMIT, value.coerceIn(0, 100_000))
        }
    }

    fun maxRetries(context: Context): Int =
        prefs(context)
            .getInt(KEY_MAX_RETRIES, 2).coerceIn(0, 5)

    fun setMaxRetries(context: Context, value: Int) {
        prefs(context).edit {
            putInt(KEY_MAX_RETRIES, value.coerceIn(0, 5))
        }
    }

    fun isAutoSortEnabled(context: Context): Boolean =
        prefs(context)
            .getBoolean(KEY_AUTO_SORT, false)

    fun setAutoSortEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(KEY_AUTO_SORT, enabled)
        }
    }

    fun isSmallFirstEnabled(context: Context): Boolean =
        prefs(context)
            .getBoolean(KEY_SMALL_FIRST, false)

    fun setSmallFirstEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(KEY_SMALL_FIRST, enabled)
        }
    }

    fun isDeletePartialOnCancel(context: Context): Boolean =
        prefs(context)
            .getBoolean(KEY_DELETE_PARTIAL_ON_CANCEL, false)

    fun setDeletePartialOnCancel(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(KEY_DELETE_PARTIAL_ON_CANCEL, enabled)
        }
    }

    fun recentUrls(context: Context): List<String> =
        prefs(context)
            .getString(KEY_RECENT_URLS, "")
            .orEmpty()
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun addRecentUrl(context: Context, url: String) {
        val clean = url.trim()
        if (clean.isEmpty()) return
        val current = recentUrls(context).filter { it != clean }
        val updated = (listOf(clean) + current).take(20)
        prefs(context).edit {
            putString(KEY_RECENT_URLS, updated.joinToString("\n"))
        }
    }

    fun serverPort(context: Context): Int =
        prefs(context)
            .getInt(KEY_SERVER_PORT, DEFAULT_PORT).coerceIn(1024, 65535)

    fun setServerPort(context: Context, value: Int) {
        prefs(context).edit {
            putInt(KEY_SERVER_PORT, value.coerceIn(1024, 65535))
        }
    }

    fun segmentCount(context: Context): Int =
        prefs(context)
            .getInt(KEY_SEGMENTS, 4).coerceIn(1, 8)

    fun setSegmentCount(context: Context, value: Int) {
        prefs(context).edit {
            putInt(KEY_SEGMENTS, value.coerceIn(1, 8))
        }
    }

    fun sortMode(context: Context): Int =
        prefs(context)
            .getInt(KEY_SORT_MODE, 0).coerceIn(0, 6)

    fun setSortMode(context: Context, value: Int) {
        prefs(context).edit {
            putInt(KEY_SORT_MODE, value.coerceIn(0, 6))
        }
    }

    fun getConnectTimeoutSec(context: Context): Int =
        prefs(context)
            .getInt(KEY_CONNECT_TIMEOUT_SEC, 15)

    fun setConnectTimeoutSec(context: Context, sec: Int) {
        prefs(context).edit {
            putInt(KEY_CONNECT_TIMEOUT_SEC, sec.coerceIn(5, 120))
        }
    }

    fun getReadTimeoutSec(context: Context): Int =
        prefs(context)
            .getInt(KEY_READ_TIMEOUT_SEC, 30)

    fun setReadTimeoutSec(context: Context, sec: Int) {
        prefs(context).edit {
            putInt(KEY_READ_TIMEOUT_SEC, sec.coerceIn(10, 300))
        }
    }

    fun clearRecentUrls(context: Context) {
        prefs(context).edit {
            putString(KEY_RECENT_URLS, "")
        }
    }

    /** Terakhir kali thumbnail cache dibersihkan otomatis (0 = belum pernah). */
    fun lastThumbCleanup(context: Context): Long =
        prefs(context)
            .getLong(KEY_THUMB_CLEANUP_LAST, 0L)

    fun setThumbCleanupDone(context: Context, timeMs: Long) {
        prefs(context)
            .edit { putLong(KEY_THUMB_CLEANUP_LAST, timeMs) }
    }

    /** Murni (tanpa Context) supaya bisa diuji unit: nilai kosong -> null,
     *  hash 64-hex -> langsung dipakai, selain itu (plaintext lama) -> di-hash. */
    internal fun normalizePinHash(stored: String): String? {
        if (stored.isEmpty()) return null
        return if (stored.length == 64 && stored.all { it in HEX_CHARS }) stored
        else sha256Hex(stored)
    }

    /** Bandingkan dua string tanpa short-circuit (constant-time). */
    internal fun constantEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(
            a.toByteArray(Charsets.UTF_8),
            b.toByteArray(Charsets.UTF_8)
        )
}
