package com.nicitaacom.androidgsm

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri

class CallInitiatorActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val number = intent.getStringExtra("number") ?: return finish()

        // 1. show when locked (API 27+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }

        // 2. keep screen on + dismiss keyguard
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)

        // 3. dismiss keyguard if needed (API 26+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val keyguardManager = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        }

        // 4. start call
        val callIntent = Intent(Intent.ACTION_CALL, "tel:$number".toUri())
        startActivity(callIntent)
        MainActivity.log("Call initiated automatically to $number")

        // 5. auto-close after 500ms (gives time for system dialer to launch)
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 500)
    }
}