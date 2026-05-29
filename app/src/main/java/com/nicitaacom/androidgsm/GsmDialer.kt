package com.nicitaacom.androidgsm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
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


    // Returns a list of call-capable SIM accounts: [{id, label, simSlotIndex}]
    @Suppress("MissingPermission")
    fun getSimAccounts(): List<Map<String, Any>> {
        if (!hasPermission(Manifest.permission.READ_PHONE_STATE)) return emptyList()
        return try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecomManager.callCapablePhoneAccounts.mapIndexedNotNull { idx, handle ->
                try {
                    val account = telecomManager.getPhoneAccount(handle)
                    val label = account?.label?.toString() ?: "SIM ${idx + 1}"
                    val simSlot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        account?.extras?.getInt("simSlotIndex", idx) ?: idx
                    } else idx
                    mapOf("id" to handle.id, "componentName" to handle.componentName.flattenToString(), "label" to label, "simSlotIndex" to simSlot)
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) {
            MainActivity.log("WARNING: Could not read SIM accounts: ${e.message}")
            emptyList()
        }
    }

    // 5. initiate GSM call
    // called when handling CALL_STARTED command (from backend via Pusher) in GsmService.handleCommand.
    // simAccountId: the PhoneAccount id string from getSimAccounts(); null = system default.
    @Suppress("unused", "MissingPermission")
    fun startCall(number: String, simAccountId: String? = null, simComponentName: String? = null): Boolean {
        try {
            MainActivity.log("GsmDialer: Initiating call to $number (simAccountId=$simAccountId)")

            if (!hasPermission(Manifest.permission.CALL_PHONE)) {
                MainActivity.log("ERROR: CALL_PHONE permission not granted")
                return false
            }

            // Check that at least one SIM slot is READY — simState without a subscriptionId
            // returns the default slot which may be ABSENT on dual-SIM phones where only slot 1 is active.
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val hasReadySim = telecomManager.callCapablePhoneAccounts.isNotEmpty()
            if (!hasReadySim) {
                log("ERROR: No call-capable SIM available")
                return false
            }

            // 1. wake up screen if locked
            try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "GsmDialer::CallWakeLock"
                )
                wakeLock.acquire(3000)
                MainActivity.log("Screen wake lock acquired for call")
            } catch (error: Exception) {
                MainActivity.log("WARNING: Could not acquire wake lock: ${error.message}")
            }

            val uri = Uri.parse("tel:$number")

            // TelephonyConnectionService.getPhoneForAccount on this MIUI build resolves Phone
            // objects by subId string, not ICCID. callCapablePhoneAccounts returns handles with
            // id=ICCID which don't match → chosenPhone=null → "Mobile network not available".
            // Build the handle with id=subId ("2") using the same TelephonyConnectionService component.
            // No PhoneAccountHandle — let MIUI resolve the SIM itself.
            // Passing any handle causes TelephonyConnectionService.getPhoneForAccount to fail
            // on this device (single active SIM, dual-SIM slot with slot 0 absent).
            android.util.Log.d("GsmDialer", "placeCall to $number, no handle")
            log("GsmDialer: placeCall to $number")
            telecomManager.placeCall(uri, Bundle())
            log("GsmDialer: placeCall() dispatched to GSM modem for $number")
            return true
        } catch (exception: Exception) {
            MainActivity.log("ERROR starting call: ${exception.message}")
            Log.e("GsmDialer", "Failed to start call", exception)
            return false
        }
    }
    // 8. programmatic call termination with fallback
    fun endCall() {
        try {
            MainActivity.log("GsmDialer: Attempting to end call")

            // Try multiple methods to ensure call is terminated
            var endCallSuccess = false

            // Method 1: Android 9+ TelecomManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)) {
                    try {
                        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                        @Suppress("MissingPermission")
                        endCallSuccess = telecomManager?.endCall() ?: false
                        if (endCallSuccess) {
                            MainActivity.log("✅ Call ended via TelecomManager")
                            return
                        }
                    } catch (e: Exception) {
                        MainActivity.log("⚠️ TelecomManager.endCall() failed: ${e.message}")
                    }
                } else {
                    MainActivity.log("⚠️ ANSWER_PHONE_CALLS permission not granted")
                }
            }

            // Method 2: Try with MANAGE_OWN_CALLS permission (Android 10+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    if (hasPermission("android.permission.MANAGE_OWN_CALLS")) {
                        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                        @Suppress("MissingPermission")
                        if (telecomManager?.endCall() == true) {
                            MainActivity.log("✅ Call ended via MANAGE_OWN_CALLS")
                            return
                        }
                    }
                } catch (e: Exception) {
                    MainActivity.log("⚠️ MANAGE_OWN_CALLS endCall failed: ${e.message}")
                }
            }

            MainActivity.log("⚠️ Call end requested (result unknown - multiple methods attempted)")
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
