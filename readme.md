# Android GSM Gateway

Android foreground service that exposes **real GSM calls** to a backend via
**Pusher** (commands) + **WebSocket** (audio).

Phone = modem. Backend = brain.

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

# Live logs (all MainActivity.log() calls go to tag GSM)
adb logcat -s GSM:D AudioWebSocket:D WebSocketAudio:D RootUtils:D GsmDialer:E AudioStreamHandler:E
```

## In-app setup (first run)

1. Grant all permissions when prompted
2. Tap **SET AS DEFAULT DIALER** → confirm (required for call routing)
3. Tap **START SERVICE** to connect to backend

---

## Requirements

- **Root** — required for `CAPTURE_AUDIO_OUTPUT` / `REMOTE_SUBMIX` to capture call audio output.
  Without root the app falls back to mic capture (your voice only, not the other person).
- **Default dialer** — must be set so `TelecomManager.placeCall()` routes through `GsmConnectionService`
  instead of opening the system dialer UI.
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
│  │ mic audio (PCM16)   │ ── WebSocket /ws/audio (dir=toAndroid) ─┐ │    │   │
│  │ speaker playback    │ ◄─ WebSocket /ws/audio (dir=toBrowser) ┐│ │    │   │
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
                         │    AUDIO_CHUNK / DTMF_SENT / DISCONNECTED           │ AudioWS      │
                         │    → pusher.trigger gsm-calls events                │ Handler      │
                         │  WebSocket /ws/audio                                └──────────────┘
                         │    relay browser ↔ android audio bidirectionally
                         └─────────────────────────────────────────────────────────────────────
```

### Android components

```
GsmService            foreground service; owns all state, dispatches commands from Pusher
GsmDialer             registers PhoneAccount, places/ends GSM calls via TelecomManager,
                      listens to TelephonyCallback (OFFHOOK/IDLE) to trigger audio start/stop
GsmConnectionService  ConnectionService (Telecom framework); handles placeCall() routing
                      so the system dialer UI never opens
GsmConnection         Connection lifecycle: answer, disconnect, hold/unhold
AudioWebSocketHandler WebSocket duplex: captures audio (REMOTE_SUBMIX or MIC fallback),
                      plays inbound audio via AudioTrack; coroutine-based, crash-safe
PusherClient          subscribes to private-device-{token} channel, receives commands,
                      sends events back via POST /api/events
RootUtils             checks root via su -c id (3s timeout to avoid blocking),
                      grants CAPTURE_AUDIO_OUTPUT via pm grant if rooted
ConfigReader          reads androidgsm.config.json from assets, validates fields,
                      pings BACKEND_URL/health once on startup
AppContextHolder      singleton holding applicationContext for use in Connection callbacks
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
            → GsmDialer.onCallConnected callback
              → AudioWebSocketHandler.connect() + startCapture() + startPlayback()
              → PusherClient.sendEvent("CALL_CONNECTED")
                → server.ts: pusher.trigger gsm:call-connected → frontend

Frontend hungUp() → POST /api/gsm/call-ended
  → server.ts: pusher.trigger CALL_END
    → GsmService.handleCommand("CALL_ENDED")
      → GsmDialer.endCall() via TelecomManager
        → TelephonyCallback fires IDLE
          → GsmDialer.onCallEnded callback
            → AudioWebSocketHandler stop + disconnect
            → PusherClient.sendEvent("CALL_ENDED")
```

### TEST mode flow

```
User taps START TEST in app
  → GsmService starts AudioWebSocketHandler (callActive=false)
  → connects WebSocket /ws/audio
  → startAudioCapture() — phone mic → WebSocket → website audio output
  → startAudioPlayback() — website mic → WebSocket → phone audio output
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
  Phone mic           ──► high-pass filter + noise gate ──► WebSocket ──► Website audio output
  Website mic         ──► WebSocket ──► Phone audio output
  No SIM or GSM call needed — used to verify duplex audio works end-to-end.

SERVICE (START SERVICE → STOP SERVICE):
  Phone audio output  ──► REMOTE_SUBMIX capture ──► WebSocket ──► Website audio output
  Website mic         ──► WebSocket ──► Phone audio output (injected into GSM call)
  Requires root + CAPTURE_AUDIO_OUTPUT for REMOTE_SUBMIX.
  Falls back to Phone mic if REMOTE_SUBMIX fails.
```

## Capture-side audio processing (Phone mic → WebSocket)

Applied in `AudioWebSocketHandler.captureAndStreamAudio()` before each chunk is sent:

```
1. High-pass filter (IIR, ~80Hz cutoff) — removes low-frequency rumble and hum
2. Noise gate (RMS threshold ~-42 dBFS) — drops chunk entirely if too quiet (silence, background hiss)
```

Inbound audio (Website mic → Phone audio output) is not filtered — it is controlled audio from the backend/AI.

---

## Button logic

```
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
AUDIO_CHUNK      { audio: "<base64>", seq: 42, sampleRate: 16000, codec: "pcm16" }
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
Sample rate: 16 kHz
Channels  : Mono
Transport : Base64 inside WebSocket JSON frames
Chunk rate: ~50/sec
```

---

## Known issues & decisions

- **REMOTE_SUBMIX false negatives**: root check can fail on some ROMs even when rooted.
  The app always attempts REMOTE_SUBMIX first and falls back to MIC on failure regardless of root check result.
- **System dialer opening**: fixed by registering a `PhoneAccount` with `CAPABILITY_CALL_PROVIDER`
  pointing at `GsmConnectionService`. App must be set as default dialer for this to work.
- **INSTALL_PARSE_FAILED**: always use `./gradlew clean assembleDebug`, not incremental builds,
  when installing on device. Cached manifest from incremental builds breaks Android 10 parser.
- **SubscriptionManager empty on boot**: fixed with `TelephonyManager.simState` fallback,
  re-checked on every `onResume`.
- **AudioTrack crash in coroutines**: `scope.coroutineContext[Job]!!` NPEs after scope recreation.
  Fixed with safe `?.isActive != true` check and catching `Throwable` (not `Exception`) so
  `CancellationException` doesn't propagate as a fatal crash.

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
    send-audio-chunk.ts  (legacy fallback, primary path is WebSocket)
```

---

## AI instructions

- Do not create doc files
- Update only the changed parts of code, not full files
- Reply concisely
- Always use `./gradlew clean assembleDebug` for builds targeting a device
- Config validation and backend ping happen in `ConfigReader.readConfig()` — touch that if changing startup behavior
- All `MainActivity.log()` calls go to logcat tag `GSM` — use `adb logcat -s GSM:D` to debug
