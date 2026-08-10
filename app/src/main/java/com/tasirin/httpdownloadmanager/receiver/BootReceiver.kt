package com.tasirin.httpdownloadmanager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tasirin.httpdownloadmanager.util.StoragePrefs

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val downloadAutostart = StoragePrefs.isAutoStartEnabled(context)
        val serverAutostart = StoragePrefs.isServerAutoStartEnabled(context) &&
            StoragePrefs.isServerStartAllowed(context)
        if (!downloadAutostart && !serverAutostart) return
        // targetSdk 35: dataSync FGS tidak boleh start langsung dari boot;
        // jadwalkan JobScheduler yang meneruskan ke DownloadService.
        BootResumeJobService.schedule(context)
    }
}
