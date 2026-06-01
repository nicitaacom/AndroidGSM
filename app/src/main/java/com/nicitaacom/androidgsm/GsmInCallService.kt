package com.nicitaacom.androidgsm

import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.InCallService

// Bound by the system when our app is the default dialer.
// Provides a real call screen (CallActivity) — required to prevent MIUI from showing
// "Mobile network not available" and to display number/duration/hang-up button.
class GsmInCallService : InCallService() {

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            MainActivity.log("GsmInCallService: call state → $state")
            if (state == Call.STATE_ACTIVE) {
                // TelephonyManager.CALL_STATE_OFFHOOK is unreliable on MIUI for some numbers
                // (e.g. service numbers like 3311) — it never fires. STATE_ACTIVE from the
                // Telecom Call object is the authoritative "remote answered" signal.
                onCallActive?.invoke()
            }
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        currentCall = call
        call.registerCallback(callCallback)
        MainActivity.log("GsmInCallService: Call attached — DTMF available")

        // Launch call screen — system requires default dialer to show call UI
        val number = call.details?.handle?.schemeSpecificPart ?: ""
        val intent = Intent(this, CallActivity::class.java).apply {
            putExtra("number", number)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callCallback)
        if (currentCall === call) currentCall = null
        CallActivity.notifyEnded()
        MainActivity.log("GsmInCallService: Call detached")
    }

    companion object {
        @Volatile var currentCall: Call? = null
            private set
        // Set by CallController.wireDialerCallbacks() — invoked on STATE_ACTIVE as a
        // reliable fallback for when TelephonyManager misses OFFHOOK (MIUI quirk).
        @Volatile var onCallActive: (() -> Unit)? = null
    }
}
