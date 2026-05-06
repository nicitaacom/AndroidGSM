# Android GSM Gateway

Android foreground service that exposes **real GSM calls** to a browser via
**WebSocket** (audio + commands) + **Pusher** (browser-side call state events only).

Phone = modem. Backend = brain. Pusher = doorbell for the browser only.

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
  && adb install -r $(ls -t app/build/outputs/apk/debug/*.apk | head -n1) \
  && adb shell am start -n com.nicitaacom.androidgsm/.MainActivity

# Live logs
adb logcat -s GSM:D AudioWebSocket:D WebSocketAudio:D CmdWS:D RootUtils:D
```

## In-app setup (first run)

1. Grant all permissions when prompted
2. SERVICE starts automatically — the phone shows status and logs only
3. Use the website to make/end calls

---

## Architecture

### Pusher usage (minimal — browser only)

Pusher is used **only** to push call state events to the browser (5–10 messages per call).
Android does NOT subscribe to Pusher. All Android↔backend communication goes over WebSocket.

| What | Where | Cost |
|------|-------|------|
| Call state (started/connected/ended) | Pusher → browser | ~6 msgs/call |
| Device ready state | Pusher → browser | 1 msg/connect (debounced 5min) |
| Android commands (CALL_STARTED, CALL_ENDED) | `/ws/cmd` WebSocket | 0 Pusher msgs |
| Android heartbeats | `/ws/cmd` WebSocket | 0 Pusher msgs |
| Audio | `/ws/audio` WebSocket | 0 Pusher msgs |

### System diagram

```
┌──────────────────────────────────┐
│        Next.js Frontend          │
│  useInitGSM hook                 │
│  ┌──────────────────────────┐    │
│  │ call() / hungUp()        │ ── POST /api/gsm/call-started|ended ──────────────┐
│  │ sendDTMF()               │ ── POST /api/gsm/send-dtmf ────────────────────┐  │
│  │ mic audio (PCM16)        │ ── WS /ws/audio (dir=toAndroid) ─────────────┐ │  │
│  │ speaker playback ◄───────│─── WS /ws/audio (dir=toBrowser) ◄──────────┐ │ │  │
│  └──────────────────────────┘    │                                        │ │ │  │
│  Pusher subscriptions:           │                                        │ │ │  │
│  gsm-calls ◄─────────────────────┤─ call-started/connected/ended         │ │ │  │
│  gsm-devices ◄───────────────────┤─ device-connected                     │ │ │  │
└──────────────────────────────────┘                                        │ │ │  │
                                                                            │ │ │  │
                         ┌──────────────────────────────────────────────────┘ │ │  │
                         │   server.ts (VPS / Express)                         │ │  │
                         │  WS /ws/cmd  ◄──── Android persistent cmd WS ──────┐│ │  │
                         │    heartbeat → update lastSeen (no Pusher)          ││ │  │
                         │    events → forward to browser via Pusher as needed ││ │  │
                         │    commands → deliver over this WS                  ││ │  │
                         │  WS /ws/audio ◄──── Android audio WS ──────────────┘│ │  │
                         │    relay browser ↔ android PCM16 audio              │ │  │
                         │  POST /api/gsm/call-started ◄────────────────────────┘ │  │
                         │    → send command CALL_STARTED over /ws/cmd            │  │
                         │  POST /api/gsm/call-ended ◄──────────────────────────── │  │
                         │    → send command CALL_ENDED over /ws/cmd              │  │
                         │  POST /api/gsm/send-dtmf ◄────────────────────────────┘  │
                         │    → send command SEND_DTMF over /ws/cmd                 │
                         │  GET /api/gsm/status ◄──────────────────────────────────┘
                         │    → returns { isAuthorized, deviceToken, sims[] }
                         └──────────────────────────────────────────────────────────
                                              ↕ WS /ws/cmd  (persistent)
                                      ┌──────────────────────┐
                                      │   Android App        │
                                      │                      │
                                      │  GsmService          │
                                      │  GsmDialer           │
                                      │  CommandWebSocketClient (replaces PusherClient)
                                      │  AudioWebSocketHandler
                                      └──────────────────────┘
```

### Android components

```
GsmService              Foreground service; owns all state, dispatches commands.
                        Auto-starts SERVICE mode on launch after permissions.
                        Does NOT use PusherClient — all backend comms via WS.

CommandWebSocketClient  Persistent WS to /ws/cmd. Stays connected always.
                        Receives CALL_STARTED/CALL_ENDED/SEND_DTMF commands.
                        Sends CONNECTED heartbeats every 15s (no Pusher cost).
                        Sends CALL_CONNECTED/CALL_ENDED/SIM_LIST events to backend.
                        Auto-reconnects on failure.

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
                        enableIncallMusicCapture(): tinymix 'MultiMedia1 Mixer VOC_REC_DL' 1
                        disableIncallMusicCapture(): resets to 0.

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

### The solution: tinycap as root subprocess

The GSM downlink audio IS available at the ALSA kernel level via a Qualcomm mixer route.

**Step 1 — Open the mixer route:**
```
tinymix 'MultiMedia1 Mixer VOC_REC_DL' 1
```
`VOC_REC_DL` = Voice Call Record DownLink. This taps the GSM call's receive path
into MultiMedia1's ALSA capture device at the kernel level.

**Step 2 — Capture raw PCM:**
```
tinycap /proc/self/fd/1 -D 0 -d 0 -c 1 -r 16000 -b 16
```
Runs as a root subprocess. Reads from ALSA card 0 device 0 (MultiMedia1).
Writes a 44-byte WAV header then raw PCM16 mono 16kHz to stdout.
The app reads stdout, skips the WAV header, and streams 320-sample (20ms) chunks.

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
1. Phone opens app → auto-starts SERVICE → CommandWebSocketClient connects to /ws/cmd

2. Frontend call() → POST /api/gsm/call-started { num, deviceToken, simAccountId, simComponentName }
   → server sends { type:"command", cmdType:"CALL_STARTED", data:{number, simAccountId} } over /ws/cmd
   → GsmService.handleCommand("CALL_STARTED")
   → RootUtils.enableIncallMusicCapture()  (opens VOC_REC_DL mixer)
   → GsmDialer.startCall(number, simAccountId, simComponentName)
   → TelecomManager.placeCall() with correct PhoneAccountHandle (dual-SIM aware)
   → Pusher: gsm:call-started → browser (shows "dialing" state)

3. Carrier connects → TelephonyCallback fires OFFHOOK
   → GsmService sets MODE_IN_CALL + speakerphoneOn=true + STREAM_VOICE_CALL=max
   → AudioWebSocketHandler.connect() → /ws/audio
   → startAudioCapture() → launches tinycap subprocess as root
   → startAudioPlayback() → AudioTrack ready
   → CommandWebSocketClient.sendEvent("CALL_CONNECTED")
   → server: Pusher gsm:call-connected → browser (starts audio playout)

4. Audio flows:
   tinycap → raw PCM16 → base64 → /ws/audio → server → browser AudioContext
   Browser mic → PCM16 → /ws/audio → server → Android AudioTrack

5. Call ends (either side):
   → TelephonyCallback fires IDLE
   → RootUtils.disableIncallMusicCapture()
   → AudioWebSocketHandler stops + disconnects
   → CommandWebSocketClient.sendEvent("CALL_ENDED")
   → server: Pusher gsm:call-ended → browser (stops playout)
```

## TEST mode flow

```
User taps TEST AUDIO (or website triggers it):
  → AudioWebSocketHandler (callActive=false) → /ws/audio
  → startAudioPlayback() + startAudioCapture() (uses mic, not tinycap)
  → CommandWebSocketClient.sendEvent("TEST_AUDIO_STARTED")
    → server: Pusher gsm:test-audio-started → browser
      → browser starts mic capture + audio playout
        → bidirectional PCM16 audio over /ws/audio
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
`Incall_Music Audio Mixer MultiMedia1` was tried first — it injects MM1 playback INTO the
call uplink, not the reverse. The correct control is `MultiMedia1 Mixer VOC_REC_DL`.

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
{ "type": "event", "eventType": "CONNECTED|CALL_CONNECTED|CALL_ENDED|...", "deviceToken": "...", "data": {} }
```

Server → Android:
```json
{ "type": "command", "cmdType": "CALL_STARTED|CALL_ENDED|SEND_DTMF", "data": { "number": "...", "simAccountId": "..." } }
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

## Docs (frontend integration)

```
docs/realtime-audio/
  server.ts              Express + WebSocket server (VPS). Handles /ws/audio, /ws/cmd, Pusher triggers.
  useInitGSM.ts          React hook: device discovery, Pusher events, WebSocket audio, call/hangup/DTMF, SIM picker
  nextjs-api-routes/
    call-started.ts      POST /api/gsm/call-started → sends CALL_STARTED command over /ws/cmd
    call-ended.ts        POST /api/gsm/call-ended   → sends CALL_ENDED command over /ws/cmd
    send-dtmf.ts         POST /api/gsm/send-dtmf    → sends SEND_DTMF command over /ws/cmd
    status.ts            GET  /api/gsm/status        → polls device liveness + returns sims[]
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
