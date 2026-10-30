package com.tasirin.httpdownloadmanager.receiver

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import com.tasirin.httpdownloadmanager.App
import com.tasirin.httpdownloadmanager.download.DownloadService
import com.tasirin.httpdownloadmanager.util.StoragePrefs

/** Jembatan boot -> DownloadService untuk targetSdk 36.
 *
 *  Sejak Android 15 (targetSdk 36), FGS tipe `dataSync` TIDAK boleh di-start
 *  langsung dari receiver BOOT_COMPLETED. Solusi: receiver hanya menjadwalkan
 *  JobScheduler (tersedia sejak API 21; minSdk aplikasi 23); eksekusi job berhak
 *  men-start foreground service (pola yang sama dipakai WorkManager).
 */
class BootResumeJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        val context = applicationContext
        val downloadAutostart = StoragePrefs.isAutoStartEnabled(context)
        val serverAutostart = StoragePrefs.isServerAutoStartEnabled(context) &&
            StoragePrefs.isServerStartAllowed(context)
        if (!downloadAutostart && !serverAutostart) {
            jobFinished(params, false)
            return false
        }
        App.logEvent("BOOT/JOB: autostart download=$downloadAutostart, server=$serverAutostart")
        val started = runCatching {
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        if (started.isFailure) {
            // Kegagalan start wajib berjejak (issue #592): log autostart
            // di atas ditulis SEBELUM percobaan, jadi tanpa baris ini
            // log justru menyiratkan autostart berjalan padahal start
            // ditolak sistem dan autostart mati total.
            App.logEvent("BOOT/JOB: autostart FAILED: ${started.exceptionOrNull()?.message ?: "unknown error"}")
        }
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        private const val JOB_ID = 1001

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as android.app.job.JobScheduler
            val builder = JobInfo.Builder(
                JOB_ID,
                ComponentName(context, BootResumeJobService::class.java)
            )
                .setPersisted(true) // bertahan lintas reboot
                .setMinimumLatency(15_000L) // biarkan sistem settle setelah boot
                .setOverrideDeadline(30_000L)
            val result = scheduler.schedule(builder.build())
            if (result <= 0) {
                android.util.Log.w("BootResume", "JobScheduler.schedule failed (result=$result)")
            }
        }
    }
}
