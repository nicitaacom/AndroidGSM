package com.nicitaacom.androidgsm

import android.content.Context
import android.content.Intent
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.telecom.VideoProfile

// The one "is the loudspeaker on" check, shared by the service, the controller and the call screen.
internal val CallAudioState.isSpeakerRoute: Boolean get() = route == CallAudioState.ROUTE_SPEAKER

// Bound by the system when our app is the default dialer.
// Provides a real call screen (CallActivity) — required to prevent MIUI from showing
// "Mobile network not available" and to display number/duration/hang-up button.
class GsmInCallService : InCallService() {

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            MainActivity.log("GsmInCallService: call state → $state")
            // an ignored ring that got answered on the phone after all is a call again
            if (state != Call.STATE_RINGING && ignoredCall === call) ignoredCall = null
            // before the callbacks below: the VPS holds the new state by the time CALL_CONNECTED reaches the laptop
            onCallStateChanged?.invoke()
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
        val number = call.details?.handle?.schemeSpecificPart ?: ""
        val isRinging = call.state == Call.STATE_RINGING
        lastCallAddedAtMs = System.currentTimeMillis()
        // a call that rings while another is on (call waiting) stays apart from the active one: its end must not
        // tear the active call's audio down (plan-05 task 7, edge case 5)
        if (isRinging && currentCall != null && currentCall !== call) ringingCall = call else currentCall = call
        // the laptop's id of this dial (CALL_STARTED's dialId) belongs to the outgoing call Telecom adds next
        if (!isRinging) nextOutgoingDialId?.let { dialId ->
            dialIdByCall[call] = dialId
            nextOutgoingDialId = null
        }
        call.registerCallback(callCallback)
        onCallStateChanged?.invoke()
        // the laptop hung up before Telecom added this dial - it ends here, before it rings anyone (onCallRemoved then
        // tears it down and sends CALL_ENDED like any other end)
        if (!isRinging && System.currentTimeMillis() < endOutgoingCallUntilMs) {
            endOutgoingCallUntilMs = 0L
            call.disconnect()
            MainActivity.log("📴 The laptop hung up before this dial was placed - disconnected at once")
            return
        }
        MainActivity.log("GsmInCallService: Call attached — DTMF available")
        // the laptop shows "Incoming · <number>" with [Ignore] [Answer] (plan-05 task 7)
        if (isRinging) onIncomingCall?.invoke(number)

        // Launch call screen — system requires default dialer to show call UI
        val intent = Intent(this, CallActivity::class.java).apply {
            putExtra("number", number)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callCallback)
        // the id of the call that ended, not of whichever call is current by now - CALL_ENDED names it for the laptop
        val endedDialId = dialIdByCall.remove(call)
        if (ignoredCall === call) ignoredCall = null
        // the waiting call went away (she gave up, or the network's ring timeout) - the active call is untouched
        if (ringingCall === call) {
            ringingCall = null
            onCallStateChanged?.invoke()
            onIncomingCallEnded?.invoke()
            MainActivity.log("GsmInCallService: the waiting call detached")
            return
        }
        // the active call ended while another one rang next to it: that one is the phone's only call now, so its answer,
        // its end and its call log row take the same path as a plain incoming call's
        if (currentCall === call) {
            currentCall = ringingCall
            ringingCall = null
        }
        // Drop the last call's mute/route so the next call's screen does not open with stale
        // toggles and its first audio state is reported as the fresh route it is.
        currentAudioState = null
        CallActivity.notifyEnded()
        // before CALL_ENDED below: the VPS holds "idle" by the time the audio socket drops
        onCallStateChanged?.invoke()
        // Authoritative, immediate "call ended" — tear audio down NOW instead of waiting for
        // MIUI's laggy PhoneStateListener IDLE (which left the downlink robot voice playing
        // ~10s after hang-up). See CallController.wireDialerCallbacks / teardownCall.
        onCallEnded?.invoke(endedDialId)
        MainActivity.log("GsmInCallService: Call detached")
    }

    companion object {
        @Volatile private var instance: GsmInCallService? = null
        @Volatile var currentCall: Call? = null
            private set
        // the call ringing next to an active one (call waiting) - null at idle, where the ringing call IS currentCall
        @Volatile private var ringingCall: Call? = null
        // Set by CallController.wireDialerCallbacks() - an incoming call rings (its number, "" when hidden), and the
        // waiting call went away; the laptop's card follows both (plan-05 task 7)
        @Volatile var onIncomingCall: ((String) -> Unit)? = null
        @Volatile var onIncomingCallEnded: (() -> Unit)? = null
        // when Telecom added the newest call - CallController.reportCall reads the call log row from then on
        @Volatile var lastCallAddedAtMs = 0L
        // ms until which the next outgoing call gets disconnected the moment Telecom adds it - set by a laptop hang-up
        // that came while this phone's own dial was still being placed (CallController.handleCallEnded)
        @Volatile private var endOutgoingCallUntilMs = 0L
        fun endNextOutgoingCall(withinMs: Long) {
            endOutgoingCallUntilMs = System.currentTimeMillis() + withinMs
        }

        // The laptop's own id of each call it dialed or answered (CALL_STARTED / ANSWER_CALL dialId). CALL_CONNECTED and
        // CALL_ENDED send it back, so a late end of the call before never ends the laptop's card of the next dial
        // (outreach-tool plan-09 task 8). A call made on the phone itself has none
        private val dialIdByCall = java.util.concurrent.ConcurrentHashMap<Call, String>()
        @Volatile private var nextOutgoingDialId: String? = null
        // CallController.handleCallStarted, right before placeCall - null for a laptop that sends no dialId yet
        fun setNextOutgoingDialId(dialId: String?) {
            nextOutgoingDialId = dialId
        }
        val currentDialId: String? get() = currentCall?.let { dialIdByCall[it] }
        // ANSWER_CALL - the ringing call becomes the laptop's call
        fun setRingingDialId(dialId: String?) {
            val call = ringingNow ?: return
            if (dialId.isNullOrBlank()) dialIdByCall.remove(call) else dialIdByCall[call] = dialId
        }

        // Set by CallController.wireDialerCallbacks() - a call was added, changed its state or went away. The phone's
        // own state goes to the VPS at once (CALL_STATE) and with every heartbeat, the laptop's status poll reads it
        @Volatile var onCallStateChanged: (() -> Unit)? = null

        private fun numberOf(call: Call): String = call.details?.handle?.schemeSpecificPart ?: ""

        // the ring [Ignore] silenced - for Telecom it still rings (the caller keeps hearing it), for the laptop's card
        // it is over: callStateMap leaves it out, or the card would show it again from the status poll
        @Volatile private var ignoredCall: Call? = null

        // "idle" | "dialing" | "active" | "ringing" + the numbers - what the laptop's card has to show right now
        fun callStateMap(): Map<String, Any> {
            val call = currentCall?.takeIf { it !== ignoredCall }
            val ringing = ringingNow?.takeIf { it !== ignoredCall }
            val callState = when (call?.state) {
                null, Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> "idle"
                Call.STATE_RINGING -> "ringing"
                Call.STATE_ACTIVE, Call.STATE_HOLDING -> "active"
                else -> "dialing"
            }
            return mapOf(
                "callState" to callState,
                "callNumber" to (if (call == null || callState == "idle") "" else numberOf(call)),
                "callDialId" to (currentDialId ?: ""),
                "isRinging" to (ringing != null),
                "ringingNumber" to (ringing?.let { numberOf(it) } ?: "")
            )
        }

        private val ringingNow: Call? get() = (ringingCall ?: currentCall)?.takeIf { it.state == Call.STATE_RINGING }
        val hasRingingCall: Boolean get() = ringingNow != null
        // ANSWER_CALL from the laptop - false = nothing rings any more
        fun answerRingingCall(): Boolean = ringingNow?.let { it.answer(VideoProfile.STATE_AUDIO_ONLY); true } ?: false
        // [Ignore] on the laptop (IGNORE_CALL) or on CallActivity - the ringtone stops here, the call rings on for the
        // caller until she gives up or the network ends it (a reject gives her "busy" at once). For him the ring is
        // over: a lone ring's screen closes and the laptop's card hides. silenceRinger is open to the default dialer,
        // this app
        fun ignoreRingingCall(context: Context): Boolean {
            val call = ringingNow ?: return false
            try {
                context.getSystemService(TelecomManager::class.java).silenceRinger()
            } catch (error: SecurityException) {
                MainActivity.log("❌ Ignore: silenceRinger refused - is AndroidGSM still the default dialer? $error")
                return false
            }
            // a waiting call next to an active one leaves the active call's screen open
            if (call === currentCall) CallActivity.notifyEnded()
            ignoredCall = call
            onCallStateChanged?.invoke()
            onIncomingCallEnded?.invoke()
            return true
        }
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
        // dialId: the laptop's id of the call that ended, null for a call it did not start
        @Volatile var onCallEnded: ((dialId: String?) -> Unit)? = null

        val isMuted: Boolean get() = currentAudioState?.isMuted == true

        // SET_MIC_MUTED from the laptop's [Mute] - the phone's own mic / the headset's mic, the call's one uplink.
        // false = no live call
        fun updateMicMuted(muted: Boolean): Boolean {
            val service = instance ?: return false
            service.setMicMuted(muted)
            return true
        }
        val isSpeakerOn: Boolean get() = currentAudioState?.isSpeakerRoute == true

        fun routeName(route: Int): String = when (route) {
            CallAudioState.ROUTE_EARPIECE -> "EARPIECE"
            CallAudioState.ROUTE_SPEAKER -> "SPEAKER"
            CallAudioState.ROUTE_BLUETOOTH -> "BLUETOOTH"
            CallAudioState.ROUTE_WIRED_HEADSET -> "WIRED_HEADSET"
            else -> "route=$route"
        }

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
