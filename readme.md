# Android GSM Gateway

Android foreground service that exposes **real GSM calls** to a browser via
**WebSocket** (audio + commands) + **Pusher** (browser-side call state events only).

Phone = modem. Backend = brain. Frontend controls service lifecycle.

---

## First-time setup (Kali / no Android SDK)

```bash
chmod +x install-android-sdk.sh
./install-android-sdk.sh
source ~/.zshrc
```

## Config (required before first build)

```bash
cp app/src/main/assets/androidgsm.config.example.json app/src/main/assets/androidgsm.config.json
```

Edit `app/src/main/assets/androidgsm.config.json`:

```json
{
  "BACKEND_URL": "https://your-backend.com",
  "BACKEND_BEARER": "your-secret-token",
  "PUSHER_KEY": "your-pusher-key",
  "PUSHER_CLUSTER": "eu",
  "DEVICE_TOKEN": ""
}
```

> `androidgsm.config.json` is gitignored — never committed.
> `DEVICE_TOKEN` is auto-generated from `{manufacturer}_{model}` at runtime.

## Build & run on phone

```bash
adb devices   # must show device, not unauthorized

# Always clean to avoid INSTALL_PARSE_FAILED
adb shell am force-stop com.nicitaacom.androidgsm \
  ; ./gradlew clean assembleDebug \
  && adb install -r -d $(ls -t app/build/outputs/apk/debug/*.apk | head -n1) \
  && adb shell am start -n com.nicitaacom.androidgsm/.MainActivity

# Live logs
adb logcat -s GSM:D AudioWebSocket:D WebSocketAudio:D CmdWS:D RootUtils:D
```

## In-app setup (first run)

1. Grant all permissions when prompted
2. Phone connects to backend via `/ws/cmd` — shows "Status: Not Active"
3. Use the **website** to start SERVICE or TEST mode — phone UI updates accordingly
4. Use the website to make/end calls

---

## Architecture

### Pusher usage (minimal — browser only)

Pusher is used **only** to push call state events to the browser (5–10 messages per call).
Android does NOT subscribe to Pusher. All Android↔backend communication goes over WebSocket.

| What | Where | Cost |
|------|-------|------|
| Call state (started/connected/ended) | Pusher → browser | ~6 msgs/call |
| Device ready state | Pusher → browser | 1 msg/connect (debounced 5min) |
| Android commands (CALL_STARTED, CALL_ENDED, START_SERVICE, etc.) | `/ws/cmd` WebSocket | 0 Pusher msgs |
| Android heartbeats | `/ws/cmd` WebSocket | 0 Pusher msgs |
| Audio | `/ws/audio` WebSocket | 0 Pusher msgs |

### Service lifecycle

The phone app **never auto-starts SERVICE or TEST mode** on launch. GsmService starts as a
foreground process for WebSocket connectivity only. SERVICE/TEST audio modes are started and
stopped exclusively from the frontend via commands over `/ws/cmd`.

State sync is dual-path:
1. **Immediate**: Android sends `SERVICE_STARTED`/`SERVICE_STOPPED` events over `/ws/cmd` with a retry loop (10s deadline, 300ms poll) to handle WS not yet open
2. **Periodic fallback**: Heartbeat every 15s carries `isServiceActive` and `isTestActive` via `stateProvider` lambda

Frontend polls `/api/gsm/status` every 2s — returns `isServiceActive` and `isTestActive` so the UI reflects real phone state.

### System diagram

```
┌──────────────────────────────────┐
│        Next.js Frontend          │
│  useInitGSM hook                 │
│  ┌──────────────────────────┐    │
│  │ startService/stopService │ ── POST /api/gsm/commands ─────────────────────────┐
│  │ startTest/stopTest       │ ── POST /api/gsm/commands ─────────────────────────┤
│  │ setMicGain/setPlayback   │ ── POST /api/gsm/commands ─────────────────────────┤
│  │ call() / hungUp()        │ ── POST /api/gsm/call-started|ended ───────────────┤
│  │ sendDTMF()               │ ── POST /api/gsm/send-dtmf ────────────────────────┤
│  │ fetchLogs()              │ ── GET  /api/gsm/logs ─────────────────────────────┤
│  │ mic audio (PCM16)        │ ── WS /ws/audio (dir=toAndroid) ──────────────┐    │
│  │ speaker playback ◄───────│─── WS /ws/audio (dir=toBrowser) ◄────────────┤    │
│  └──────────────────────────┘    │                                          │    │
│  Pusher subscriptions:           │                                          │    │
│  gsm-calls ◄─────────────────────┤─ call-started/connected/ended            │    │
│  gsm-devices ◄───────────────────┤─ device-connected                        │    │
└──────────────────────────────────┘                                          │    │
                                                                              │    │
                    ┌─────────────────────────────────────────────────────────┘    │
                    │   server.ts (VPS / Express)                                   │
                    │  WS /ws/cmd  ◄──── Android persistent cmd WS ───────────────┐│
                    │    heartbeat → update lastSeen + isServiceActive/isTestActive ││
                    │    SERVICE_STARTED/STOPPED → update connectedDevices          ││
                    │    commands → deliver to Android over this WS                 ││
                    │  WS /ws/audio ◄──── Android audio WS ────────────────────────┘│
                    │    relay browser ↔ android PCM16 audio                        │
                    │  POST /api/commands → sends command over /ws/cmd              │
                    │  GET  /api/logs    → returns last 200 server log lines        │
                    │  POST /api/gsm/call-started → send CALL_STARTED over /ws/cmd  │
                    │  POST /api/gsm/call-ended   → send CALL_ENDED over /ws/cmd    │
                    │  POST /api/gsm/send-dtmf    → send SEND_DTMF over /ws/cmd     │
                    │  GET  /api/gsm/status       → { isAuthorized, deviceToken,    │
                    │                                  sims[], isServiceActive,      │
                    │                                  isTestActive }                │
                    └────────────────────────────────────────────────────────────────
                                         ↕ WS /ws/cmd  (persistent)
                                 ┌──────────────────────┐
                                 │   Android App        │
                                 │                      │
                                 │  GsmService          │
                                 │  GsmDialer           │
                                 │  CommandWebSocketClient
                                 │  AudioWebSocketHandler
                                 └──────────────────────┘
```

### Android components

```
GsmService              Foreground service; owns all state, dispatches commands.
                        Starts on launch for /ws/cmd connectivity only.
                        SERVICE/TEST audio modes require explicit frontend commands.
                        Does NOT use PusherClient — all backend comms via WS.

CommandWebSocketClient  Persistent WS to /ws/cmd. Stays connected always.
                        Receives CALL_STARTED/CALL_ENDED/START_SERVICE/STOP_SERVICE/
                          START_TEST/STOP_TEST/SET_GAIN commands.
                        Sends CONNECTED heartbeats every 15s with isServiceActive +
                          isTestActive state (no Pusher cost).
                        Sends SERVICE_STARTED/STOPPED/CALL_CONNECTED/CALL_ENDED events.
                        Auto-reconnects on failure.
                        stateProvider lambda: injected by GsmService so heartbeats
                          always carry current audio mode state.

GsmDialer               Registers TelephonyCallback (API 31+) or PhoneStateListener.
                        Places calls via TelecomManager.placeCall() with PhoneAccountHandle
                        for correct SIM routing on dual-SIM devices.
                        OFFHOOK → starts audio; IDLE → stops audio + sends CALL_ENDED.

AudioWebSocketHandler   WS duplex audio (/ws/audio).
                        Call mode: tinycap subprocess (root) reads ALSA MultiMedia1 kernel
                          device directly, streams raw PCM16 to server.
                        TEST mode: standard AudioRecord from mic.
                        Playback: Channel<ShortArray>(capacity=64) single-consumer coroutine.
                        AudioManager mode changes are gated on !isCallActive to avoid
                          overriding GsmService's MODE_IN_CALL+speakerphone setup.

WebSocketAudioClient    OkHttp WS; sends registration packet on open; routes command
                        messages (type="command") to onCommand callback.

RootUtils               su -c with 3s timeout + stream-draining threads.
                        Uses set_mixer_ctl native binary (assets/set_mixer_ctl, arm64 ELF)
                          to write individual BOOL element indices via SNDRV_CTL_IOCTL_ELEM_WRITE,
                          bypassing broken mixer_ctl_get_array in MIUI's tinyalsa.
                        enableIncallMusicCapture(): sets MultiMedia1 Mixer VOC_REC_DL slots 0+1
                        disableIncallMusicCapture(): clears both slots.
                        enableIncallMusicInjection(): sets Incall_Music Audio Mixer MM1/MM5
                          slots 0+1 (VoiceMMode1+VoiceMMode2), mutes hardware mic TX.
                        disableIncallMusicInjection(): clears above, restores mic TX.
                        nativeBinDir: set by GsmService.onCreate() after unpacking asset.

AudioStreamHandler      LEGACY — not used in call path. Kept for reference.
```

---

## How GSM call audio gets to the browser

This was the hardest problem. Here's the full path and why each approach was needed.

### The problem

GSM call audio on Android routes through the **modem voice DSP**, completely bypassing
Android's AudioFlinger (the normal Java audio system). This means:

- `AudioRecord(VOICE_CALL)` — needs `CAPTURE_AUDIO_OUTPUT` (signature-level, cannot be granted via `pm grant` even with root on MIUI)
- `AudioRecord(REMOTE_SUBMIX)` — taps AudioFlinger output, but GSM audio never goes through AudioFlinger during a call → pure silence (RMS 0.00004)
- `AudioManager.setSpeakerphoneOn(true)` during a call — Telecom overrides it back to earpiece

### The solution: tinycap + set_mixer_ctl as root subprocesses

The GSM downlink audio IS available at the ALSA kernel level via a Qualcomm mixer route.
The uplink (browser mic → remote party) requires enabling a second mixer route.

Both routes have **2-slot BOOL controls** (slot 0 = VoiceMMode1, slot 1 = VoiceMMode2).
The device's `tinymix` binary cannot set slot 1 due to a broken `mixer_ctl_get_array` in
MIUI's tinyalsa build. We ship a native `set_mixer_ctl` binary (compiled with NDK r27 against
the kernel's `sound/asound.h`) that calls `SNDRV_CTL_IOCTL_ELEM_WRITE` directly per element index.

**Downlink (remote party → browser):**
```
set_mixer_ctl 0 'MultiMedia1 Mixer VOC_REC_DL' 0 1   # VoiceMMode1
set_mixer_ctl 0 'MultiMedia1 Mixer VOC_REC_DL' 1 1   # VoiceMMode2
tinycap /proc/self/fd/1 -D 0 -d 0 -c 1 -r 16000 -b 16
```
`VOC_REC_DL` taps the GSM call's receive path into MultiMedia1's ALSA capture device.
tinycap writes a 44-byte WAV header then raw PCM16 mono 16kHz to stdout.
The app reads stdout, skips the WAV header, and streams 320-sample (20ms) chunks.

**Uplink (browser mic → remote party):**
```
set_mixer_ctl 0 'Incall_Music Audio Mixer MultiMedia1' 0 1   # VoiceMMode1
set_mixer_ctl 0 'Incall_Music Audio Mixer MultiMedia1' 1 1   # VoiceMMode2
set_mixer_ctl 0 'Incall_Music Audio Mixer MultiMedia5' 0 1   # (MIUI may route to MM5)
set_mixer_ctl 0 'Incall_Music Audio Mixer MultiMedia5' 1 1
# also Incall_Music_2 variants for both MMode slots
```
`Incall_Music Audio Mixer` routes MultiMedia1/5 AudioTrack playback into the GSM TX uplink.
Browser mic PCM arrives via WebSocket → Android `AudioTrack` (USAGE_MEDIA) → MultiMedia1 →
voice DSP TX path → remote party.
Hardware mic TX is muted while injection is active.

**Step 3 — Stream to browser:**
```
raw PCM16 → base64 → WebSocket JSON → server.ts → browser WebSocket → AudioContext
```

**Why not `REMOTE_SUBMIX`?**
On Qualcomm sdm660 (Xiaomi Redmi Note 7), GSM audio goes:
```
modem → voice DSP → ALSA kernel → earpiece/speaker
```
It never enters AudioFlinger, so REMOTE_SUBMIX sees nothing. Confirmed via RMS logging
(consistently 0.000044 regardless of tinymix settings).

**Why not speakerphone?**
`AudioManager.setSpeakerphoneOn(true)` sets route momentarily, but `TelephonyManager`
immediately overrides it back to earpiece during an active call. Not reliable.

**Why not standard tinymix?**
The 2-slot BOOL controls (`Incall_Music Audio Mixer`, `VOC_REC_DL`) require writing each
element index separately. MIUI's tinyalsa `tinymix` calls `mixer_ctl_get_array` which fails
on this device with "Failed to mixer_ctl_get_array" — slot 1 (VoiceMMode2) is always left Off.
The `set_mixer_ctl` binary bypasses this by using the raw `SNDRV_CTL_IOCTL_ELEM_WRITE` ioctl.

### Browser playback quality fix

The browser creates an `AudioContext` at its default sample rate (48kHz).
`createBuffer(1, chunk.length, 16000)` inside a 48kHz context forces the browser to
upsample 3x using its internal resampler — this caused robotic/crackling artifacts.

Fix: create `AudioContext({ sampleRate: 16000 })` to match the incoming PCM exactly.
No resampling occurs, audio plays cleanly.

### Lookahead scheduler (gapless playback)

`setInterval`-based playback fires late due to browser tab throttling, causing gaps.
Instead, incoming chunks are scheduled end-to-end into AudioContext time:

```
nextPlayTime = max(nextPlayTime, ctx.currentTime + BUFFER_S)
src.start(nextPlayTime)
nextPlayTime += chunkDuration
```

150ms initial buffer absorbs network jitter. 250ms lookahead keeps the schedule filled.

---

## Call flow (SERVICE mode)

```
1. Phone opens app → GsmService starts for WS connectivity → CommandWebSocketClient connects to /ws/cmd
   Phone UI shows "Status: Not Active"

2. Frontend clicks START SERVICE:
   → POST /api/gsm/commands { type: "START_SERVICE" }
   → server sends { type:"command", cmdType:"START_SERVICE" } over /ws/cmd
   → GsmService.handleWsCommand("START_SERVICE")
   → startServiceDuplexOutputToServerAndServerToInput()
   → isServiceAudioActive = true
   → Phone UI shows "Status: Service Active" (green)
   → retry loop sends SERVICE_STARTED event until WS confirms isConnected
   → server updates connectedDevices.isServiceActive = true
   → status poll returns isServiceActive: true → frontend updates UI

3. Frontend call() → POST /api/gsm/call-started { num, deviceToken, simAccountId, simComponentName }
   → server sends { type:"command", cmdType:"CALL_STARTED", data:{number, simAccountId} } over /ws/cmd
   → GsmService.handleCommand("CALL_STARTED")
   → RootUtils.enableIncallMusicCapture()  (opens VOC_REC_DL mixer)
   → GsmDialer.startCall(number, simAccountId, simComponentName)
   → TelecomManager.placeCall() with correct PhoneAccountHandle (dual-SIM aware)
   → Pusher: gsm:call-started → browser (shows "dialing" state)

4. Carrier connects → TelephonyCallback fires OFFHOOK
   → GsmService sets MODE_IN_CALL + speakerphoneOn=true + STREAM_VOICE_CALL=max
   → AudioWebSocketHandler.connect() → /ws/audio
   → startAudioCapture() → launches tinycap subprocess as root
   → startAudioPlayback() → AudioTrack (USAGE_MEDIA) ready on MultiMedia1
   → Thread.sleep(200) → RootUtils.enableIncallMusicInjection()
        set_mixer_ctl: Incall_Music Audio Mixer MM1/MM5 slots 0+1 = On
        mute hardware mic TX (VoiceMMode1_Tx/VoiceMMode2_Tx)
   → CommandWebSocketClient.sendEvent("CALL_CONNECTED")
   → server: Pusher gsm:call-connected → browser (starts audio playout)

5. Audio flows:
   DOWNLINK: tinycap → raw PCM16 → base64 → /ws/audio → server → browser AudioContext
   UPLINK:   Browser mic → PCM16 → /ws/audio → server → Android AudioTrack → MultiMedia1
             → Incall_Music mixer → voice DSP TX → remote party

6. Call ends (either side):
   → TelephonyCallback fires IDLE (via GsmDialer.setCallEndedCallback)
   → RootUtils.disableIncallMusicCapture()  (VOC_REC_DL slots → Off)
   → RootUtils.disableIncallMusicInjection() (Incall_Music slots → Off, mic TX restored)
   → RootUtils.unmutePhoneSpeaker()
   → AudioWebSocketHandler stops + disconnects
   → CommandWebSocketClient.sendEvent("CALL_ENDED")
   → server: Pusher gsm:call-ended → browser (stops playout)
```

## TEST mode flow

```
Frontend clicks START TEST:
  → POST /api/gsm/commands { type: "START_TEST" }
  → server sends { type:"command", cmdType:"START_TEST" } over /ws/cmd
  → GsmService.handleWsCommand("START_TEST")
  → AudioWebSocketHandler (callActive=false) → /ws/audio
  → startAudioPlayback() + startAudioCapture() (uses mic, not tinycap)
  → CommandWebSocketClient.sendEvent("TEST_AUDIO_STARTED")
    → server: Pusher gsm:test-audio-started → browser
      → browser starts mic capture + audio playout
        → bidirectional PCM16 audio over /ws/audio

Frontend clicks STOP TEST:
  → POST /api/gsm/commands { type: "STOP_TEST" }
  → GsmService stops test audio, sends TEST_AUDIO_STOPPED
  → server: Pusher gsm:test-audio-stopped → browser
```

---

## Gain control

Mic gain and playback volume are controlled from the frontend — no phone UI sliders.

```
Frontend setMicGain(value) / setPlaybackGain(value):
  → POST /api/gsm/commands { type: "SET_GAIN", data: { micGain: value } }
  → server sends SET_GAIN command over /ws/cmd
  → GsmService.handleWsCommand("SET_GAIN")
  → AudioWebSocketHandler applies gain to capture/playback path
```

---

## Server logs

Server keeps a circular buffer of the last 200 log lines (meaningful events only, not audio chunks).

```
Frontend fetchLogs():
  → GET /api/gsm/logs
  → server: GET /api/logs?n=50
  → returns { logs: string[] }  (last 50 lines)
```

---

## Dual-SIM support

On startup, `GsmDialer.getSimAccounts()` queries `TelecomManager.callCapablePhoneAccounts`
and sends the list to the backend via `CommandWebSocketClient.sendEvent("SIM_LIST", ...)`.

The server stores SIMs per device and returns them in `/api/gsm/status`.
The frontend (`useInitGSM.ts`) auto-selects the first SIM and exposes `simsRef`,
`selectedSimRef`, `selectSim()` for a SIM picker UI.

When `call()` is invoked, `simAccountId` and `simComponentName` are sent to the backend
and forwarded to Android, which passes the correct `PhoneAccountHandle` to `placeCall()`.

---

## Known issues & decisions

**REMOTE_SUBMIX silence**
GSM audio on sdm660 never enters AudioFlinger during a call. REMOTE_SUBMIX consistently
returns RMS ~0.000044 (silence). Only tinycap via ALSA MultiMedia1 works.

**Wrong tinymix control (fixed)**
`Incall_Music Audio Mixer MultiMedia1` was tried first for downlink — it injects MM1 playback
INTO the call uplink TX, not the reverse. The correct downlink control is `MultiMedia1 Mixer VOC_REC_DL`.
`Incall_Music Audio Mixer` IS the correct uplink injection control (browser mic → remote party).

**tinymix broken on MIUI sdm660 for 2-slot BOOL controls (fixed)**
`Incall_Music Audio Mixer` and `VOC_REC_DL` are 2-slot controls (slot0=VoiceMMode1, slot1=VoiceMMode2).
MIUI's tinyalsa `tinymix` calls `mixer_ctl_get_array` before writing, which fails on these controls
with "Failed to mixer_ctl_get_array". Result: slot 1 (VoiceMMode2) is never set — always stays Off.
Since the active call uses VoiceMMode2, neither capture nor injection worked.
Fixed: native `set_mixer_ctl` binary in `assets/` uses `SNDRV_CTL_IOCTL_ELEM_WRITE` directly,
setting each slot by element index without any read-first step. Compiled with NDK r27 against
`sound/asound.h` from NDK sysroot. GsmService unpacks it to `filesDir` on first run.

**AudioManager mode conflict (fixed)**
`startAudioCapture()` was setting `MODE_IN_COMMUNICATION + speakerphoneOn=false`,
overriding GsmService's `MODE_IN_CALL + speakerphoneOn=true` set just before capture.
Fixed: AudioManager mode changes in AudioWebSocketHandler are gated on `!isCallActive`.

**STREAM_VOICE_CALL volume=0 silences capture (fixed)**
REMOTE_SUBMIX taps the post-volume mixer output. Volume=0 yields silence even with the
correct mixer path open. Fixed: set STREAM_VOICE_CALL to max before starting capture.

**AudioContext sample rate mismatch (fixed)**
Browser default AudioContext is 48kHz. `createBuffer(..., 16000)` inside 48kHz context
triggers browser 3x upsampler → robotic/crackling audio. Fixed: `AudioContext({ sampleRate: 16000 })`.

**WS onclose setting isReady=false (fixed)**
`ws.onclose` was calling `setIsReady(false)` on every reconnect (alt+tab, etc.).
Fixed: removed it. The 2s status poll is the only authoritative source for device readiness.

**Audio replay after call ends (fixed)**
`onCallEnded` was not calling `stopPlayoutLoop()`. Buffer kept draining after hangup.
Fixed: `stopPlayoutLoop()` + `resetInboundAudioState()` called in `onCallEnded`.

**Pusher quota (fixed)**
Two heartbeats firing every 15s via Pusher (11,520+ Pusher messages/day from heartbeats alone).
Fixed: Android no longer uses Pusher at all. `CommandWebSocketClient` handles all
Android↔backend communication. Pusher is now used only for browser-side call state
events (~6 messages per call).

**SIGABRT in DefaultDispatcher**
Was caused by launching a new coroutine per audio chunk at 50/sec.
Fixed: playback uses `Channel<ShortArray>(capacity=64)` with a single consumer coroutine.
Do NOT revert to `scope.launch` per chunk.

**INSTALL_PARSE_FAILED**
Always use `./gradlew clean assembleDebug`. Do not put inline XML comments after
self-closing tags in `AndroidManifest.xml` — breaks Android 10 manifest parser.

**Log spam from audio loop**
Never call `MainActivity.log()` from the capture/playback loop. Use `Log.d()` only.
`MainActivity.log()` posts to UI thread at 50/sec which locks the ScrollView.

**Phone showing wrong status (fixed)**
`requestPermissionsOnLaunchIfNeeded` was auto-dispatching SERVICE start on every launch,
causing phone to always show "Service Active" regardless of frontend state.
Fixed: removed auto-dispatch. GsmService process starts (for WS cmd) but no audio mode
is activated until frontend sends START_SERVICE.

**SERVICE_STARTED event dropped silently (fixed)**
`cmdWsClient?.sendEvent()` returns early when `!isConnected`. WS not yet open when
service starts. Fixed: retry loop (10s deadline, 300ms poll) waits for `isConnected`.

**connectedDevices dropping isServiceActive on reconnect (fixed)**
Device entry was reconstructed with only `{ deviceToken, lastSeen, sims }` on reconnect,
dropping `isServiceActive`/`isTestActive`. Fixed: spread existing entry first.

**Kotlin trailing lambda bug (fixed)**
`WebSocketAudioClient(... , { packet -> handleAudioPacket(packet) })` — trailing lambda
attaches to the LAST parameter, which was `onCommand`, not `onAudioPacket`.
Fixed: use named argument: `onAudioPacket = { packet -> handleAudioPacket(packet) }`.

---

## WebSocket message schemas

### /ws/audio (audio relay)
```json
{
  "role": "browser | android",
  "deviceToken": "Xiaomi_2109119DG",
  "dir": "toAndroid | toBrowser",
  "codec": "pcm16",
  "seq": 42,
  "ts": 1234567890,
  "sampleRate": 16000,
  "audio": "<base64 PCM16 little-endian>"
}
```

### /ws/cmd (command channel, Android only)

Android → server:
```json
{ "type": "event", "eventType": "CONNECTED|SERVICE_STARTED|SERVICE_STOPPED|CALL_CONNECTED|CALL_ENDED|...", "deviceToken": "...", "data": { "isServiceActive": true, "isTestActive": false } }
```

Server → Android:
```json
{ "type": "command", "cmdType": "CALL_STARTED|CALL_ENDED|SEND_DTMF|START_SERVICE|STOP_SERVICE|START_TEST|STOP_TEST|SET_GAIN", "data": { "number": "...", "simAccountId": "...", "micGain": 1.0 } }
```

## Audio format

```
Encoding   : PCM 16-bit little-endian
Sample rate: 16kHz
Channels   : Mono
Transport  : Base64 inside WebSocket JSON frames
Chunk size : 320 samples (20ms)
```

---

## Native binaries

```
native/set_mixer_ctl/
  set_mixer_ctl.c      Source. Uses sound/asound.h SNDRV_CTL_IOCTL_ELEM_WRITE directly.
  build_arm64.sh       NDK r27 build script. Output → app/src/main/assets/set_mixer_ctl.
                       Run: NDK=/path/to/ndk ./native/set_mixer_ctl/build_arm64.sh
app/src/main/assets/
  set_mixer_ctl        Pre-built arm64 PIE ELF. Bundled in APK. GsmService unpacks to
                       filesDir on first run and sets RootUtils.nativeBinDir.
```

Usage (manual test):
```bash
adb shell "su -c '/data/data/com.nicitaacom.androidgsm/files/set_mixer_ctl 0 \"Incall_Music Audio Mixer MultiMedia1\" 1 1'"
# Expected: OK: 'Incall_Music Audio Mixer MultiMedia1'[1] = 1  (numid=1690 count=2)
```

---

## Docs (frontend integration)

```
docs/realtime-audio/
  server.ts              Express + WebSocket server (VPS). Handles /ws/audio, /ws/cmd, Pusher triggers.
                         Circular log buffer (200 lines). GET /api/logs, POST /api/commands.
  useInitGSM.ts          React hook: device discovery, Pusher events, WebSocket audio,
                         call/hangup/DTMF, SIM picker, service/test control, gain, logs.
  nextjs-api-routes/
    call-started.ts      POST /api/gsm/call-started → sends CALL_STARTED command over /ws/cmd
    call-ended.ts        POST /api/gsm/call-ended   → sends CALL_ENDED command over /ws/cmd
    send-dtmf.ts         POST /api/gsm/send-dtmf    → sends SEND_DTMF command over /ws/cmd
    status.ts            GET  /api/gsm/status        → polls device liveness, sims[], isServiceActive, isTestActive
    commands.ts          POST /api/gsm/commands      → proxies START_SERVICE/STOP_SERVICE/START_TEST/STOP_TEST/SET_GAIN
    logs.ts              GET  /api/gsm/logs           → proxies last 50 server log lines
```

---

## AI instructions

- Do not create doc files
- Update only the changed parts of code, not full files
- Reply concisely
- Always use `./gradlew clean assembleDebug` for builds targeting a device
- All `MainActivity.log()` calls go to logcat tag `GSM` — use `adb logcat -s GSM:D` to debug
- High-frequency events (audio chunks) must use `Log.d()` not `MainActivity.log()` — the latter posts to UI thread
- Primary audio path: `AudioWebSocketHandler` via `/ws/audio`. `AudioStreamHandler` is legacy — do not use.
- Android commands come via `CommandWebSocketClient` over `/ws/cmd`, not Pusher
- `callEndedCallback` is set once in `GsmService.onCreate` — do not override it in `handleCallStarted`
- Playback channel (`Channel<ShortArray>`) must remain single-consumer — no `scope.launch` per chunk
- AudioManager mode changes in `AudioWebSocketHandler` must be gated on `!isCallActive`
- tinycap WAV header is 44 bytes — always skip before reading PCM
- Never call `tinymix` for 2-slot BOOL controls — use `RootUtils.setMixerElem()` (calls set_mixer_ctl)
- To rebuild set_mixer_ctl: `NDK=/home/kali/android-sdk/ndk/27.2.12479018 ./native/set_mixer_ctl/build_arm64.sh`
- set_mixer_ctl is unpacked from assets by GsmService.onCreate() — do not hardcode /data/local/tmp paths
- SERVICE/TEST audio modes are started only by frontend commands — never auto-start on launch
- `SERVICE_STARTED` event uses retry loop (10s, 300ms poll) — do not remove it
- `stateProvider` lambda in `CommandWebSocketClient` carries live `isServiceActive`/`isTestActive`
- Trailing lambda in Kotlin attaches to the LAST parameter — always use named args for non-last lambdas
