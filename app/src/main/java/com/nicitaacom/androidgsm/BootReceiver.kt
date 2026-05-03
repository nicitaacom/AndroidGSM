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
        Log.d("BootReceiver", "Boot completed — not auto-starting service")
    }
}