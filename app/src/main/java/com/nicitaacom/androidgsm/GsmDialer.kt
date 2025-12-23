package com.nicitaacom.androidgsm

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.media.AudioManager
import android.telephony.PhoneStateListener
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.ActivityCompat

class GsmDialer(private val context: Context) {
    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var telephonyCallback: TelephonyCallback? = null
    private var onCallEnded: (() -> Unit)? = null
    private var onCallConnected: (() -> Unit)? = null

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
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                MainActivity.log("📞 Call state: OFFHOOK (active)")
                onCallConnected?.invoke()
            }
            TelephonyManager.CALL_STATE_RINGING -> MainActivity.log("📞 Call state: RINGING")
        }
    }

    fun setCallConnectedCallback(callback: () -> Unit) {
        onCallConnected = callback
    }

    fun setCallEndedCallback(callback: () -> Unit) {
        onCallEnded = callback
    }


    // 5. initiate GSM call
    // called when handling CALL_STARTED command (from backend via Pusher) in GsmService.handleCommand.
    @Suppress("unused", "MissingPermission")
    fun startCall(number: String) {
        try {
            MainActivity.log("GsmDialer: Initiating call to $number")

            if (!hasPermission(Manifest.permission.CALL_PHONE)) {
                MainActivity.log("ERROR: CALL_PHONE permission not granted")
                return
            }

            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val selectedSubId = prefs.getInt("selected_sim", -1)

            var handle: PhoneAccountHandle? = null

            if (selectedSubId != -1 && hasPermission(Manifest.permission.READ_PHONE_STATE)) {
                val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                val phoneAccounts = telecomManager.callCapablePhoneAccounts

                val subMgr = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
                val subInfo = subMgr.getActiveSubscriptionInfo(selectedSubId)

                if (subInfo != null) {
                    val targetAccount = phoneAccounts.find { account ->
                        account.id.contains(subInfo.simSlotIndex.toString()) || account.id.contains(selectedSubId.toString())
                    }

                    if (targetAccount != null) {
                        handle = targetAccount
                        MainActivity.log("Using PhoneAccountHandle for SIM slot ${subInfo.simSlotIndex + 1} (SubId: $selectedSubId)")
                    } else {
                        MainActivity.log("WARNING: Could not find PhoneAccount for SubId $selectedSubId - using default")
                    }
                } else {
                    MainActivity.log("Selected subId invalid or inactive - falling back to default SIM")
                }
            } else if (selectedSubId != -1) {
                MainActivity.log("READ_PHONE_STATE missing - cannot select specific SIM, using default")
            }

            // 6. verify SIM state (use default if no specific slot)
            val simState = telephonyManager?.simState ?: TelephonyManager.SIM_STATE_UNKNOWN

            if (simState != TelephonyManager.SIM_STATE_READY) {
                MainActivity.log("ERROR: SIM not ready (state: $simState)")
                return
            }

            // 7. launch dialer with ACTION_CALL
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$number")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            if (handle != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
            }

            context.startActivity(intent)
            MainActivity.log("GsmDialer: Call started to $number")
        } catch (exception: Exception) {
            MainActivity.log("ERROR starting call: ${exception.message}")
            Log.e("GsmDialer", "Failed to start call", exception)
        }
    }

    // 8. programmatic call termination with fallback
    fun endCall() {
        try {
            MainActivity.log("GsmDialer: Attempting to end call")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // 9. Android 9+ - use TelecomManager if permission granted
                if (hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)) {
                    try {
                        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                        @Suppress("MissingPermission") // we already checked permission above (in init)
                        val ended = telecomManager?.endCall() ?: false
                        MainActivity.log(if (ended) "✅ Call ended programmatically" else "⚠️ endCall() returned false")
                        return
                    } catch (securityException: SecurityException) {
                        MainActivity.log("ERROR: SecurityException calling endCall() - ${securityException.message}")
                    }
                } else {
                    MainActivity.log("⚠️ ANSWER_PHONE_CALLS permission not granted - using fallback")
                }
            }

            // 10. Fallback for Android <9 or missing permission - simulate headset hook
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK))
            }
            context.sendOrderedBroadcast(intent, null)
            MainActivity.log("⚠️ Call end requested via headset hook (fallback method)")
        } catch (error: Exception) {
            MainActivity.log("ERROR ending call: ${error.message}")
            Log.e("GsmDialer", "Failed to end call", error)
        }
    }

    fun sendDtmf(digit: Char) {
        try {
            val toneType = when (digit) {
                '0' -> ToneGenerator.TONE_DTMF_0
                '1' -> ToneGenerator.TONE_DTMF_1
                '2' -> ToneGenerator.TONE_DTMF_2
                '3' -> ToneGenerator.TONE_DTMF_3
                '4' -> ToneGenerator.TONE_DTMF_4
                '5' -> ToneGenerator.TONE_DTMF_5
                '6' -> ToneGenerator.TONE_DTMF_6
                '7' -> ToneGenerator.TONE_DTMF_7
                '8' -> ToneGenerator.TONE_DTMF_8
                '9' -> ToneGenerator.TONE_DTMF_9
                '*' -> ToneGenerator.TONE_DTMF_S
                '#' -> ToneGenerator.TONE_DTMF_P
                else -> return
            }

            // Prefer DTMF stream → VOICE_CALL → MUSIC
            val stream = AudioManager.STREAM_DTMF

            val toneGen = ToneGenerator(stream, 100) // max volume
            toneGen.startTone(toneType, 200) // 200ms tone
            MainActivity.log("DTMF sent locally: $digit (may not reach far end on all devices)")

            // Release after short delay to free resources
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                toneGen.release()
            }, 300)
        } catch (exception: Exception) {
            MainActivity.log("ERROR sending DTMF: ${exception.message}")
            Log.e("GsmDialer", "Failed to send DTMF", exception)
        }
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