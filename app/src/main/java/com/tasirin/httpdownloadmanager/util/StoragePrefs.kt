package com.tasirin.httpdownloadmanager.util

import android.content.Context
import android.net.Uri
import java.security.MessageDigest
import androidx.core.content.edit
import androidx.core.net.toUri

object StoragePrefs {

    const val DEFAULT_PORT = 8080

    private const val PREFS = "storage_settings"
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
    private const val KEY_GALLERY_IMAGE_FOLDER = "gallery_image_folder"
    private const val KEY_GALLERY_VIDEO_FOLDER = "gallery_video_folder"
    private const val KEY_COLLAPSED_SECTIONS = "collapsed_sections"
    private const val KEY_THUMB_CLEANUP_LAST = "thumb_cleanup_last"

    /** Seksi pengaturan yang sedang dilipat (kartu bisa dibuka/tutup). */
    fun isSectionCollapsed(context: Context, key: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_COLLAPSED_SECTIONS, emptySet())
            ?.contains(key) == true

    fun setSectionCollapsed(context: Context, key: String, collapsed: Boolean) {
        val set = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_COLLAPSED_SECTIONS, emptySet())!!.toMutableSet()
        if (collapsed) set.add(key) else set.remove(key)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit {
                putStringSet(KEY_COLLAPSED_SECTIONS, set)
            }
    }

    fun getFolderUri(context: Context): Uri? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_FOLDER_URI, null)
        return raw?.takeIf { it.isNotEmpty() }?.let { it.toUri() }
    }

    fun saveFolder(context: Context, uri: Uri?, name: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_FOLDER_URI, uri?.toString())
            putString(KEY_FOLDER_NAME, name)
        }
    }

    fun isBackgroundEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_BACKGROUND, true)

    fun setBackgroundEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_BACKGROUND, enabled)
        }
    }

    fun isAutoStartEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTOSTART, true)

    fun setAutoStartEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_AUTOSTART, enabled)
        }
    }

    fun isServerBackgroundEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SERVER_BACKGROUND, false)

    fun setServerBackgroundEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_SERVER_BACKGROUND, enabled)
        }
    }

    fun getTextFolder(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TEXT_FOLDER, null)?.takeIf { it.isNotBlank() }

    fun getExtraFolders(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_EXTRA_FOLDERS, null)
            ?.split("\n")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    fun setExtraFolders(context: Context, folders: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(
                KEY_EXTRA_FOLDERS,
                folders.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n")
            )
        }
    }

    fun setTextFolder(context: Context, path: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_TEXT_FOLDER, path?.takeIf { it.isNotBlank() })
        }
    }

    fun isBatteryExemptEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_BATTERY_EXEMPT, true)

    fun setBatteryExemptEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_BATTERY_EXEMPT, enabled)
        }
    }

    fun getServerPin(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SERVER_PIN, null)?.takeIf { it.isNotBlank() }

    /** Simpan PIN sebagai hash SHA-256; nilai kosong menghapus PIN. */
    fun setServerPin(context: Context, pin: String?) {
        val hash = pin?.trim()?.takeIf { it.isNotEmpty() }?.let { sha256Hex(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_SERVER_PIN, hash)
        }
    }

    /** Normalisasi PIN tersimpan menjadi hash SHA-256. Nilai lama dari versi
     *  sebelum hash berupa plaintext — di-hash sekali supaya cookie lama tetap
     *  berlaku tanpa memaksa login ulang. */
    fun storedPinHash(context: Context): String? {
        val stored = getServerPin(context).orEmpty()
        return normalizePinHash(stored)
    }

    /** Cek PIN yang dimasukkan terhadap yang tersimpan dengan pembandingan
     *  constant-time (anti timing attack lewat perbedaan panjang loop). */
    fun pinMatches(context: Context, pin: String): Boolean {
        val expected = storedPinHash(context) ?: return false
        return constantEquals(sha256Hex(pin), expected)
    }

    fun isPinEnforced(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PIN_ENFORCED, true)

    fun setPinEnforced(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_PIN_ENFORCED, enabled)
        }
    }

    fun isFsFullAccessEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_FS_FULL_ACCESS, false)

    fun setFsFullAccessEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_FS_FULL_ACCESS, enabled)
        }
    }

    /** Server remote read-only: upload, ubah file, dan hapus media ditolak. */
    fun isServerReadOnly(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SERVER_READ_ONLY, false)

    fun setServerReadOnly(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_SERVER_READ_ONLY, enabled)
        }
    }

    fun isServerAutoStartEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SERVER_AUTOSTART, true)

    /** Server hanya boleh start otomatis bila PIN wajib sudah disetel. */
    fun isServerStartAllowed(context: Context): Boolean =
        !isPinEnforced(context) || !getServerPin(context).isNullOrEmpty()

    fun setServerAutoStartEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_SERVER_AUTOSTART, enabled)
        }
    }

    fun maxConcurrent(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MAX_CONCURRENT, 2).coerceIn(1, 5)

    fun setMaxConcurrent(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putInt(KEY_MAX_CONCURRENT, value.coerceIn(1, 5))
        }
    }

    fun speedLimitKbps(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SPEED_LIMIT, 0).coerceIn(0, 100_000)

    fun setSpeedLimitKbps(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putInt(KEY_SPEED_LIMIT, value.coerceIn(0, 100_000))
        }
    }

    fun maxRetries(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MAX_RETRIES, 2).coerceIn(0, 5)

    fun setMaxRetries(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putInt(KEY_MAX_RETRIES, value.coerceIn(0, 5))
        }
    }

    fun isAutoSortEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_SORT, false)

    fun setAutoSortEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_AUTO_SORT, enabled)
        }
    }

    fun isSmallFirstEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SMALL_FIRST, false)

    fun setSmallFirstEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_SMALL_FIRST, enabled)
        }
    }

    fun isDeletePartialOnCancel(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DELETE_PARTIAL_ON_CANCEL, false)

    fun setDeletePartialOnCancel(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_DELETE_PARTIAL_ON_CANCEL, enabled)
        }
    }

    fun recentUrls(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_RECENT_URLS, updated.joinToString("\n"))
        }
    }

    fun serverPort(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SERVER_PORT, DEFAULT_PORT).coerceIn(1024, 65535)

    fun setServerPort(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putInt(KEY_SERVER_PORT, value.coerceIn(1024, 65535))
        }
    }

    fun segmentCount(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SEGMENTS, 4).coerceIn(1, 8)

    fun setSegmentCount(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putInt(KEY_SEGMENTS, value.coerceIn(1, 8))
        }
    }

    fun sortMode(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SORT_MODE, 0).coerceIn(0, 6)

    fun setSortMode(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putInt(KEY_SORT_MODE, value.coerceIn(0, 6))
        }
    }

    fun getConnectTimeoutSec(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CONNECT_TIMEOUT_SEC, 15)

    fun setConnectTimeoutSec(context: Context, sec: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putInt(KEY_CONNECT_TIMEOUT_SEC, sec.coerceIn(5, 120))
        }
    }

    fun getReadTimeoutSec(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_READ_TIMEOUT_SEC, 30)

    fun setReadTimeoutSec(context: Context, sec: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putInt(KEY_READ_TIMEOUT_SEC, sec.coerceIn(10, 300))
        }
    }

    fun getGalleryImageFolder(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_GALLERY_IMAGE_FOLDER, null)?.takeIf { it.isNotBlank() }

    fun setGalleryImageFolder(context: Context, path: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_GALLERY_IMAGE_FOLDER, path?.takeIf { it.isNotBlank() }?.trim())
        }
    }

    fun getGalleryVideoFolder(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_GALLERY_VIDEO_FOLDER, null)?.takeIf { it.isNotBlank() }

    fun setGalleryVideoFolder(context: Context, path: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_GALLERY_VIDEO_FOLDER, path?.takeIf { it.isNotBlank() }?.trim())
        }
    }

    fun clearRecentUrls(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_RECENT_URLS, "")
        }
    }

    /** Terakhir kali thumbnail cache dibersihkan otomatis (0 = belum pernah). */
    fun lastThumbCleanup(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_THUMB_CLEANUP_LAST, 0L)

    fun setThumbCleanupDone(context: Context, timeMs: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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
