package com.tasirin.httpdownloadmanager.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/** Daftar izin runtime yang perlu diminta (duplikat lama ada di MainActivity
 *  dan SettingsActivity). Android 5-12 tidak memakai notifikasi runtime,
 *  Android 5-9 tidak butuh WRITE_EXTERNAL_STORAGE diminta di runtime. */
object Permissions {

    fun missingRuntime(context: Context): Array<String> {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= 23 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        return needed.toTypedArray()
    }

    /** Apakah "All files access" (Android 11+) belum diaktifkan. */
    fun needsAllFilesAccess(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()

    /** Buka halaman "All files access" khusus aplikasi (fallback ke daftar umum). */
    fun requestAllFilesAccess(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    "package:${context.packageName}".toUri()
                )
            )
        }.onFailure {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }
}
