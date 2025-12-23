package com.nicitaacom.androidgsm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri

class CallInitiatorActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val number = intent.getStringExtra("number")
        if (number.isNullOrEmpty()) {
            MainActivity.log("ERROR: CallInitiatorActivity - missing number")
            finish()
            return
        }

        // 1. check CALL_PHONE permission
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            MainActivity.log("ERROR: CALL_PHONE permission not granted")
            finish()
            return
        }

        try {
            // 2. show when locked (API 27+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            }

            // 3. keep screen on + dismiss keyguard
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)

            // 4. dismiss keyguard if needed (API 26+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val keyguardManager = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
                keyguardManager.requestDismissKeyguard(this, null)
            }

            // 5. start call with delay to ensure activity is ready
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val callIntent = Intent(Intent.ACTION_CALL, "tel:$number".toUri()).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(callIntent)
                    MainActivity.log("Call initiated automatically to $number")
                } catch (error: Exception) {
                    MainActivity.log("ERROR starting call: ${error.message}")
                    error.printStackTrace()
                }

                // 6. close after call starts
                Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1000)
            }, 300)

        } catch (error: Exception) {
            MainActivity.log("ERROR in CallInitiatorActivity: ${error.message}")
            error.printStackTrace()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        MainActivity.log("CallInitiatorActivity destroyed")
    }
}