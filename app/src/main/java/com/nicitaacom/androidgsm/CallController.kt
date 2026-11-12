package com.nicitaacom.androidgsm

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

// Mutable holder so CallController can create/null the handler across threads without
// passing it back to GsmService on every call.
class AudioWsHandlerRef {
    var handler: AudioWebSocketHandler? = null
}

/**
 * Owns all call lifecycle logic extracted from GsmService:
 *  - isCallActive flag
 *  - setCallConnectedCallback body (audio setup on OFFHOOK)
 *  - teardownCall()
 *  - handleCallStarted()
 *  - handleCallEnded()
 *  - handleSendDtmf()
 *
 * @param context          Application/service context for AudioManager.
 * @param config           App configuration (backend URL, tokens).
 * @param cmdWsClient      Lambda returning the current CommandWebSocketClient (may be null).
 * @param audioWsHandlerRef Mutable holder for the AudioWebSocketHandler instance.
 * @param onCallActiveChanged Notified whenever isCallActive changes so GsmService can gate
 *                           SET_MIC_SOURCE injection toggling.
 * @param log              GsmLogger — never call MainActivity.log directly inside this class.
 */
class CallController(
    private val context: Context,
    private val config: Config,
    private val cmdWsClient: () -> CommandWebSocketClient?,
    private val audioWsHandlerRef: AudioWsHandlerRef,
    private val onCallActiveChanged: (Boolean) -> Unit,
    private val onMicSourceReset: () -> Unit,
    private val log: GsmLogger
) {
    @Volatile var isCallActive: Boolean = false
        private set

    // Guards the call audio setup so it happens once per call, whichever signal comes first:
    // TelephonyManager OFFHOOK (the dial itself for an outgoing call - Android reports OFFHOOK the
    // moment a call is dialing, not when it is answered; never at all for MIUI service numbers like
    // 3311) or Telecom Call.STATE_ACTIVE (the answer). Reset at the start of each call + on teardown.
    private val callConnectedHandled = AtomicBoolean(false)

    // CALL_CONNECTED to the browser goes out on STATE_ACTIVE only - it is the website's "answered"
    // (its call timer, its mic, the music panel's "keep music on answer at", the end of its ringing
    // gain). Sent on OFFHOOK it lit "answered" at the dial, so the website's ringing phase was 0 s
    // long (2026-11-02: "ringing while music plays does nothing", the music paused at the dial).
    private val callAnsweredSent = AtomicBoolean(false)

    // Browser-driven calls (CALL_STARTED over /ws/cmd) keep the phone itself quiet — the operator
    // listens in the browser (see applyPhoneAudibility). A call dialed or answered on the phone
    // stays audible like in any dialer. Set in handleCallStarted, cleared in teardownCall.
    @Volatile private var browserDrivenCall = false

    // The operator's voice-call volume at dial time, handed back after the call: a browser-driven
    // call drives that volume itself (the floor while the phone is quiet, max around a cue).
    // -1 = nothing captured (manual call).
    @Volatile private var voiceVolumeBeforeCall = -1

    // The same for a Bluetooth headset: its call volume is a stream of its own (STREAM_BLUETOOTH_SCO), captured the
    // first time the website's "Output vol" sets it. -1 = this call never touched it.
    @Volatile private var scoVolumeBeforeCall = -1

    // ANSWER_CALL came while a call was on and another one rang next to it (outreach-tool plan-05 task 19): the laptop
    // hangs the active call up first, and the ringing one is answered once Telecom removed the active one
    @Volatile private var isAnswerAfterEnd = false
    // that ANSWER_CALL's dialId - the waiting call gets it once it is answered
    @Volatile private var answerAfterEndDialId: String? = null

    // ms of the laptop's last CALL_STARTED - callStateMap counts the dial as on until Telecom has added its call
    @Volatile private var browserCallStartedAtMs = 0L

    // "bluetooth" | "codec" | "none" | "" - where the website's "Mic vol" stands on this call: a Bluetooth headset's
    // mic has no volume on the phone, the phone's own mic / a wired mic took it ("codec"), no mixer control took it
    // ("none"), nothing was asked yet (""). Goes out with the call state, the website's gear menu shows it
    @Volatile private var micVolStatus = ""

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val maxVoiceVolume: Int
        get() = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)

    // Start / end cues (res/raw/start_call.mp3, end_call.mp3). Owned here, not by GsmInCallService,
    // because a browser-driven call has EAR_S/SPK muted + voice volume 0 from dial time and only
    // this class may lift that around a clip — a clip started into the mute is just a click.
    private val callSounds = CallSounds(context, log)

    // plan-06 task 2: records every answered call while "record every call" is on
    private val callRecorder = CallRecorder(context, audioWsHandlerRef, log, isCallOn = { isCallActive })

    // every answered call's 📶 lead side + 🌐 phone internet lines for the website's Logs box
    private val dialQualityLog = DialQualityLog(context, cmdWsClient, audioWsHandlerRef, log)

    // the SIM the laptop dialed with - DialQualityLog reads its signal; null for a call that rang in
    @Volatile private var dialSubscriptionId: Int? = null

    // Called from GsmService.onCreate to wire up the dialer + InCallService callbacks.
    fun wireDialerCallbacks(gsmDialer: GsmDialer) {
        // Single call-ended callback set once. Owns all teardown.
        gsmDialer.setCallEndedCallback {
            if (!isCallActive && audioWsHandlerRef.handler == null) return@setCallEndedCallback
            teardownCall()
        }

        // Both signals route into onCallConnected(), which self-guards against a second pass. OFFHOOK
        // comes at the dial of an outgoing call (and never for some MIUI numbers), STATE_ACTIVE at the
        // answer: the audio setup takes the first of the two, CALL_CONNECTED waits for the answer.
        gsmDialer.setCallConnectedCallback { onCallConnected(SOURCE_OFFHOOK) }
        GsmInCallService.onCallActive = { onCallConnected(SOURCE_ANSWERED) }
        GsmInCallService.onAudioRouteChanged = { onPhoneRouteChanged() }
        GsmInCallService.onCallDisconnected = { onCallDisconnected() }
        GsmInCallService.onIncomingCall = { number -> onIncomingCall(number) }
        GsmInCallService.onIncomingCallEnded = { onIncomingCallEnded() }
        // every Telecom change at once - the VPS keeps it, the laptop's status poll reads it (plan-09 task 8)
        GsmInCallService.onCallStateChanged = { cmdWsClient()?.sendEvent("CALL_STATE", callStateMap()) }

        // Authoritative "call ended" signal. Telecom's onCallRemoved fires the INSTANT the call
        // ends — far faster/more reliable than MIUI's PhoneStateListener IDLE (which can lag ~10s
        // on this build, leaving downlink audio playing after hang-up). teardownCall() self-guards
        // against double-run, so the later IDLE event is a harmless no-op.
        // A ring nobody answered (she gave up, the ring timed out after [Ignore]) set up no audio, so teardownCall's
        // CALL_ENDED never comes for it - INCOMING_CALL_ENDED is the laptop's one sign that nothing rings any more.
        GsmInCallService.onCallEnded = { endedDialId ->
            if (isCallActive || audioWsHandlerRef.handler != null) {
                log("📴 Call removed (Telecom) — tearing down audio immediately")
                teardownCall(endedDialId)
            } else onIncomingCallEnded()
            reportCall()
            if (isAnswerAfterEnd) {
                isAnswerAfterEnd = false
                val dialId = answerAfterEndDialId
                answerAfterEndDialId = null
                handleAnswerCall(dialIdData(dialId))
            }
        }
    }

    // the phone's own call state for the heartbeat and CALL_STATE: Telecom's state + what is paired to the phone (the
    // call's output and mic) + where "Mic vol" stands. The laptop's dial counts as "dialing" from CALL_STARTED on -
    // Telecom adds its call up to DIAL_PLACE_WAIT_MS later, and "idle" in that gap would read as "the dial is gone"
    fun callStateMap(): Map<String, Any> {
        val telecomState = GsmInCallService.callStateMap()
        val isDialBeingPlaced = telecomState["callState"] == "idle" && isCallActive && browserDrivenCall &&
            System.currentTimeMillis() - browserCallStartedAtMs < DIAL_PLACE_WAIT_MS
        return telecomState +
            (if (isDialBeingPlaced) mapOf("callState" to "dialing") else emptyMap()) +
            mapOf("headset" to headsetName(), "micVol" to micVolStatus)
    }

    private fun dialIdData(dialId: String?): Map<String, Any> =
        if (dialId.isNullOrBlank()) emptyMap() else mapOf("dialId" to dialId)

    // the row of the call that just ended, from the phone's own call log, to the laptop's "Recent Calls" (plan-05
    // task 8) - every call, a manual one too. Android inserts the row a moment after onCallRemoved, so this polls up
    // to 3 s for a row started at or after Telecom added the call
    private fun reportCall() {
        val sinceMs = GsmInCallService.lastCallAddedAtMs - CALL_LOG_START_MARGIN_MS
        Thread {
            try {
                val deadline = System.currentTimeMillis() + CALL_LOG_WAIT_MS
                var row: CallLogRow? = null
                while (row == null && System.currentTimeMillis() < deadline) {
                    row = CallLogReader.select(context, 1, sinceMs).firstOrNull()
                    if (row == null) Thread.sleep(300)
                }
                if (row == null) {
                    log("📒 CALL_REPORT skipped: no call log row within ${CALL_LOG_WAIT_MS / 1000} s")
                    return@Thread
                }
                val hasRecording = callRecorder.finish(row.id)
                cmdWsClient()?.sendEvent("CALL_REPORT", row.toMap() + ("hasRecording" to hasRecording))
                log("📒 CALL_REPORT ${row.type} ${row.number.ifBlank { "hidden number" }} ${row.talkSec}s")
            } catch (error: SecurityException) {
                cmdWsClient()?.sendEvent("CALL_REPORT", mapOf("error" to "Phone has no call log access"))
                log("📒 CALL_REPORT: no READ_CALL_LOG - ${error.message}")
            } catch (error: Exception) {
                log("📒 CALL_REPORT error: ${error.message}")
            }
        }.start()
    }

    // Sets up call audio exactly once per call, triggered by whichever signal fires first (OFFHOOK at
    // the dial, or STATE_ACTIVE at the answer). A later signal only gets to send CALL_CONNECTED, the
    // answer. handleCallStarted must NOT re-wire this — doing setup per-call caused duplicate teardown
    // paths and triple CALL_ENDED events.
    private fun onCallConnected(source: String) {
        // the answer: the generated ringback stops here and only here - OFFHOOK is the dial, and while the
        // generated tone plays the downlink chunks (the network's own ringback) are held back, so the
        // website hears one "tyyy" at one volume (plan-05 task 13)
        if (source == SOURCE_ANSWERED) audioWsHandlerRef.handler?.stopRingback()
        if (!callConnectedHandled.compareAndSet(false, true)) {
            log("📞 $source — audio already set up this call")
            sendCallConnectedIfAnswered(source)
            return
        }
        setCallActive(true)
        log("📞 $source — setting up the call audio")
        val am = audioManager
        am.mode = AudioManager.MODE_IN_CALL
        // Do NOT force isSpeakerphoneOn=true here — it overrides the HAL's USB-C mic routing.
        // tinycap reads ALSA card 0 device 0 directly and doesn't need speakerphone at Java level.

        Thread {
            try {

                // "Call started" cue on the phone. A browser-driven call has been quiet since
                // handleCallStarted — make the phone audible (max voice volume + mixer), let the
                // whole clip play out, then mute the mixer again so the phone on the desk stays
                // quiet (applyPhoneAudibility() settles volume + mixer below). A manual call keeps
                // whatever volume the operator set. Blocking on the clip's own completion, not a
                // fixed sleep: in-call playback starts late on this HAL and flipping the mixer
                // mid-clip chopped the cue to a click.
                if (browserDrivenCall) liftQuietState()
                callSounds.playBlocking(R.raw.start_call)
                if (browserDrivenCall) RootUtils.mutePhoneSpeaker()

                val captureEnabled = RootUtils.enableIncallMusicCapture()
                log("📞 Incall capture path: $captureEnabled")
                Thread.sleep(300)

                val wsUrl = audioWsUrl()

                // The audio WS was already opened in handleCallStarted (for ringback). Reuse it
                // if still connected — avoids a reconnect gap. Only create fresh if missing/dead.
                if (audioWsHandlerRef.handler?.isWsConnected != true) {
                    audioWsHandlerRef.handler?.disconnect()
                    audioWsHandlerRef.handler = AudioWebSocketHandler(context, config, log = log) { }
                    audioWsHandlerRef.handler?.connect(wsUrl, config.BACKEND_BEARER ?: "", config.DEVICE_TOKEN ?: "")
                }

                // Wait for WS to actually open before starting capture — blind sleep(300) was
                // not enough on slow networks and caused all tinycap chunks to be silently dropped.
                val wsDeadline = System.currentTimeMillis() + 5000
                while (audioWsHandlerRef.handler?.isWsConnected != true && System.currentTimeMillis() < wsDeadline) {
                    Thread.sleep(100)
                }
                if (audioWsHandlerRef.handler?.isWsConnected != true) {
                    log("❌ Audio WS failed to connect after 5s — call audio will not work")
                } else {
                    log("✅ Audio WS connected")
                }

                audioWsHandlerRef.handler?.setCallActive(true)
                audioWsHandlerRef.handler?.startAudioCapture()
                applyPhoneAudibility()
                applyAudioToBrowser()
                applyMicGain()
                // Uplink injection via Incall_Music mixer is NOT used — MIUI CAF kernel
                // blocks slot 1 (VoiceMMode2) ELEM_WRITE silently. Instead, startAudioPlayback()
                // writes browser mic PCM directly to /dev/snd/pcmC0D19p (VoiceMMode2 TX PCM device).
                audioWsHandlerRef.handler?.startAudioPlayback()
                sendCallConnectedIfAnswered(source)
            } catch (error: Exception) {
                log("ERROR in CALL_CONNECTED callback: ${error.message}")
            }
        }.start()
    }

    // The browser's "answered" - once per call, and only from Telecom's STATE_ACTIVE: OFFHOOK is the dial.
    private fun sendCallConnectedIfAnswered(source: String) {
        if (source != SOURCE_ANSWERED) return
        if (!callAnsweredSent.compareAndSet(false, true)) return
        cmdWsClient()?.sendEvent("CALL_CONNECTED", dialIdData(GsmInCallService.currentDialId))
        log("📞 CALL_CONNECTED sent to backend ($source)")
        callRecorder.start()
        dialQualityLog.start(dialSubscriptionId)
    }

    // Single teardown path for call end — called from GsmDialer callback only.
    // PhoneStateListener IDLE is a safety net that calls this too, but only if still active.
    // Marked internal so GsmService can call it from the PhoneStateListener safety net.
    // endedDialId: the laptop's id of the call that ended (GsmInCallService.onCallRemoved knows it per call); the IDLE
    // paths have no call at hand and take the one still current.
    internal fun teardownCall(endedDialId: String? = GsmInCallService.currentDialId) {
        setCallActive(false)
        // before the capture below stops - the recording ends with the call, not with the "call ended" cue
        callRecorder.stop()
        // before the audio socket below is dropped - its upload backlog is part of the line
        dialQualityLog.stop()
        // The laptop may dial again while this teardown still waits for the end cue (up to 1.5 s): this call's handler
        // is taken here, so the socket the next dial opens for itself is never the one dropped below
        val handler = audioWsHandlerRef.handler
        audioWsHandlerRef.handler = null
        dialSubscriptionId = null
        // Cleared so the next call's connect signal is honored; the old value says whether this
        // call ever connected (busy / no answer never did).
        val wasConnected = callConnectedHandled.getAndSet(false)
        callAnsweredSent.set(false)
        val wasBrowserDriven = browserDrivenCall
        browserDrivenCall = false
        AudioWebSocketHandler.useBrowserMicUplink = false  // reset to working PHONE/headset uplink
        onMicSourceReset()
        log("📴 Call ended — tearing down audio")
        try {
            val am = audioManager
            // Browser-driven call: the phone was quiet (mixer muted, voice volume at the floor), so
            // make it audible FIRST and only then start the "call ended" cue. Started from
            // STATE_DISCONNECTED into the mute (the old way) only its tail came through after the
            // unmute — a click. The clip sounds while the capture teardown below runs; awaited before
            // the mode reset so the route change does not cut it. A manual call keeps the operator's
            // volume (the unmute is an idempotent safety net) and got its cue in onCallDisconnected()
            // already. Like the start cue, only for a call that actually connected — busy / no
            // answer gets no "ended" without a "started".
            if (wasBrowserDriven) liftQuietState() else RootUtils.unmutePhoneSpeaker()
            if (wasBrowserDriven && wasConnected) callSounds.play(R.raw.end_call)
            RootUtils.disableIncallMusicCapture()
            RootUtils.disableIncallMusicInjection()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
            // Every call: a manual call's cue (started in onCallDisconnected) may still be sounding
            // too, and the mode reset below would re-route it mid-clip. No-op when nothing plays.
            callSounds.awaitFinished()
            // the mic level the website's "Mic vol" changed goes back to the phone's own
            if (micVolStatus == MIC_VOL_CODEC) RootUtils.restoreMicVolume()
            micVolStatus = ""
            // The laptop dialed again while the cue played - the volume and the mode are that call's now
            if (!isCallActive) {
                // The cue is over — the operator's own voice volume comes back, not the cue's max, so
                // the next call (manual, or this gateway's next ring) does not start at full blast.
                restoreVoiceVolume()
                am.isSpeakerphoneOn = false
                am.mode = AudioManager.MODE_NORMAL
            }
        } catch (_: Exception) {}
        try {
            handler?.setCallActive(false)
            handler?.stopAudioCapture()
            handler?.stopAudioPlayback()
            handler?.disconnect()
        } catch (_: Exception) {}
        // dialId: the laptop ends the card of this call only - a later dial's card stays (plan-09 task 8)
        val endedData = dialIdData(endedDialId)
        Thread {
            repeat(3) { attempt ->
                val client = cmdWsClient()
                if (client?.isConnected == true) {
                    client.sendEvent("CALL_ENDED", endedData)
                    log("📴 CALL_ENDED sent (attempt ${attempt + 1})")
                    return@Thread
                }
                log("📴 CALL_ENDED attempt ${attempt + 1} — WS not ready, retrying...")
                Thread.sleep(500)
            }
            cmdWsClient()?.sendEvent("CALL_ENDED", endedData)
            log("📴 CALL_ENDED final attempt sent")
        }.start()
    }

    fun handleCallStarted(data: Map<String, Any>, gsmDialer: GsmDialer?) {
        try {
            val number = data["number"] as? String
            if (number.isNullOrBlank()) {
                log("CALL_STARTED ignored: missing number")
                return
            }
            val simAccountId = data["simAccountId"] as? String
            val simComponentName = data["simComponentName"] as? String
            // the laptop's own id of this dial - Telecom's next outgoing call gets it, CALL_CONNECTED / CALL_ENDED send it back
            val dialId = data["dialId"] as? String
            log("Starting call to: $number (sim=$simAccountId)")
            dialSubscriptionId = simAccountId?.let { runCatching { gsmDialer?.selectSubscriptionId(it) }.getOrNull() }
            voiceVolumeBeforeCall = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            isAnswerAfterEnd = false
            answerAfterEndDialId = null
            GsmInCallService.setNextOutgoingDialId(dialId)
            browserCallStartedAtMs = System.currentTimeMillis()
            // Quiet from before the dial. Not enough on its own: the HAL rewrites the EAR_S/SPK mixer
            // when it brings up the voice path for the route a moment later, which is how the phone
            // rang along with the browser for the whole ringing phase — onPhoneRouteChanged() applies
            // the state again once Telecom has settled that route.
            setPhoneQuiet()
            callConnectedHandled.set(false)  // arm for this call's OFFHOOK / STATE_ACTIVE signal
            callAnsweredSent.set(false)
            browserDrivenCall = true
            setCallActive(true)
            // "dialing" for the laptop's status poll right away, before Telecom has added the call
            cmdWsClient()?.sendEvent("CALL_STATE", callStateMap())
            // GsmDialer callbacks (set once in wireDialerCallbacks) handle OFFHOOK and IDLE — do NOT
            // override setCallConnectedCallback here; doing so per-call created duplicate
            // teardown paths that fired triple CALL_ENDED events.
            try {
                val started = gsmDialer?.startCall(number, simAccountId, simComponentName) ?: false
                if (started) {
                    log("Call started via GsmDialer to $number")
                    // Open the audio WS now (before OFFHOOK) and stream a ringback tone to the
                    // website while dialing. Phone plays nothing — the tone is generated as PCM
                    // and sent over /ws/audio, exactly like downlink call audio.
                    startRingbackToWebsite()
                } else {
                    abortBrowserCall("Call start failed - syncing CALL_ENDED state", dialId)
                }
            } catch (error: Exception) {
                abortBrowserCall("ERROR starting call: ${error.message}", dialId)
                error.printStackTrace()
            }
        } catch (e: Exception) {
            log("FATAL ERROR in handleCallStarted: ${e.message}")
            e.printStackTrace()
        }
    }

    // an incoming call rings - the laptop's card shows "Incoming · <number>" with [Ignore] [Answer] (plan-05 task 7)
    private fun onIncomingCall(number: String) {
        log("📲 Incoming call from ${number.ifBlank { "hidden number" }}")
        cmdWsClient()?.sendEvent("INCOMING_CALL", mapOf("number" to number))
    }

    // the ringing call went away unanswered, alone or next to an active one - the laptop drops its "Incoming" card or
    // line, an active call stays
    private fun onIncomingCallEnded() {
        log("📲 The ringing call went away")
        cmdWsClient()?.sendEvent("INCOMING_CALL_ENDED", emptyMap())
    }

    // [Answer] on the laptop: the ringing call becomes a browser-driven one - the phone stays quiet and the audio goes
    // to the laptop like an outgoing call's; Telecom's STATE_ACTIVE then runs onCallConnected(SOURCE_ANSWERED).
    // Answered on the phone itself it stays a manual, audible call (edge case 7)
    fun handleAnswerCall(data: Map<String, Any> = emptyMap()) {
        // the laptop's own id of this answer - CALL_CONNECTED / CALL_ENDED send it back
        val dialId = data["dialId"] as? String
        if (isCallActive) {
            if (!GsmInCallService.hasRingingCall) {
                log("ANSWER_CALL ignored: a call is on and nothing else rings")
                return
            }
            log("📲 ANSWER_CALL: the waiting call is answered once the active call ends")
            answerAfterEndDialId = dialId
            isAnswerAfterEnd = true
            return
        }
        // the ring ended a moment before [Answer] - the laptop shows "Answering..." already, CALL_ENDED takes it back
        if (!GsmInCallService.hasRingingCall) {
            log("ANSWER_CALL: no ringing call - syncing CALL_ENDED state")
            cmdWsClient()?.sendEvent("CALL_ENDED", dialIdData(dialId))
            return
        }
        GsmInCallService.setRingingDialId(dialId)
        voiceVolumeBeforeCall = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        callConnectedHandled.set(false)
        callAnsweredSent.set(false)
        browserDrivenCall = true
        setCallActive(true)
        Thread {
            try {
                setPhoneQuiet()
                if (GsmInCallService.answerRingingCall()) log("📲 Incoming call answered from the laptop")
                else abortBrowserCall("ANSWER_CALL: the ringing call went away - syncing CALL_ENDED state", dialId)
            } catch (error: Exception) {
                abortBrowserCall("ERROR answering the call: ${error.message}", dialId)
            }
        }.start()
    }

    // SET_MIC_MUTED from the laptop's [Mute]: the call's uplink is the phone's own mic / the headset's mic (the
    // laptop's mic never reaches a GSM call), so the mute is Telecom's own
    fun handleSetMicMuted(data: Map<String, Any>) {
        val isMuted = data["isMuted"] as? Boolean ?: return
        val isSet = GsmInCallService.updateMicMuted(isMuted)
        log(if (isSet) "🎙️ SET_MIC_MUTED $isMuted" else "SET_MIC_MUTED ignored: no live call")
    }

    // IGNORE_CALL from the laptop ([Ignore] on the card, the ringing call alone or waiting next to an active one): the
    // phone stops ringing, the caller keeps hearing it ring - his "for that person that is calling that person should
    // still hear "tyyy" sounds". Her own hang-up (or the network's ring timeout) ends it later through onCallEnded ->
    // INCOMING_CALL_ENDED, like any unanswered ring
    fun handleIgnoreCall() {
        val ignored = GsmInCallService.ignoreRingingCall(context)
        log(if (ignored) "🔕 Incoming call ignored from the laptop - it rings on for the caller" else "IGNORE_CALL: nothing rings")
    }

    // GET_CALL_LOG { limit } from the laptop when its socket opens (plan-05 task 9): the newest rows of the phone's own
    // call log -> CALL_LOG { rows } - the calls made while no tab was open land in "Recent Calls" this way
    fun handleGetCallLog(data: Map<String, Any>) {
        val limit = ((data["limit"] as? Number)?.toInt() ?: CALL_LOG_DEFAULT_LIMIT).coerceIn(1, CALL_LOG_MAX_LIMIT)
        Thread {
            try {
                val rows = CallLogReader.select(context, limit)
                // the recordings no tab took at their hang-up (plan-06 3.2) - the website asks an upload link for each
                cmdWsClient()?.sendEvent(
                    "CALL_LOG",
                    mapOf("rows" to rows.map { it.toMap() }, "pendingRecordingIds" to callRecorder.selectPendingIds())
                )
                log("📒 CALL_LOG sent: ${rows.size} rows")
            } catch (error: SecurityException) {
                cmdWsClient()?.sendEvent("CALL_LOG", mapOf("error" to "Phone has no call log access"))
                log("📒 CALL_LOG: no READ_CALL_LOG - ${error.message}")
            } catch (error: Exception) {
                log("📒 CALL_LOG error: ${error.message}")
            }
        }.start()
    }

    // SET_RECORDING's recordingDeleteAfterDays (plan-06 task 7) - the files no upload took within N days
    fun deleteRecordingsOlderThan(days: Int) = callRecorder.deleteOlderThan(days)

    // UPLOAD_RECORDING { callLogId, url } (plan-06 task 3) - the website's one-time link to the user's "call-recordings"
    // bucket; RECORDING_UPLOADED / RECORDING_FAILED tell it how it went
    fun handleUploadRecording(data: Map<String, Any>) {
        val callLogId = data["callLogId"]?.toString().orEmpty()
        val url = data["url"] as? String
        if (callLogId.isBlank() || url.isNullOrBlank()) {
            log("UPLOAD_RECORDING ignored: missing callLogId or url")
            return
        }
        callRecorder.upload(
            callLogId,
            url,
            onUploaded = { cmdWsClient()?.sendEvent("RECORDING_UPLOADED", mapOf("callLogId" to callLogId)) },
            onFailed = { reason -> cmdWsClient()?.sendEvent("RECORDING_FAILED", mapOf("callLogId" to callLogId, "reason" to reason)) }
        )
    }

    // A browser dial that never left the phone: undo handleCallStarted's pre-dial state and tell the
    // backend. Not teardownCall() — no audio path was opened yet. The unmute + volume matter: a
    // manual call stays audible by design now, so it must not inherit the quiet state armed here.
    private fun abortBrowserCall(reason: String, dialId: String?) {
        log(reason)
        browserDrivenCall = false
        setCallActive(false)
        GsmInCallService.setNextOutgoingDialId(null)
        RootUtils.unmutePhoneSpeaker()
        restoreVoiceVolume()
        audioWsHandlerRef.handler?.disconnect()
        cmdWsClient()?.sendEvent("CALL_ENDED", dialIdData(dialId))
    }

    // Opens the audio WS (if needed) during dialing and streams a ringback tone to the website.
    // Runs on its own thread so it doesn't block command handling. The answer (STATE_ACTIVE) stops
    // the ringback; teardownCall() disconnects the handler if the call never connects.
    private fun startRingbackToWebsite() {
        Thread {
            try {
                val wsUrl = audioWsUrl()

                audioWsHandlerRef.handler?.disconnect()
                val handler = AudioWebSocketHandler(context, config, log = log) { }
                audioWsHandlerRef.handler = handler
                handler.connect(wsUrl, config.BACKEND_BEARER ?: "", config.DEVICE_TOKEN ?: "")

                val deadline = System.currentTimeMillis() + 5000
                while (handler.isWsConnected != true && System.currentTimeMillis() < deadline) {
                    Thread.sleep(100)
                }
                // The tone starts only while the dial still rings out (race with a fast answer) and only when the
                // website is the call's output - a headset paired to the phone plays the network's own ringback
                if (handler.isWsConnected) applyAudioToBrowser()
                else log("⚠️ Ringback skipped: the audio socket did not open in 5 s")
            } catch (e: Exception) {
                log("ERROR starting ringback to website: ${e.message}")
            }
        }.start()
    }

    fun handleCallEnded(gsmDialer: GsmDialer?) {
        // Frontend requested hang-up. Call gsmDialer.endCall() to dismiss the call, then
        // let teardownCall() handle all audio/WS/event cleanup — same path as telephony IDLE.
        // Do NOT send CALL_ENDED here directly; teardownCall() does it, preventing double-send.
        try {
            log("Ending call (frontend request)")
            // the hang-up came before Telecom added this phone's own dial (currentCall still null): Call.disconnect() has
            // nothing to act on and TelecomManager.endCall() no-ops while dialing on MIUI, so the dial went on with no
            // card on the laptop. The dial ends the moment Telecom adds it instead
            if (isCallActive && GsmInCallService.currentCall == null) {
                GsmInCallService.endNextOutgoingCall(DIAL_PLACE_WAIT_MS)
                log("📴 Hang-up before the dial was placed - it ends the moment Telecom adds it")
            }
            Thread {
                try {
                    gsmDialer?.endCall()
                    // teardownCall fires via GsmDialer's IDLE callback once the modem confirms.
                    // If for some reason the callback doesn't fire within 3s, the PhoneStateListener
                    // safety net will invoke teardownCall anyway.
                } catch (error: Exception) {
                    log("ERROR in handleCallEnded thread: ${error.message}")
                }
            }.start()
        } catch (error: Exception) {
            log("ERROR in handleCallEnded: ${error.message}")
        }
    }

    fun handleSendDtmf(data: Map<String, Any>) {
        try {
            val digit = (data["digit"] as? String).orEmpty()
            if (digit.isEmpty()) { log("SEND_DTMF ignored: missing digit"); return }
            val call = GsmInCallService.currentCall
            if (call == null) {
                log("SEND_DTMF $digit ignored: no Call (app must be default dialer + call must be active)")
                cmdWsClient()?.sendEvent("DTMF_FAILED", mapOf("digit" to digit, "reason" to "no_call"))
                return
            }
            val c = digit[0]
            log("Sending DTMF $c via Call.playDtmfTone() (out-of-band → modem)")
            call.playDtmfTone(c)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try { call.stopDtmfTone() } catch (_: Exception) {}
            }, 200)
            cmdWsClient()?.sendEvent("DTMF_SENT", mapOf("digit" to digit))
        } catch (e: Exception) {
            log("ERROR in handleSendDtmf: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun setVoiceVolume(level: Int) =
        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, level, 0)

    // Voice volume and the EAR_S/SPK mixer together: that pair is what "the phone is quiet" means
    // for the gateway. Blocks on su — call from a background thread.
    private fun setPhoneQuiet() {
        setVoiceVolume(0)
        RootUtils.mutePhoneSpeaker()
    }

    // The opposite, whatever the route — around a cue, and before the volume is handed back.
    private fun liftQuietState() {
        setVoiceVolume(maxVoiceVolume)
        RootUtils.unmutePhoneSpeaker()
    }

    // The song during a call: AudioService keeps a music level per output, and the call link's own entry was
    // 10 of 15 - some 14 dB under the call itself, so the music came through quiet at "100%" (2026-11-02:
    // "its volume is lower even if I have 100%"). Max here, so the browser's percent is the only control and
    // 100% is as loud as the call. Written while the call link is the music's output, it is that link's entry
    // only - the music link between calls keeps the level the operator set on the phone.
    private fun liftMusicOnCallLink() {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
    }

    private fun restoreVoiceVolume() {
        if (scoVolumeBeforeCall >= 0) {
            audioManager.setStreamVolume(STREAM_BLUETOOTH_SCO, scoVolumeBeforeCall, 0)
            scoVolumeBeforeCall = -1
        }
        if (voiceVolumeBeforeCall < 0) return
        setVoiceVolume(voiceVolumeBeforeCall)
        voiceVolumeBeforeCall = -1
    }

    // SET_GAIN playbackGain (the website's "Output vol", 0..4 = 0..400%) as the headset's call volume: 100% and
    // above is the max. A Bluetooth headset has a volume stream of its own - while the call is on its link, Android
    // drops every STREAM_VOICE_CALL change (AudioPolicyManager "do not change in call volume if bluetooth is
    // connected"), so that stream changes nothing in the headset. 0% = the Bluetooth stream's lowest step.
    private fun setHeadsetCallVolume(isBluetooth: Boolean): String {
        val gain = AudioWebSocketHandler.playbackGain.coerceIn(0f, 1f)
        if (isBluetooth) {
            val max = audioManager.getStreamMaxVolume(STREAM_BLUETOOTH_SCO)
            if (scoVolumeBeforeCall < 0) scoVolumeBeforeCall = audioManager.getStreamVolume(STREAM_BLUETOOTH_SCO)
            val level = (max * gain).roundToInt()
            audioManager.setStreamVolume(STREAM_BLUETOOTH_SCO, level, 0)
            return "Bluetooth call volume $level/$max"
        }
        // a wired headset plays the voice-call stream; Android keeps it at step 1 or above
        val level = (maxVoiceVolume * gain).roundToInt().coerceAtLeast(1)
        setVoiceVolume(level)
        return "voice volume $level/$maxVoiceVolume"
    }

    // Wired to GsmService's SET_GAIN: the website moved "Output vol", or its ringing percent kicked in, during a
    // browser-driven call - the headset level follows at once. Blocks on su - its own thread.
    fun onPlaybackGainChanged() {
        if (!browserDrivenCall) return
        Thread {
            try {
                applyPhoneAudibility()
            } catch (e: Exception) {
                log("ERROR applying the playback gain: ${e.message}")
            }
        }.start()
    }

    // Wired to GsmService's SET_GAIN micGain: the website moved "Mic vol" - how loud the lead hears him. Blocks on
    // su - its own thread. Between calls there is no mic path to set, the next call's connect applies it.
    fun onMicGainChanged() {
        if (!isCallActive) return
        Thread {
            try {
                applyMicGain()
                cmdWsClient()?.sendEvent("CALL_STATE", callStateMap())
            } catch (e: Exception) {
                log("ERROR applying the mic gain: ${e.message}")
            }
        }.start()
    }

    // "Mic vol" (SET_GAIN micGain, 1.0 = the phone's own level): the mic the call uses right now, by Telecom's route.
    //  - the phone's own mic / a wired headset's mic run through the codec - its mic volume controls take the gain
    //  - a Bluetooth headset's mic comes from the Bluetooth radio straight into the voice DSP: no control on the phone
    //    sits on that road, the headset sets its own level
    // 1.0 with nothing changed before is left alone, so a call that never touched the slider runs no root command.
    private fun applyMicGain() {
        val micGain = AudioWebSocketHandler.micGain
        if (GsmInCallService.currentAudioState?.route == CallAudioState.ROUTE_BLUETOOTH) {
            micVolStatus = MIC_VOL_BLUETOOTH
            if (micGain != 1f) log("🎙️ Mic vol not applied: a Bluetooth headset sets its own mic level")
            return
        }
        if (micGain == 1f && micVolStatus != MIC_VOL_CODEC) return
        val isApplied = RootUtils.updateMicVolume(micGain)
        micVolStatus = if (isApplied) MIC_VOL_CODEC else MIC_VOL_NONE
        log(if (isApplied) "🎙️ Mic vol x$micGain applied to the codec's mic volume" else "🎙️ Mic vol not applied: no mic volume control on this phone")
    }

    // What is paired / plugged into the phone right now - the call's output and its mic: "bluetooth" (a headset with
    // a call link), "wired", or "none" (the phone's own earpiece + mic)
    private fun headsetName(): String {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return when {
            outputs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } -> HEADSET_BLUETOOTH
            outputs.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES } -> HEADSET_WIRED
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && outputs.any { it.type == AudioDeviceInfo.TYPE_USB_HEADSET } -> HEADSET_WIRED
            else -> HEADSET_NONE
        }
    }

    // A headset paired to the phone is the call's one output. Telecom's route once the call has one; in the dial's
    // first moment (no route yet) what is connected right now - Telecom picks a connected headset by itself.
    private fun isHeadsetCarryingCall(): Boolean = when (GsmInCallService.currentAudioState?.route) {
        CallAudioState.ROUTE_BLUETOOTH, CallAudioState.ROUTE_WIRED_HEADSET -> true
        null -> headsetName() != HEADSET_NONE
        else -> false
    }

    // The dial is out and nobody answered yet - the only time the generated ringback may play.
    private fun isDialRingingOut(): Boolean {
        if (!isCallActive || !browserDrivenCall || callAnsweredSent.get()) return false
        val state = GsmInCallService.currentCall?.state ?: return true
        return state != Call.STATE_ACTIVE && state != Call.STATE_HOLDING && state != Call.STATE_RINGING &&
            state != Call.STATE_DISCONNECTED && state != Call.STATE_DISCONNECTING
    }

    // One output for the call's sound (outreach-tool plan-09 task 5). A headset that carries the call plays the
    // network's own ringback and the lead's voice - the website then gets neither: both outputs at once were the
    // 2nd, quieter "tyyy", and the stream ran over the same 2.4 GHz radio as the Bluetooth call link. No headset =
    // the website is the output: the generated ringback while it rings, the downlink after. tinycap keeps running
    // either way (CallRecorder's tee).
    private fun applyAudioToBrowser() {
        val handler = audioWsHandlerRef.handler ?: return
        val isHeadset = isHeadsetCarryingCall()
        handler.isAudioToBrowserOn = !isHeadset
        if (isHeadset) {
            handler.stopRingback()
            log("🎧 Call sound: the headset paired to the phone - nothing goes to the website")
        } else if (isDialRingingOut() && handler.isWsConnected) {
            handler.startRingback()
        }
    }

    // Phone-side audibility of a browser-driven call, from Telecom's real route, not a mirror flag:
    //  - loudspeaker: the operator turned it on — fully audible
    //  - a headset paired to the phone (Bluetooth / wired): it carries the call at the website's
    //    "Playback Vol", the phone's earpiece + loudspeaker stay muted
    //  - earpiece: quiet, audio to the frontend only
    // A manual call is left alone — Telecom's own route handling is the whole job there. Blocks on
    // su — call from a background thread.
    private fun applyPhoneAudibility() {
        if (!browserDrivenCall) return
        when (val route = GsmInCallService.currentAudioState?.route) {
            CallAudioState.ROUTE_SPEAKER -> {
                liftQuietState()
                log("🔊 Phone audible — loudspeaker on during a browser-driven call")
            }
            CallAudioState.ROUTE_BLUETOOTH, CallAudioState.ROUTE_WIRED_HEADSET -> {
                // the headset's call volume follows the website's "Output vol" (SET_GAIN playbackGain); while
                // it rings with music on, the website lowers that gain to its "ringing while music plays" percent
                val volumeText = setHeadsetCallVolume(isBluetooth = route == CallAudioState.ROUTE_BLUETOOTH)
                liftMusicOnCallLink()
                RootUtils.mutePhoneSpeaker()
                log("🎧 Headset carries the browser-driven call at $volumeText — phone itself quiet")
            }
            else -> {
                setPhoneQuiet()
                log("🔇 Phone quiet — browser-driven call, audio to frontend only")
            }
        }
    }

    // Wired to GsmInCallService.onCallDisconnected (Telecom STATE_DISCONNECTED, just before
    // onCallRemoved). A manual call is audible as-is, so its "call ended" cue plays right here,
    // still on the call route like any dialer's end tone. A browser-driven call is muted at this
    // point — its cue plays in teardownCall() once the phone is unmuted. A call that never
    // connected (missed or rejected incoming) gets no cue: no "started" means no "ended".
    private fun onCallDisconnected() {
        if (browserDrivenCall || !callConnectedHandled.get()) return
        callSounds.play(R.raw.end_call)
    }

    // Wired to GsmInCallService.onAudioRouteChanged — the first route of the call (what it rings on)
    // and every switch after it (loudspeaker toggle, headset plugged in). Telecom has already switched
    // the route; the HAL then rewrites its own EAR_S/SPK mixer path for the new device a moment
    // later, which would undo a state applied at once — so it is applied after that window, and once
    // more in case the HAL was slower. Each pass reads the live route, so a quick on-off-on settles
    // on the real one.
    private fun onPhoneRouteChanged() {
        dialQualityLog.onRouteChanged()
        if (!browserDrivenCall) return
        Thread {
            try {
                for (delayMs in AUDIBILITY_REAPPLY_DELAYS_MS) {
                    Thread.sleep(delayMs)
                    if (!browserDrivenCall) return@Thread
                    applyPhoneAudibility()
                    // the route decides the call's one output and which mic "Mic vol" acts on - the HAL sets its own
                    // mic volume again for the new route
                    applyAudioToBrowser()
                    applyMicGain()
                }
                // the headset / the mic of the new route - the website's gear menu shows both
                cmdWsClient()?.sendEvent("CALL_STATE", callStateMap())
            } catch (e: Exception) {
                log("ERROR applying the call route: ${e.message}")
            }
        }.start()
    }

    private fun setCallActive(active: Boolean) {
        isCallActive = active
        onCallActiveChanged(active)
    }

    private fun audioWsUrl(): String =
        config.BACKEND_URL
            ?.replace("http://", "ws://")
            ?.replace("https://", "wss://")
            ?.removeSuffix("/") + "/ws/audio"

    companion object {
        // Waits between a route change and the audibility passes (see onPhoneRouteChanged): 0.5 s
        // clears the HAL's own mixer write for the new device, the second pass at 2 s is the margin.
        private val AUDIBILITY_REAPPLY_DELAYS_MS = longArrayOf(500, 1500)
        // the two "call audio" signals - see callConnectedHandled / callAnsweredSent
        private const val SOURCE_OFFHOOK = "OFFHOOK"
        private const val SOURCE_ANSWERED = "STATE_ACTIVE"
        // AudioManager.STREAM_BLUETOOTH_SCO - hidden in the SDK, its value is part of the platform since API 1
        private const val STREAM_BLUETOOTH_SCO = 6
        // headsetName()
        private const val HEADSET_BLUETOOTH = "bluetooth"
        private const val HEADSET_WIRED = "wired"
        private const val HEADSET_NONE = "none"
        // micVolStatus
        private const val MIC_VOL_BLUETOOTH = "bluetooth"
        private const val MIC_VOL_CODEC = "codec"
        private const val MIC_VOL_NONE = "none"
        // reportCall: how long the call log row may take to show up, and how far before the call was added the row's
        // start may sit (the call log's DATE is the dial, Telecom's add comes a beat later)
        private const val CALL_LOG_WAIT_MS = 3000L
        private const val CALL_LOG_START_MARGIN_MS = 5000L
        // handleGetCallLog: the rows one GET_CALL_LOG answers with
        private const val CALL_LOG_DEFAULT_LIMIT = 50
        private const val CALL_LOG_MAX_LIMIT = 300
        // handleCallEnded: how long a hang-up that came before Telecom added the dial waits for that dial (the dial
        // took 3 s to go OFFHOOK on 11.11.2026)
        private const val DIAL_PLACE_WAIT_MS = 10_000L
    }
}
