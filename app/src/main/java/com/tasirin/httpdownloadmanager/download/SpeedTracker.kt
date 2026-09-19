package com.tasirin.httpdownloadmanager.download

/** Pelacak kecepatan EMA + ETA (murni, bisa diuji JVM; jam bisa di-inject). */
class SpeedTracker(
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    // Satu map per id (bukan 3 map): 1 lookup + 1 lock per sample multi-segmen.
    private data class Entry(var bytes: Long, var time: Long, var ema: Double)
    private val entries = HashMap<String, Entry>()

    @Synchronized
    fun sample(id: String, bytes: Long, total: Long): Pair<Long, Long> {
        val now = clock()
        val e = entries[id]
        val prevB = e?.bytes ?: bytes
        val prevT = e?.time ?: now
        val instant = if (now > prevT) ((bytes - prevB) * 1000L) / (now - prevT) else 0L
        // EMA: kecepatan rata-rata bergerak supaya ETA tidak melompat-lompat
        // akibat lonjakan kecepatan sesaat.
        val smoothed = if (instant > 0L) {
            val prev = e?.ema ?: instant.toDouble()
            prev * (1.0 - EMA_ALPHA) + instant * EMA_ALPHA
        } else {
            e?.ema ?: 0.0
        }
        entries[id] = Entry(bytes, now, smoothed)
        val speed = smoothed.toLong()
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
