package com.nicitaacom.androidgsm

import android.telecom.Call
import android.telecom.InCallService

// Bound by the system when our app is the default dialer.
// We do NOT provide a call UI — the call is observed/controlled from the browser via
// GsmService. The Call reference is exposed so GsmService.handleSendDtmf() can invoke
// Call.playDtmfTone() — the only reliable out-of-band DTMF path on Android.
class GsmInCallService : InCallService() {

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        currentCall = call
        MainActivity.log("GsmInCallService: Call attached — DTMF available")
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        if (currentCall === call) currentCall = null
        MainActivity.log("GsmInCallService: Call detached")
    }

    companion object {
        @Volatile var currentCall: Call? = null
            private set
    }
}
