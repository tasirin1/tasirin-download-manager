package com.tasirin.httpdownloadmanager.util

import android.content.pm.PackageInfo
import android.os.Build

/** versionCode lintas API: longVersionCode (API 28+) dengan fallback
 *  versionCode untuk minSdk 21 — tanpa warning deprecation. */
@Suppress("DEPRECATION")
fun PackageInfo.versionCodeCompat(): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()
