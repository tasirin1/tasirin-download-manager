package com.tasirin.httpdownloadmanager.download

/** Pelacak kecepatan EMA + ETA (murni, bisa diuji JVM; jam bisa di-inject). */
class SpeedTracker(
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    // Satu map per id (bukan 3 map): 1 lookup + 1 lock per sample multi-segmen.
    // ema null = belum ada pengukuran instan; sampel kedua langsung memakai
    // instan penuh sebagai seed (bukan 20% dari instan karena terseret baseline 0).
    private data class Entry(var bytes: Long, var time: Long, var ema: Double?)
    private val entries = HashMap<String, Entry>()

    @Synchronized
    fun sample(id: String, bytes: Long, total: Long): Pair<Long, Long> {
        val now = clock()
        val e = entries[id]
        val prevB = e?.bytes ?: bytes
        val prevT = e?.time ?: now
        val instant = if (now > prevT) ((bytes - prevB) * 1000L) / (now - prevT) else 0L
        // EMA: kecepatan rata-rata bergerak supaya ETA tidak melompat-lompat
        // akibat lonjakan kecepatan sesaat. Sampel pertama belum punya delta
        // waktu sehingga speed 0; sampel kedua menjadi seed penuh agar ETA
        // langsung akurat, bukan terseret baseline 0.
        // null dipertahankan (bukan 0.0) supaya sampel berikutnya tahu
        // bahwa belum ada pengukuran instan dan memakai instan penuh.
        val prev = e?.ema
        val newEma: Double? = if (instant > 0L) {
            if (prev == null) instant.toDouble()
            else prev * (1.0 - EMA_ALPHA) + instant * EMA_ALPHA
        } else {
            prev
        }
        entries[id] = Entry(bytes, now, newEma)
        val speed = (newEma ?: 0.0).toLong()
        val eta = if (speed > 0 && total > bytes) (total - bytes) / speed else 0L
        return speed to eta
    }

    @Synchronized
    fun reset(id: String) {
        entries.remove(id)
    }

    private companion object {
        const val EMA_ALPHA = 0.2
    }
}
