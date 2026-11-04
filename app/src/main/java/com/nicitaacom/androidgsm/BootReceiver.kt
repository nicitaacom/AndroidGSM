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

        // Do not auto-start on boot — user must explicitly tap START SERVICE.
        // Auto-starting would send heartbeats indefinitely without user intent.
        // The one exception: a reboot REBOOT_PHONE asked for (GsmService.rebootPhone) - that command is the intent, and
        // the phone may sit in another room. MIUI dropped this broadcast on 04.11.2026 even with Autostart on - see
        // outreach-tool plan-04 "Start on boot"
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_START_AFTER_REBOOT, false)) {
            Log.d("BootReceiver", "Boot completed — not auto-starting service")
            return
        }
        prefs.edit().putBoolean(KEY_START_AFTER_REBOOT, false).commit()
        Log.d("BootReceiver", "Boot completed after REBOOT_PHONE — starting the gateway")
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