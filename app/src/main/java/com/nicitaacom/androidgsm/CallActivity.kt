package com.nicitaacom.androidgsm

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.VideoProfile
import android.view.WindowManager
import android.view.View
import android.widget.ImageButton
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
    private lateinit var callerInitialText: TextView
    private lateinit var answerButton: ImageButton
    private lateinit var declineButton: ImageButton
    private lateinit var hangUpButton: ImageButton

    private val handler = Handler(Looper.getMainLooper())
    private var elapsedSeconds = 0
    private var isConnected = false
    private var activeCall: Call? = null

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            runOnUiThread { updateUiForState(state) }
        }
    }

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
        callerInitialText = findViewById(R.id.callerInitialText)
        answerButton = findViewById(R.id.answerButton)
        declineButton = findViewById(R.id.declineButton)
        hangUpButton = findViewById(R.id.hangUpButton)

        // Populate number from intent
        val number = intent.getStringExtra("number") ?: ""
        numberText.text = number.ifBlank { "Unknown caller" }
        callerInitialText.text = number.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#"
        durationText.text = ""

        activeCall = GsmInCallService.currentCall
        activeCall?.registerCallback(callCallback)
        updateUiForState(activeCall?.state ?: Call.STATE_NEW)

        answerButton.setOnClickListener {
            val call = activeCall ?: GsmInCallService.currentCall
            if (call != null) {
                call.answer(VideoProfile.STATE_AUDIO_ONLY)
                MainActivity.log("CallActivity: answered incoming call")
                statusText.text = "Answering..."
                durationText.text = ""
                answerButton.visibility = View.GONE
                declineButton.visibility = View.GONE
                hangUpButton.visibility = View.VISIBLE
            } else {
                MainActivity.log("CallActivity: answer ignored, no Call object")
            }
        }

        declineButton.setOnClickListener {
            declineOrDisconnect()
        }

        hangUpButton.setOnClickListener {
            declineOrDisconnect()
            finish()
        }

        MainActivity.log("CallActivity: showing call screen for $number")
    }

    fun onCallConnected() {
        handler.removeCallbacks(ticker)
        val wasConnected = isConnected
        isConnected = true
        statusText.text = "Connected"
        micLabel.text = if (AudioWebSocketHandler.useBrowserMicUplink) "MIC: BROWSER" else "MIC: PHONE"
        answerButton.visibility = View.GONE
        declineButton.visibility = View.GONE
        hangUpButton.visibility = View.VISIBLE
        if (!wasConnected) elapsedSeconds = 0
        handler.post(ticker)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(ticker)
        activeCall?.unregisterCallback(callCallback)
        if (instance === this) instance = null
    }

    private fun updateUiForState(state: Int) {
        when (state) {
            Call.STATE_RINGING -> {
                isConnected = false
                handler.removeCallbacks(ticker)
                statusText.text = "Incoming call"
                durationText.text = "Tap to answer"
                micLabel.text = "GSM Gateway"
                answerButton.visibility = View.VISIBLE
                declineButton.visibility = View.VISIBLE
                hangUpButton.visibility = View.GONE
            }
            Call.STATE_ACTIVE -> onCallConnected()
            Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT -> {
                isConnected = false
                handler.removeCallbacks(ticker)
                statusText.text = "Calling..."
                durationText.text = "Ringing"
                micLabel.text = "GSM Gateway"
                answerButton.visibility = View.GONE
                declineButton.visibility = View.GONE
                hangUpButton.visibility = View.VISIBLE
            }
            Call.STATE_DISCONNECTING -> {
                statusText.text = "Ending call"
                durationText.text = ""
                answerButton.visibility = View.GONE
                declineButton.visibility = View.GONE
                hangUpButton.visibility = View.VISIBLE
            }
            Call.STATE_DISCONNECTED -> finish()
            else -> {
                statusText.text = "Call"
                durationText.text = ""
                answerButton.visibility = View.GONE
                declineButton.visibility = View.GONE
                hangUpButton.visibility = View.VISIBLE
            }
        }
    }

    private fun declineOrDisconnect() {
        val call = activeCall ?: GsmInCallService.currentCall
        if (call != null) {
            if (call.state == Call.STATE_RINGING) {
                call.reject(false, null)
                MainActivity.log("CallActivity: declined incoming call")
            } else {
                call.disconnect()
                MainActivity.log("CallActivity: disconnected call")
            }
        } else {
            MainActivity.log("CallActivity: no Call object, sending hangup via backend")
        }
    }

    private fun formatDuration(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return "%02d:%02d".format(m, s)
    }
}
