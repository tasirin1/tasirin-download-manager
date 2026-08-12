package com.tasirin.httpdownloadmanager.util

import android.graphics.Color
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Android 15 (targetSdk 35) memaksa edge-to-edge: konten digambar di bawah
 *  status/navigation bar. Panggil setelah setContentView dengan root view;
 *  padding root otomatis menyesuaikan insets system bar (aman Android 5+). */
fun ComponentActivity.applyEdgeToEdge(root: View) {
    // App selalu tema terang: paksa ikon status/nav bar gelap (light style)
    // supaya kontras walau mode gelap sistem aktif.
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
    )
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.setPadding(0, bars.top, 0, bars.bottom)
        WindowInsetsCompat.CONSUMED
    }
}
