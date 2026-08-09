package com.tasirin.httpdownloadmanager.download

/** Pelacak kecepatan EMA + ETA (murni, bisa diuji JVM; jam bisa di-inject). */
class SpeedTracker(
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val lastBytes = HashMap<String, Long>()
    private val lastTime = HashMap<String, Long>()
    private val emaSpeed = HashMap<String, Double>()

    @Synchronized
    fun sample(id: String, bytes: Long, total: Long): Pair<Long, Long> {
        val now = clock()
        val prevB = lastBytes[id] ?: bytes
        val prevT = lastTime[id] ?: now
        lastBytes[id] = bytes
        lastTime[id] = now
        val instant = if (now > prevT) ((bytes - prevB) * 1000L) / (now - prevT) else 0L
        // EMA: kecepatan rata-rata bergerak supaya ETA tidak melompat-lompat
        // akibat lonjakan kecepatan sesaat.
        val smoothed = if (instant > 0L) {
            val prev = emaSpeed[id] ?: instant.toDouble()
            prev * (1.0 - EMA_ALPHA) + instant * EMA_ALPHA
        } else {
            emaSpeed[id] ?: 0.0
        }
        emaSpeed[id] = smoothed
        val speed = smoothed.toLong()
        val eta = if (speed > 0 && total > bytes) (total - bytes) / speed else 0L
        return speed to eta
    }

    @Synchronized
    fun reset(id: String) {
        lastBytes.remove(id)
        lastTime.remove(id)
        emaSpeed.remove(id)
    }

    private companion object {
        const val EMA_ALPHA = 0.2
    }
}
