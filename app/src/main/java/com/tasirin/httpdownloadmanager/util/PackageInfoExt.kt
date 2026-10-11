package com.tasirin.httpdownloadmanager.util

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

/** getPackageInfo lintas API: PackageInfoFlags (API 33+) dengan fallback
 *  int untuk minSdk 21 — tanpa warning deprecation di semua call site. */
@Suppress("DEPRECATION")
fun PackageManager.packageInfoCompat(packageName: String): PackageInfo =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    else getPackageInfo(packageName, 0)

/** versionCode lintas API: longVersionCode (API 28+) dengan fallback
 *  versionCode untuk minSdk 21 — tanpa warning deprecation. */
@Suppress("DEPRECATION")
fun PackageInfo.versionCodeCompat(): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()
