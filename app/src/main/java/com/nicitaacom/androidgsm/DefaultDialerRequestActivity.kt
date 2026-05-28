package com.nicitaacom.androidgsm

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager

class DefaultDialerRequestActivity : Activity() {
    private val ROLE_REQUEST_CODE = 9001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val rm = getSystemService(Context.ROLE_SERVICE) as? RoleManager
                if (rm == null) {
                    MainActivity.log("RoleManager unavailable")
                    finish(); return
                }
                if (!rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                    MainActivity.log("ROLE_DIALER not available on this device")
                    finish(); return
                }
                if (rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
                    MainActivity.log("ROLE_DIALER already held")
                    finish(); return
                }
                val intent = rm.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                startActivityForResult(intent, ROLE_REQUEST_CODE)
            } else {
                // Pre-Android 10 fallback
                val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                    putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
                }
                startActivity(intent)
                finish()
            }
        } catch (e: Exception) {
            MainActivity.log("DefaultDialerRequestActivity error: ${e.message}")
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ROLE_REQUEST_CODE) {
            MainActivity.log(if (resultCode == RESULT_OK) "✅ Default dialer GRANTED" else "❌ Default dialer DENIED")
        }
        finish()
    }
}
