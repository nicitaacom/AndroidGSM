package com.nicitaacom.androidgsm

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ActivityCompat

class GsmDialer(private val context: Context) {
    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var telephonyCallback: TelephonyCallback? = null
    private var onCallEnded: (() -> Unit)? = null

    init {
        telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

        // 1. check permission once
        if (hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) registerModernCallback()
            else registerLegacyCallback()
        } else MainActivity.log("⚠️ READ_PHONE_STATE permission missing - call state monitoring disabled")
    }

    // 2. modern API (Android 12+)
    private fun registerModernCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) = handleCallState(state)
        }
        telephonyManager?.registerTelephonyCallback(context.mainExecutor, telephonyCallback!!)
        MainActivity.log("✅ TelephonyCallback registered (API 31+)")
    }

    // 3. legacy API (Android 9-11)
    @Suppress("DEPRECATION")
    private fun registerLegacyCallback() {
        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) = handleCallState(state)
        }
        telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        MainActivity.log("✅ PhoneStateListener registered (API <31)")
    }

    // 4. unified state handler
    private fun handleCallState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_IDLE -> {
                MainActivity.log("📞 Call state: IDLE (call ended)")
                onCallEnded?.invoke()
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> MainActivity.log("📞 Call state: OFFHOOK (active)")
            TelephonyManager.CALL_STATE_RINGING -> MainActivity.log("📞 Call state: RINGING")
        }
    }

    fun setCallEndedCallback(callback: () -> Unit) {
        onCallEnded = callback
    }

    // 5. initiate GSM call
    fun startCall(number: String) {
        try {
            MainActivity.log("GsmDialer: Initiating call to $number")

            if (!hasPermission(Manifest.permission.CALL_PHONE)) {
                MainActivity.log("ERROR: CALL_PHONE permission not granted")
                return
            }

            // 6. verify SIM state
            val simState = telephonyManager?.simState
            if (simState != TelephonyManager.SIM_STATE_READY) {
                MainActivity.log("ERROR: SIM not ready (state: $simState)")
                return
            }

            // 7. launch dialer with ACTION_CALL
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$number")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            MainActivity.log("GsmDialer: Call started to $number")
        } catch (error: Exception) {
            MainActivity.log("ERROR starting call: ${error.message}")
            Log.e("GsmDialer", "Failed to start call", error)
        }
    }

    // 8. programmatic call termination (API 28+)
    fun endCall() {
        try {
            MainActivity.log("GsmDialer: Attempting to end call")

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                MainActivity.log("WARNING: endCall() requires Android 9+ (current: ${Build.VERSION.SDK_INT})")
                return
            }

            if (!hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)) {
                MainActivity.log("ERROR: ANSWER_PHONE_CALLS permission not granted")
                return
            }

            // 9. use TelecomManager to end active call
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            val ended = telecomManager?.endCall() ?: false
            MainActivity.log(if (ended) "✅ Call ended programmatically" else "⚠️ endCall() returned false")
        } catch (error: Exception) {
            MainActivity.log("ERROR ending call: ${error.message}")
            Log.e("GsmDialer", "Failed to end call", error)
        }
    }

    fun sendDtmf(digit: Char) {
        MainActivity.log("⚠️ DTMF not supported - requires InCallService (complex setup)")
        Log.d("GsmDialer", "DTMF requested: $digit (requires InCallService)")
    }

    // 10. cleanup listeners on destroy
    fun cleanup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let { callback -> telephonyManager?.unregisterTelephonyCallback(callback) }
        } else {
            @Suppress("DEPRECATION")
            phoneStateListener?.let { listener -> telephonyManager?.listen(listener, PhoneStateListener.LISTEN_NONE) }
        }
        telephonyCallback = null
        phoneStateListener = null
    }

    // 11. permission check helper
    private fun hasPermission(permission: String): Boolean {
        return ActivityCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}