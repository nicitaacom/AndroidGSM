package com.nicitaacom.androidgsm

import android.content.Intent
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService

// The one "is the loudspeaker on" check, shared by the service, the controller and the call screen.
internal val CallAudioState.isSpeakerRoute: Boolean get() = route == CallAudioState.ROUTE_SPEAKER

// Bound by the system when our app is the default dialer.
// Provides a real call screen (CallActivity) — required to prevent MIUI from showing
// "Mobile network not available" and to display number/duration/hang-up button.
class GsmInCallService : InCallService() {

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            MainActivity.log("GsmInCallService: call state → $state")
            when (state) {
                Call.STATE_ACTIVE -> {
                    // The answer. TelephonyManager's OFFHOOK is the dial of an outgoing call (and
                    // never fires on MIUI for some numbers, e.g. service numbers like 3311) -
                    // STATE_ACTIVE from the Telecom Call object is the one "remote answered" signal;
                    // CallController sends CALL_CONNECTED to the browser from it.
                    onCallActive?.invoke()
                    // ⛔ DISABLED — routeToBluetoothIfAvailable() ends the downlink to the browser.
                    // STATE_ACTIVE fires shortly AFTER OFFHOOK (already confirmed elsewhere in this
                    // file as lagging on this MIUI build). By the time it fires, onCallConnected()
                    // has already opened VOC_REC_DL and started tinycap against the EARPIECE route.
                    // Switching CallAudioState to BLUETOOTH moves the whole call onto the SCO/HFP
                    // link, off the on-chip codec's EARPIECE voice path that VOC_REC_DL taps — so
                    // tinycap keeps running but captures only silence from then on. Symptom: browser
                    // hears ~2-3s of real downlink audio (the OFFHOOK window) then silence, even
                    // though the call itself stays live. Hearing the remote party matters more than
                    // BT-uplink quality — the operator needs the downlink to run the call at all.
                    // Hardware mic (MIC: PHONE) already works fine as uplink without BT. Re-enable
                    // only after confirming VOC_REC_DL still captures over a live BT SCO route on
                    // real hardware.
                    // routeToBluetoothIfAvailable()
                }
                // "Call ended" cue — CallController owns both clips (see its callSounds): a
                // browser-driven call is muted at this point, so the controller plays the cue in
                // teardownCall() after unmuting; a manual call gets it right away. Telecom (the
                // system service, not the dialer) may still add its own endcall beep on top.
                Call.STATE_DISCONNECTED -> onCallDisconnected?.invoke()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
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

    // In-call screen toggles. Both go through Telecom so the system stays the source of truth and
    // onCallAudioStateChanged() redraws the buttons from the real state.
    private fun setMicMuted(muted: Boolean) {
        setMuted(muted)
        MainActivity.log("🎙️ Phone mic ${if (muted) "muted" else "unmuted"}")
    }

    private fun setLoudspeaker(on: Boolean) {
        val state = callAudioState
        val route = when {
            on -> CallAudioState.ROUTE_SPEAKER
            // Leaving the loudspeaker: a plugged-in wired headset (Telecom then offers no earpiece),
            // else the earpiece. Never BLUETOOTH here — see the disabled BT notes above.
            state != null && (state.supportedRouteMask and CallAudioState.ROUTE_WIRED_HEADSET) != 0 ->
                CallAudioState.ROUTE_WIRED_HEADSET
            else -> CallAudioState.ROUTE_EARPIECE
        }
        setAudioRoute(route)
        MainActivity.log("🔊 Call audio route → ${routeName(route)}")
    }

    // The system delivers the real audio state here whenever the route or mute flag changes.
    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        val previous = currentAudioState
        currentAudioState = audioState
        onAudioStateChanged?.invoke(audioState)
        // The route is reported from HERE, once it really changed, not from setLoudspeaker(): the HAL
        // re-applies its own EAR_S/SPK mixer paths while switching, so a quiet-state re-apply fired at
        // toggle time could land first and be undone by the switch. The first state of a call counts
        // too — it is the route the call rings on, and the HAL brings up that voice path right after
        // it, over any mute set before the dial. This also covers a route change made elsewhere
        // (wired headset plugged in mid-call).
        if (previous?.route != audioState.route) onAudioRouteChanged?.invoke()
        // ⛔ DISABLED — same reason as routeToBluetoothIfAvailable() above: forcing the route to
        // BLUETOOTH moves the call off the EARPIECE path that VOC_REC_DL/tinycap taps, so the
        // browser downlink goes to silence. Left inert (not deleted) so it is easy to compare
        // against once VOC_REC_DL-over-BT-SCO is confirmed working on real hardware.
        // if (audioState.route == CallAudioState.ROUTE_BLUETOOTH) return
        // val btAvailable = (audioState.supportedRouteMask and CallAudioState.ROUTE_BLUETOOTH) != 0
        // if (currentCall?.state == Call.STATE_ACTIVE && btAvailable) {
        //     setAudioRoute(CallAudioState.ROUTE_BLUETOOTH)
        //     MainActivity.log("🎧 BT headset available mid-call — routing call audio to it")
        // }
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
        // Drop the last call's mute/route so the next call's screen does not open with stale
        // toggles and its first audio state is reported as the fresh route it is.
        currentAudioState = null
        CallActivity.notifyEnded()
        // Authoritative, immediate "call ended" — tear audio down NOW instead of waiting for
        // MIUI's laggy PhoneStateListener IDLE (which left the downlink robot voice playing
        // ~10s after hang-up). See CallController.wireDialerCallbacks / teardownCall.
        onCallEnded?.invoke()
        MainActivity.log("GsmInCallService: Call detached")
    }

    companion object {
        @Volatile private var instance: GsmInCallService? = null
        @Volatile var currentCall: Call? = null
            private set
        // Latest CallAudioState from Telecom (mute flag + route) so the call screen can draw its
        // toggles from the real state, including a route change made elsewhere (headset plugged in).
        @Volatile var currentAudioState: CallAudioState? = null
            private set
        @Volatile var onAudioStateChanged: ((CallAudioState) -> Unit)? = null
        // Set by CallController.wireDialerCallbacks() — applies the gateway's phone-side audibility
        // for the route a browser-driven call is on: its first route (the ringing phase) and every
        // switch after it. The route is read back from currentAudioState, so no argument.
        @Volatile var onAudioRouteChanged: (() -> Unit)? = null
        // Set by CallController.wireDialerCallbacks() — invoked on STATE_ACTIVE as a
        // reliable fallback for when TelephonyManager misses OFFHOOK (MIUI quirk).
        @Volatile var onCallActive: (() -> Unit)? = null
        // Set by CallController.wireDialerCallbacks() — invoked on STATE_DISCONNECTED (before
        // onCallRemoved) so the controller can play the "call ended" cue at the right moment.
        @Volatile var onCallDisconnected: (() -> Unit)? = null
        // Invoked on onCallRemoved — the fast/reliable "call ended" signal (Telecom), used to
        // trigger immediate audio teardown instead of relying on the slow MIUI IDLE callback.
        @Volatile var onCallEnded: (() -> Unit)? = null

        val isMuted: Boolean get() = currentAudioState?.isMuted == true
        val isSpeakerOn: Boolean get() = currentAudioState?.isSpeakerRoute == true

        // false = InCallService not bound (no live call) — nothing to toggle.
        fun toggleMute(): Boolean {
            val service = instance ?: return false
            service.setMicMuted(!isMuted)
            return true
        }

        fun toggleSpeaker(): Boolean {
            val service = instance ?: return false
            service.setLoudspeaker(!isSpeakerOn)
            return true
        }
    }
}
