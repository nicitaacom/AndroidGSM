package com.nicitaacom.androidgsm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // The gateway starts on every boot (his words 06.11.2026: "it should use that permission to auto-start so I
        // don't have to open it manually") - MIUI delivers this broadcast only with the dialer's Autostart switch on
        // (MainActivity asks for it once) and the app opened at least once after the install.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_START_AFTER_REBOOT, false).commit()
        Log.d("BootReceiver", "Boot completed — starting the gateway")
        val start = Intent(context, GsmService::class.java).setAction(GsmService.ACTION_START_SERVICE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(start)
        else context.startService(start)
    }

    companion object {
        private const val PREFS = "boot"
        private const val KEY_START_AFTER_REBOOT = "startAfterReboot"

        // commit, not apply - the reboot right after must not beat the write to disk
        fun markStartAfterReboot(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_START_AFTER_REBOOT, true).commit()
        }
    }
}