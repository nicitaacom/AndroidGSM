package com.nicitaacom.androidgsm

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.telecom.TelecomManager

class DefaultDialerRequestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val pkg = packageName
            val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, pkg)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            MainActivity.log("DefaultDialerRequestActivity error: ${e.message}")
        } finally {
            finish()
        }
    }
}
