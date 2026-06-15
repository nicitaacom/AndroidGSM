package com.nicitaacom.androidgsm

import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
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
                // Route call audio to the Bluetooth headset so the HEADSET MIC becomes the
                // call uplink (firmware-blessed HFP path). Without this the route stays on
                // EARPIECE and the remote hears the faint phone earpiece mic instead of the
                // headphone. See memory [[bt-hfp-uplink-works]].
                routeToBluetoothIfAvailable()
            }
        }
    }

    // Switch the active call audio route to a connected Bluetooth headset.
    // Safe no-op if no BT device is connected (falls back to whatever route is active).
    private fun routeToBluetoothIfAvailable() {
        try {
            val audioState = callAudioState ?: run {
                MainActivity.log("🎧 No callAudioState yet — can't route to BT")
                return
            }
            val btAvailable = (audioState.supportedRouteMask and CallAudioState.ROUTE_BLUETOOTH) != 0
            if (!btAvailable) {
                MainActivity.log("🎧 No Bluetooth headset available — call stays on ${routeName(audioState.route)}")
                return
            }
            if (audioState.route == CallAudioState.ROUTE_BLUETOOTH) {
                MainActivity.log("🎧 Already routed to Bluetooth headset")
                return
            }
            setAudioRoute(CallAudioState.ROUTE_BLUETOOTH)
            MainActivity.log("🎧 Routed call audio to Bluetooth headset (headphone mic = uplink)")
        } catch (e: Exception) {
            MainActivity.log("❌ Failed to route call audio to Bluetooth: ${e.message}")
        }
    }

    private fun routeName(route: Int): String = when (route) {
        CallAudioState.ROUTE_EARPIECE -> "EARPIECE"
        CallAudioState.ROUTE_SPEAKER -> "SPEAKER"
        CallAudioState.ROUTE_BLUETOOTH -> "BLUETOOTH"
        CallAudioState.ROUTE_WIRED_HEADSET -> "WIRED_HEADSET"
        else -> "route=$route"
    }

    // The system delivers the real audio state here whenever BT connects/disconnects mid-call.
    // Re-assert the Bluetooth route once so a headset that connects after the call started
    // still captures the uplink. Guard: only act when route isn't already BT, to avoid a
    // feedback loop (setAudioRoute itself triggers another onCallAudioStateChanged).
    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        if (audioState.route == CallAudioState.ROUTE_BLUETOOTH) return
        val btAvailable = (audioState.supportedRouteMask and CallAudioState.ROUTE_BLUETOOTH) != 0
        if (currentCall?.state == Call.STATE_ACTIVE && btAvailable) {
            setAudioRoute(CallAudioState.ROUTE_BLUETOOTH)
            MainActivity.log("🎧 BT headset available mid-call — routing call audio to it")
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
        // Authoritative, immediate "call ended" — tear audio down NOW instead of waiting for
        // MIUI's laggy PhoneStateListener IDLE (which left the downlink robot voice playing
        // ~10s after hang-up). See CallController.wireDialerCallbacks / teardownCall.
        onCallEnded?.invoke()
        MainActivity.log("GsmInCallService: Call detached")
    }

    companion object {
        @Volatile var currentCall: Call? = null
            private set
        // Set by CallController.wireDialerCallbacks() — invoked on STATE_ACTIVE as a
        // reliable fallback for when TelephonyManager misses OFFHOOK (MIUI quirk).
        @Volatile var onCallActive: (() -> Unit)? = null
        // Invoked on onCallRemoved — the fast/reliable "call ended" signal (Telecom), used to
        // trigger immediate audio teardown instead of relying on the slow MIUI IDLE callback.
        @Volatile var onCallEnded: (() -> Unit)? = null
    }
}
