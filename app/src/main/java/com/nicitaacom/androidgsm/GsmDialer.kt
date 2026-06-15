package com.nicitaacom.androidgsm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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


class GsmDialer(private val context: Context, private val log: GsmLogger = { MainActivity.log(it) }) {
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
        } else log("⚠️ READ_PHONE_STATE permission missing - call state monitoring disabled")
    }

    // 2. modern API (Android 12+)
    private fun registerModernCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) = handleCallState(state)
        }
        telephonyManager?.registerTelephonyCallback(context.mainExecutor, telephonyCallback!!)
        log("✅ TelephonyCallback registered (API 31+)")
    }

    // 3. legacy API (Android 9-11)
    @Suppress("DEPRECATION")
    private fun registerLegacyCallback() {
        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) = handleCallState(state)
        }
        telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        log("✅ PhoneStateListener registered (API <31)")
    }

    // 4. unified state handler
    private fun handleCallState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_IDLE -> {
                log("📞 Call state: IDLE (call ended)")
                onCallEnded?.invoke()
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                log("📞 Call state: OFFHOOK (active)")
                onCallConnected?.invoke()
            }
            TelephonyManager.CALL_STATE_RINGING -> log("📞 Call state: RINGING")
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
            log("WARNING: Could not read SIM accounts: ${e.message}")
            emptyList()
        }
    }

    // 5. initiate GSM call
    // called when handling CALL_STARTED command (from backend via /ws/cmd) in GsmService.handleCommand.
    // simAccountId: the PhoneAccount id string from getSimAccounts(); null = system default.
    @Suppress("unused", "MissingPermission")
    fun startCall(number: String, simAccountId: String? = null, simComponentName: String? = null): Boolean {
        try {
            log("GsmDialer: Initiating call to $number (simAccountId=$simAccountId)")

            if (!hasPermission(Manifest.permission.CALL_PHONE)) {
                log("ERROR: CALL_PHONE permission not granted")
                return false
            }

            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (telecomManager.callCapablePhoneAccounts.isEmpty()) {
                log("ERROR: No call-capable SIM accounts found")
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
                log("Screen wake lock acquired for call")
            } catch (error: Exception) {
                log("WARNING: Could not acquire wake lock: ${error.message}")
            }
            val uri = Uri.parse("tel:$number")

            // On dual-SIM MIUI, an empty Bundle causes SELECT_PHONE_ACCOUNT → CANCELED (the
            // system waits for a SIM picker that never gets answered, so the call never connects).
            // Fix: embed the handle via EXTRA_PHONE_ACCOUNT_HANDLE inside the Bundle — different
            // from placeCall's 3-arg form with a handle (which causes OUT_OF_SERVICE on sdm660).
            // Priority: use the handle matching simAccountId if provided, else the user's default
            // outgoing account, else fall back to the first available account.
            val extras = Bundle()
            val handle = resolvePhoneAccountHandle(telecomManager, simAccountId, simComponentName)
            if (handle != null) {
                extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                log("GsmDialer: using PhoneAccount handle ${handle.id}")
            } else {
                log("GsmDialer: no handle resolved, placing call without account (MIUI fallback)")
            }
            telecomManager.placeCall(uri, extras)
            log("GsmDialer: placeCall() dispatched to GSM modem for $number")
            return true
        } catch (exception: Exception) {
            log("ERROR starting call: ${exception.message}")
            Log.e("GsmDialer", "Failed to start call", exception)
            return false
        }
    }
    @Suppress("MissingPermission")
    private fun resolvePhoneAccountHandle(
        telecomManager: TelecomManager,
        simAccountId: String?,
        simComponentName: String?
    ): android.telecom.PhoneAccountHandle? {
        val accounts = telecomManager.callCapablePhoneAccounts
        if (!simAccountId.isNullOrBlank() && !simComponentName.isNullOrBlank()) {
            val match = accounts.firstOrNull { h ->
                h.id == simAccountId && h.componentName.flattenToString() == simComponentName
            }
            if (match != null) return match
        }
        telecomManager.getDefaultOutgoingPhoneAccount("tel")?.let { return it }
        return accounts.firstOrNull()
    }

    fun endCall() {
        // Primary path: disconnect the actual Call object held by GsmInCallService — same path
        // as the on-screen hang-up button. TelecomManager.endCall() is unreliable on this MIUI
        // build while the call is still DIALING/CONNECTING (returns false / no-ops), which left
        // the phone calling forever after a website hang-up. Call.disconnect() works in any state.
        val call = GsmInCallService.currentCall
        if (call != null) {
            try {
                if (call.state == android.telecom.Call.STATE_RINGING) {
                    call.reject(false, null)
                    log("✅ Incoming call rejected via Call object")
                } else {
                    call.disconnect()
                    log("✅ Call disconnected via Call object (state=${call.state})")
                }
                return
            } catch (e: Exception) {
                log("⚠️ Call.disconnect() failed, falling back to TelecomManager: ${e.message}")
            }
        } else {
            log("⚠️ No Call object — falling back to TelecomManager.endCall()")
        }
        try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            @Suppress("MissingPermission")
            val ended = telecomManager?.endCall() ?: false
            log(if (ended) "✅ Call ended via TelecomManager" else "⚠️ TelecomManager.endCall() returned false")
        } catch (e: Exception) {
            log("ERROR ending call: ${e.message}")
            Log.e("GsmDialer", "Failed to end call", e)
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
