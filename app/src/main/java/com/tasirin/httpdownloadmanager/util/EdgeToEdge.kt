package com.tasirin.httpdownloadmanager.util

import android.graphics.Color
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Android 15 (targetSdk 36) memaksa edge-to-edge: konten digambar di bawah
 *  status/navigation bar. Panggil setelah setContentView dengan root view;
 *  padding root otomatis menyesuaikan insets system bar (aman Android 5+). */
fun ComponentActivity.applyEdgeToEdge(root: View, darkBars: Boolean = true) {
    // Semua layar kini bertema gelap (keputusan owner 2026-10-30):
    // default ikon status/nav bar TERANG. darkBars = false hanya bila ada
    // layar berlatar terang lagi suatu hari.
    val barStyle = if (darkBars) {
        SystemBarStyle.dark(Color.TRANSPARENT)
    } else {
        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
    }
    enableEdgeToEdge(
        statusBarStyle = barStyle,
        navigationBarStyle = barStyle
    )
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.setPadding(0, bars.top, 0, bars.bottom)
        WindowInsetsCompat.CONSUMED
    }
}
