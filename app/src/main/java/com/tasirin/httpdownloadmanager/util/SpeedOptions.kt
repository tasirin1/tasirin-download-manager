package com.tasirin.httpdownloadmanager.util

/** Opsi batas kecepatan (Kbps) dipakai spinner MainActivity & SettingsActivity. */
/* Disentralisasi agar daftar tidak dobel dan mudah diubah di satu tempat. */
object SpeedOptions {
    val SPEED_KBPS = intArrayOf(0, 128, 256, 512, 1024, 2048, 5120)
}
