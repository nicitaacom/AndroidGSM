package com.nicitaacom.androidgsm

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Shown by the system during an active call (we are default dialer).
 * Displays number, call duration, and hang-up button.
 * Also fixes the "Mobile network not available" stuck state — MIUI requires
 * a real Activity from the default dialer app to properly manage call UI state.
 */
class CallActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var numberText: TextView
    private lateinit var durationText: TextView
    private lateinit var micLabel: TextView
    private lateinit var hangUpButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private var elapsedSeconds = 0
    private var isConnected = false

    private val ticker = object : Runnable {
        override fun run() {
            if (isConnected) {
                elapsedSeconds++
                durationText.text = formatDuration(elapsedSeconds)
                handler.postDelayed(this, 1000)
            }
        }
    }

    companion object {
        var instance: CallActivity? = null
            private set

        fun notifyConnected() {
            instance?.runOnUiThread {
                instance?.onCallConnected()
            }
        }

        fun notifyEnded() {
            instance?.runOnUiThread {
                instance?.finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over lock screen, turn on screen
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        setContentView(R.layout.activity_call)
        instance = this

        statusText = findViewById(R.id.callStatusText)
        numberText = findViewById(R.id.callNumberText)
        durationText = findViewById(R.id.callDurationText)
        micLabel = findViewById(R.id.micSourceLabel)
        hangUpButton = findViewById(R.id.hangUpButton)

        // Populate number from intent
        val number = intent.getStringExtra("number") ?: ""
        numberText.text = number
        statusText.text = "Calling..."
        durationText.text = ""

        hangUpButton.setOnClickListener {
            val call = GsmInCallService.currentCall
            if (call != null) {
                call.disconnect()
            } else {
                // Fallback: send CALL_ENDED via backend
                MainActivity.log("CallActivity: no Call object, sending hangup via backend")
            }
            finish()
        }

        MainActivity.log("CallActivity: showing call screen for $number")
    }

    fun onCallConnected() {
        isConnected = true
        statusText.text = "Connected"
        micLabel.text = if (AudioWebSocketHandler.useBrowserMicUplink) "MIC: BROWSER" else "MIC: PHONE"
        elapsedSeconds = 0
        handler.post(ticker)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(ticker)
        if (instance === this) instance = null
    }

    private fun formatDuration(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return "%02d:%02d".format(m, s)
    }
}
