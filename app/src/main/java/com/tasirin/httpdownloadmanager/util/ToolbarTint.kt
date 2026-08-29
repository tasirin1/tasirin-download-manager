package com.tasirin.httpdownloadmanager.util

import android.graphics.Color
import androidx.appcompat.widget.Toolbar

/** Toolbar dipakai dengan background primary — ikon navigasi (panah kembali)
 *  perlu di-tint putih agar kontras. Dipanggil via post karena ikon dibuat
 *  tidak sinkron oleh AppCompat setelah setDisplayHomeAsUpEnabled. */
fun Toolbar.whiteNavigationIcon() {
    post {
        val icon = navigationIcon?.mutate() ?: return@post
        icon.setTint(Color.WHITE)
        navigationIcon = icon
    }
}
