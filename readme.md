# Android GSM Gateway

Android foreground service that exposes **real GSM calls** to a backend via
**Pusher** (commands) + **WebSocket** (audio).

Phone = modem. Backend = brain.

---

## Requirements

- **Root** — required for `CAPTURE_AUDIO_OUTPUT` / `REMOTE_SUBMIX` (captures call audio output).
  Without root, fallback is mic input only.
- **Default dialer** — set via the SET AS DEFAULT DIALER button.
  Required so `placeCall()` routes through `GsmConnectionService` without opening the system dialer UI.
- **Android 10+** (API 29) for SERVICE mode. TEST mode works on lower versions.

---

## Architecture

```
┌──────────────┐   Pusher (commands)   ┌──────────────┐   HTTP POST /api/events   ┌──────────────┐
│   Backend    │ ────────────────────► │ Android App  │ ──────────────────────►   │   Backend    │
│  (server.ts) │                       │ (GsmService) │   status + audio chunks   │  (server.ts) │
│              │ ◄──────────────────── │              │ ◄─────────────────────    │              │
│              │   WebSocket /ws/audio │              │   audio from backend      │              │
└──────────────┘                       └──────────────┘                           └──────────────┘
                                               │
                                     ┌─────────┴─────────┐
                                     │   GsmConnectionService │
                                     │   (replaces system     │
                                     │    dialer - no UI)     │
                                     └────────────────────────┘
```

## Component Map

```
GsmService          — foreground service, command dispatcher, audio lifecycle
GsmDialer           — registers PhoneAccount, places/ends GSM calls, TelephonyCallback
GsmConnectionService— ConnectionService; handles call routing without opening system dialer
GsmConnection       — Connection lifecycle (answer, disconnect, hold)
AudioWebSocketHandler — WebSocket duplex audio: capture (REMOTE_SUBMIX or MIC) + playback
PusherClient        — Pusher private channel subscriber
RootUtils           — root check (su -c id with 3s timeout) + pm grant for CAPTURE_AUDIO_OUTPUT
ConfigReader        — reads androidgsm.config.json from assets
```

## Call Flow (SERVICE mode)

```
Backend → Pusher → CALL_START {number}
  └─► GsmService.handleCommand("CALL_STARTED")
        └─► GsmDialer.startCall(number)
              └─► TelecomManager.placeCall() with GsmConnectionService PhoneAccountHandle
                    └─► GsmConnectionService.onCreateOutgoingConnection()
                          └─► GsmConnection (dialing state, NO broadcast yet)
                    └─► TelephonyCallback: OFFHOOK (carrier connected)
                          └─► GsmDialer.onCallConnected callback
                                └─► AudioWebSocketHandler: startCapture + startPlayback
                                └─► PusherClient: sendEvent("CALL_CONNECTED")
```

## Audio Modes

```
TEST  (START TEST → STOP TEST):
  Android mic  ──► WebSocket /ws/audio ──► browser
  Browser mic  ──► WebSocket /ws/audio ──► Android speaker

SERVICE (START SERVICE → STOP SERVICE):
  Android audio output (REMOTE_SUBMIX) ──► WebSocket ──► browser/AI
  Browser/AI audio                     ──► WebSocket ──► Android audio input (call path)
  (requires root + CAPTURE_AUDIO_OUTPUT)
```

## Button Logic

```
STOP TEST showing    → START SERVICE disabled
STOP SERVICE showing → START TEST disabled
No SIM detected      → START SERVICE disabled
```

---

## Setup

### 1. Config file

Copy `app/src/main/assets/androidgsm.config.example.json` → `androidgsm.config.json`:

```json
{
  "BACKEND_URL": "https://your-backend.com",
  "DEVICE_TOKEN": "android-gateway-01",
  "BACKEND_BEARER": "secret-token",
  "PUSHER_APP_ID": "xxxx",
  "PUSHER_KEY": "xxxx",
  "PUSHER_SECRET": "xxxx",
  "PUSHER_CLUSTER": "eu"
}
```

### 2. Install & grant permissions

```bash
adb devices
adb uninstall com.nicitaacom.androidgsm

# Build, install, launch (one line)
adb shell am force-stop com.nicitaacom.androidgsm && adb uninstall com.nicitaacom.androidgsm ; \
./gradlew assembleDebug && \
adb install $(ls -t app/build/outputs/apk/debug/*.apk | head -n1) && \
adb shell am start -n com.nicitaacom.androidgsm/.MainActivity
```

### 3. In-app setup

1. Grant all permissions when prompted
2. Tap **SET AS DEFAULT DIALER** → confirm
3. Tap **START SERVICE** to connect

---

## Pusher Commands (Backend → Android)

```
CALL_START  { "number": "+1234567890" }
CALL_END    {}
SEND_DTMF   { "digit": "5" }
AUDIO_CHUNK { "audio": "<base64_pcm_16khz>" }
```

Channel: `private-device-{DEVICE_TOKEN}`

## HTTP Events (Android → Backend)

```
POST /api/events

CONNECTED
CALL_CONNECTED
CALL_ENDED
AUDIO_CHUNK   { "audio": "<base64_pcm_16khz>", "seq": 42 }
DTMF_SENT     { "digit": "5" }
DISCONNECTED
TEST_AUDIO_STARTED
TEST_AUDIO_STOPPED
```

## Audio Format

```
Encoding : PCM 16-bit little-endian
Rate     : 16 kHz
Channels : Mono
Transport: Base64 over WebSocket JSON { dir, role, audio, seq }
Rate     : ~50 chunks/sec
```

---

## Important Notes

- Create a separate branch, test for crashes, then merge to production.
- Validate `androidgsm.config.json` exists before shipping — the app logs an error and runs in degraded mode if missing.
- Root check uses `su -c id` with a **3-second timeout** to avoid blocking on devices where su prompts.
- `REMOTE_SUBMIX` capture requires root + `CAPTURE_AUDIO_OUTPUT`. If root check fails but device is actually rooted, the app still attempts REMOTE_SUBMIX and falls back to MIC on failure.
- `placeCall()` routes through `GsmConnectionService` (our registered `PhoneAccount`) — no system dialer UI opens. This requires the app to be set as default dialer.

---

## Docs

See `docs/realtime-audio/` for frontend integration:
- `server.ts` — WebSocket + Pusher server
- `useInitGSM.ts` — React hook for GSM init
- `nextjs-api-routes/` — Next.js API route handlers

## AI / Automation Instructions

- DO NOT create docs files unnecessarily
- Update only the changed parts of code, not full files
- Reply concisely
