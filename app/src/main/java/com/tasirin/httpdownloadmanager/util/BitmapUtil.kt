package com.tasirin.httpdownloadmanager.util

import android.graphics.Bitmap
import androidx.core.graphics.scale

/** Turunkan ukuran bitmap agar sisi terpanjang <= [max], lalu recycle sumber
 *  bila dibuat salinan (pemanggil wajib recycle hasilnya). Dipakai galeri,
 *  remote web (thumbnail & media), dan pemutar video supaya konsisten. */
fun scaleDown(src: Bitmap, max: Int): Bitmap {
    if (src.width <= max && src.height <= max) return src
    val scale = max.toDouble() / maxOf(src.width, src.height)
    val w = (src.width * scale).toInt().coerceAtLeast(1)
    val h = (src.height * scale).toInt().coerceAtLeast(1)
    val out = src.scale(w, h, true)
    if (out !== src) src.recycle()
    return out
}
