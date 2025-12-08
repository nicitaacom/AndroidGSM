package com.nicitaacom.androidgsm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build


class BootReceiver : BroadcastReceiver() {

    override fun onReceive(appContext: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            startServiceCompat(appContext, Intent(appContext, GsmService::class.java))
        }
    }

    private fun startServiceCompat(appContext: Context, intent: Intent) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            appContext.startForegroundService(intent)
        else
            appContext.startService(intent)
}
