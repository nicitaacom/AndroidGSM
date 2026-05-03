# Android GSM Gateway

Android foreground service that exposes **real GSM calls** to a backend via
**Pusher** (commands + signaling) + **WebSocket** (audio).

Phone = modem. Backend = brain.

The architecture is a Rube Goldberg machine wearing a suit

---

## First-time setup (Kali / no Android SDK)

```bash
# 1. Install Android SDK (~2GB)
chmod +x install-android-sdk.sh
./install-android-sdk.sh

# 2. Reload shell env
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
  "PUSHER_APP_ID": "your-app-id",
  "PUSHER_KEY": "your-pusher-key",
  "PUSHER_SECRET": "your-pusher-secret",
  "PUSHER_CLUSTER": "eu"
}
```

> `androidgsm.config.json` is gitignored — never committed.
> `DEVICE_TOKEN` is auto-generated from `{manufacturer}_{model}` at runtime — do not set it manually.
> On startup the app validates all fields and pings `BACKEND_URL/health` once to confirm reachability.

## Build & run on phone

```bash
# On the phone:
#   Settings → Developer options → USB debugging ON
#   Settings → Developer options → Install via USB ON
# Plug in, tap Allow on the trust prompt

adb devices   # must show device, not unauthorized

# Always use clean to avoid INSTALL_PARSE_FAILED
adb shell am force-stop com.nicitaacom.androidgsm \
  ; ./gradlew clean assembleDebug \
  && adb install -r $(ls -t app/build/outputs/apk/debug/*.apk | head -n1) \
  && adb shell am start -n com.nicitaacom.androidgsm/.MainActivity

# Live logs
adb logcat -s GSM:D AudioWebSocket:D WebSocketAudio:D RootUtils:D GsmDialer:E
```

## In-app setup (first run)

1. Grant all permissions when prompted
2. Tap **SET DIALER** → confirm (required for call routing through GsmConnectionService)
3. Tap **START SERVICE** to connect to backend and wait for calls

---

## Requirements

- **Root** — required for `CAPTURE_AUDIO_OUTPUT` / `REMOTE_SUBMIX` to capture call audio output.
  Without root the app falls back to mic capture (your voice only, not the other person).
- **Default dialer** — must be set so `TelecomManager.placeCall()` routes through `GsmConnectionService`.
  The system in-call UI will still appear during calls — this is normal Android behavior, not a bug.
- **Android 10+** (API 29) for SERVICE mode. TEST mode works on Android 6+.
- **Config file** — `androidgsm.config.json` must be present in assets at build time.
  Missing or placeholder values (`xxxx`, `your-*`) are caught on startup with a clear error log.

---

## How it works

### Full system

```
┌─────────────────────────────┐
│      Next.js Frontend       │
│  useInitGSM hook            │
│  ┌─────────────────────┐    │
│  │ call()              │ ── POST /api/gsm/call-started ─────────────────────┐
│  │ hungUp()            │ ── POST /api/gsm/call-ended  ──────────────────┐   │
│  │ sendDTMF()          │ ── POST /api/gsm/send-dtmf   ─────────────┐    │   │
│  │ mic audio (PCM16)   │ ── WebSocket /ws/audio (dir=toAndroid) ──┐ │    │   │
│  │ speaker playback    │ ◄─ WebSocket /ws/audio (dir=toBrowser) ─┐│ │    │   │
│  └─────────────────────┘    │                                    ││ │    │   │
│  Pusher subscriptions:      │                                    ││ │    │   │
│  gsm-devices ◄──────────────┤── gsm:device-connected            ││ │    │   │
│  gsm-calls   ◄──────────────┤── gsm:call-started/connected/ended││ │    │   │
└─────────────────────────────┘   gsm:test-audio-started/stopped  ││ │    │   │
                                                                   ││ │    │   │
                         ┌─────────────────────────────────────────┘│ │    │   │
                         │   server.ts (VPS / Express)               │ │    │   │
                         │  POST /api/gsm/call-started  ◄────────────┘ │    └───► Pusher
                         │    → pusher.trigger CALL_START               │        private-device-{token}
                         │  POST /api/gsm/call-ended    ◄───────────────┘            ↓
                         │    → pusher.trigger CALL_END                       ┌──────────────┐
                         │  POST /api/gsm/send-dtmf                           │ Android App  │
                         │    → pusher.trigger SEND_DTMF                      │              │
                         │  POST /api/events  ◄── Android HTTP ───────────────│ GsmService   │
                         │    CONNECTED / CALL_CONNECTED / CALL_ENDED /        │ GsmDialer    │
                         │    DTMF_SENT / DISCONNECTED                         │ AudioWS      │
                         │    → pusher.trigger gsm-calls events                │ Handler      │
                         │  WebSocket /ws/audio                                └──────────────┘
                         │    relay browser ↔ android audio bidirectionally
                         └─────────────────────────────────────────────────────────────────────
```

### Android components

```
GsmService            foreground service; owns all state, dispatches Pusher commands.
                      Sets callEndedCallback on GsmDialer at init (not per-call) so
                      manual hang-up always sends CALL_ENDED to backend.

GsmDialer             registers PhoneAccount (CAPABILITY_CALL_PROVIDER) pointing at
                      GsmConnectionService. Places calls via TelecomManager.placeCall()
                      with explicit PhoneAccountHandle — prevents system dialer UI opening.
                      Listens to TelephonyCallback (API 31+) or PhoneStateListener (legacy)
                      for OFFHOOK/IDLE to trigger audio start/stop.

GsmConnectionService  ConnectionService (Telecom framework); handles placeCall() routing.
                      onCreateOutgoingConnection sets DIALING state only — does NOT
                      start audio or send CALL_CONNECTED (that happens on OFFHOOK).

GsmConnection         Connection lifecycle: answer, disconnect, hold/unhold.
                      onDisconnect broadcasts ACTION_CALL_DISCONNECTED_BROADCAST.

AudioWebSocketHandler WebSocket duplex audio.
                      CRITICAL: playback uses a Channel<ShortArray> with a single consumer
                      coroutine — do NOT revert to scope.launch per chunk (causes SIGABRT).
                      Noise gate is disabled — threshold was cutting speech on this device.
                      micGain and playbackGain are companion object @Volatile floats set
                      live from the UI sliders in MainActivity.

WebSocketAudioClient  OkHttp WebSocket; sends registration packet on open so server knows
                      this is the android peer and can relay browser→android audio.

PusherClient          Subscribes to private-device-{token}, receives commands.
                      AUDIO_CHUNK via Pusher is the fallback path — logs to logcat only,
                      NOT to MainActivity.log (would flood the UI at 50/sec).
                      All per-chunk or high-frequency events must use Log.d, not MainActivity.log.

AudioStreamHandler    LEGACY class — kept for DTMF/Pusher audio fallback path only.
                      playAudioChunk() does NOT auto-start playback (removed — was causing
                      max-volume speaker blast on service start).
                      Do NOT call startAudioCapture/startAudioPlayback from here in SERVICE mode.

RootUtils             su -c id with 3s timeout + stream-draining threads to avoid deadlock.
                      isRooted() is called off the main thread in MainActivity.onCreate.

ConfigReader          Reads androidgsm.config.json, validates no placeholder values,
                      pings BACKEND_URL/health on background thread.

AppContextHolder      Singleton holding applicationContext for use in Connection callbacks.
```

### Call flow (SERVICE mode)

```
Frontend call() → POST /api/gsm/call-started
  → server.ts: pusher.trigger CALL_START
    → Android PusherClient receives command
      → GsmService.handleCommand("CALL_STARTED")
        → GsmDialer.startCall(number)
          → TelecomManager.placeCall() using GsmConnectionService PhoneAccountHandle
            → GsmConnectionService.onCreateOutgoingConnection()
              → GsmConnection set to DIALING state (no audio yet)
          → TelephonyCallback fires OFFHOOK when carrier connects
            → GsmDialer.onCallConnected callback (set in handleCallStarted)
              → AudioWebSocketHandler.connect() + startAudioCapture() + startAudioPlayback()
              → PusherClient.sendEvent("CALL_CONNECTED")

User or website hangs up:
  Website → POST /api/gsm/call-ended
    → pusher.trigger CALL_END → GsmDialer.endCall() → TelecomManager.endCall()
  Phone hang-up → GsmConnection.onDisconnect() → broadcasts ACTION_CALL_DISCONNECTED_BROADCAST
  Either path → TelephonyCallback fires IDLE
    → GsmDialer.onCallEnded callback (set once in GsmService.onCreate, always active)
      → AudioWebSocketHandler stop + disconnect
      → PusherClient.sendEvent("CALL_ENDED")
```

### TEST mode flow

```
User taps START TEST in app
  → GsmService starts AudioWebSocketHandler (callActive=false)
  → connects WebSocket /ws/audio
  → startAudioPlayback() + startAudioCapture()
  → PusherClient.sendEvent("TEST_AUDIO_STARTED")
    → server.ts: pusher.trigger gsm:test-audio-started → frontend
      → frontend useInitGSM starts website mic capture + playout loop
        → bidirectional PCM16 audio over WebSocket
```

---

## Terminology

```
Phone mic           — phone's microphone input
Phone audio output  — phone's audio output (earpiece, loudspeaker, bluetooth, wired — whatever is active)
Website mic         — browser's microphone input
Website audio output— browser's audio output (speakers, headphones)
```

## Audio modes

```
TEST  (START TEST → STOP TEST):
  Phone mic           ──► high-pass filter ──► WebSocket ──► Website audio output
  Website mic         ──► WebSocket ──► Phone audio output
  No SIM or GSM call needed — used to verify duplex audio works end-to-end.

SERVICE (START SERVICE → STOP SERVICE):
  Connects to backend and waits for CALL_STARTED command. NO audio starts on button press.
  Audio only starts when TelephonyCallback fires OFFHOOK (carrier connected):
  Phone audio output  ──► REMOTE_SUBMIX capture ──► WebSocket ──► Website audio output
  Website mic         ──► WebSocket ──► Phone audio output (injected into GSM call)
  Requires root + CAPTURE_AUDIO_OUTPUT for REMOTE_SUBMIX.
  Falls back to Phone mic if REMOTE_SUBMIX fails.
```

## Capture-side audio processing

Applied in `AudioWebSocketHandler.captureAndStreamAudio()`:

```
1. High-pass filter (IIR, ~80Hz cutoff) — removes low-frequency rumble and hum
2. Noise gate — DISABLED (threshold was cutting speech; re-enable with caution)
3. Mic gain — applied from UI slider (default 1.0x, range 0.0–2.0x)
```

Playback gain is applied in `playAudioChunk()` from the UI slider (default 0.7x, range 0.0–2.0x).
Both gain values are stored in SharedPreferences and applied live without restart.

---

## UI controls

```
MIC GAIN slider      — scales captured audio before sending (0.0–2.0x, default 1.0x)
PLAYBACK VOL slider  — scales received audio before writing to AudioTrack (0.0–2.0x, default 0.7x)
COPY LOGS button     — copies full raw log buffer to clipboard (paste into chat for debugging)
STOP TEST showing    → START SERVICE disabled (only one mode at a time)
STOP SERVICE showing → START TEST disabled
No SIM detected      → START SERVICE disabled
No internet          → all controls disabled
Missing permissions  → all controls disabled
Android < 10         → SERVICE disabled, TEST still works
```

SIM detection: uses `SubscriptionManager.activeSubscriptionInfoList` with fallback to
`TelephonyManager.simState == SIM_STATE_READY` (fixes false negatives on Android 10 during early startup).

---

## Pusher commands (Backend → Android)

Channel: `private-device-{DEVICE_TOKEN}`

```
CALL_START   { "number": "+1234567890" }
CALL_END     {}
SEND_DTMF    { "digit": "5" }
AUDIO_CHUNK  { "audio": "<base64_pcm_16khz>" }   ← fallback only, primary path is WebSocket
```

## HTTP events (Android → Backend)

```
POST /api/events  { deviceToken, type, data }

CONNECTED
CALL_CONNECTED
CALL_ENDED
DTMF_SENT        { digit: "5" }
DISCONNECTED
TEST_AUDIO_STARTED
TEST_AUDIO_STOPPED
```

## WebSocket message schema

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

## Audio format

```
Encoding  : PCM 16-bit little-endian
Sample rate: 16kHz
Channels  : Mono
Transport : Base64 inside WebSocket JSON frames
Chunk rate: ~50/sec
```

---

## Known issues & decisions

- **REMOTE_SUBMIX false negatives**: root check can fail on some ROMs even when rooted.
  The app always attempts REMOTE_SUBMIX first and falls back to MIC on failure regardless of root check.
- **System in-call UI always shows**: Android always shows the in-call screen for active calls.
  This is not a bug. The system dialer UI (choose dialer prompt) is suppressed by registering a
  PhoneAccount with CAPABILITY_CALL_PROVIDER and setting the app as default dialer.
- **SIGABRT in DefaultDispatcher**: was caused by launching a new coroutine per audio chunk at 50/sec.
  Fixed: playback uses Channel<ShortArray>(capacity=64) with a single consumer coroutine.
  Do NOT revert to scope.launch per chunk.
- **AudioStreamHandler auto-playback blast**: playAudioChunk() previously called startAudioPlayback()
  automatically with isSpeakerphoneOn=true and max volume. This caused dangerously loud audio.
  Fixed: playAudioChunk() returns immediately if not already playing. Never call startAudioPlayback()
  from AudioStreamHandler in SERVICE mode — all audio goes through AudioWebSocketHandler.
- **SERVICE mode audio on button press**: startAudioCapture/Playback must NOT be called on
  START SERVICE. Audio only starts on OFFHOOK (carrier connected). Starting capture immediately
  streams all system sounds (notifications, media) through REMOTE_SUBMIX at full volume.
- **Noise gate cutting speech**: RMS threshold of 0.008f (-42 dBFS) was too aggressive and gated
  normal speech on this device. Currently disabled. If re-enabling, start at 0.001f and test.
- **Log spam from audio loop**: never call MainActivity.log() from the capture/playback loop.
  Use Log.d() only. MainActivity.log() posts to UI thread at 50/sec which locks the ScrollView
  and makes the app appear frozen.
- **INSTALL_PARSE_FAILED**: always use `./gradlew clean assembleDebug`, not incremental builds.
  Also: do not put inline XML comments after self-closing tags in AndroidManifest.xml — breaks
  Android 10 manifest parser.
- **SubscriptionManager empty on boot**: fixed with `TelephonyManager.simState` fallback,
  re-checked on every `onResume`.
- **RootUtils blocking main thread**: isRooted() runs `su -c id` (up to 3s). Must be called
  off the main thread. Currently called in a Thread{} in MainActivity.onCreate.
- **Seq number reuse across sessions**: seqRx carries over between TEST and SERVICE sessions.
  Fixed: seq out-of-order check accepts reset (seq < seqRx by more than 10000 = new session).
- **Phone hang-up detection**: TelephonyCallback IDLE fires on boot/init too — guarded by
  isCallActive flag so CALL_ENDED is only sent if a call was actually active.

---

## Docs (frontend integration)

```
docs/realtime-audio/
  server.ts              Express + WebSocket server running on VPS
  useInitGSM.ts          React hook: device discovery, Pusher events, WebSocket audio, call/hangup/DTMF
  nextjs-api-routes/
    call-started.ts      POST /api/gsm/call-started → triggers CALL_START command
    call-ended.ts        POST /api/gsm/call-ended   → triggers CALL_END command
    send-dtmf.ts         POST /api/gsm/send-dtmf    → triggers SEND_DTMF command
    status.ts            GET  /api/gsm/status        → polls device liveness
```

---

## AI instructions

- Do not create doc files
- Update only the changed parts of code, not full files
- Reply concisely
- Always use `./gradlew clean assembleDebug` for builds targeting a device
- Config validation and backend ping happen in `ConfigReader.readConfig()` — touch that if changing startup behavior
- All `MainActivity.log()` calls go to logcat tag `GSM` — use `adb logcat -s GSM:D` to debug
- High-frequency events (audio chunks, noise gate) must use `Log.d()` not `MainActivity.log()` — the latter posts to UI thread and will lock the ScrollView
- The primary audio path is WebSocket (`AudioWebSocketHandler`). `AudioStreamHandler` is legacy/fallback only — do not route SERVICE mode audio through it
- `callEndedCallback` is set once in `GsmService.onCreate` — do not override it in `handleCallStarted`
- Playback channel (`Channel<ShortArray>`) must remain single-consumer — do not add `scope.launch` calls per chunk in the playback path
