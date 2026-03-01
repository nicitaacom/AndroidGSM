# 📞 Android GSM Gateway

Android foreground service that exposes **real GSM calls** to a backend via

**ROOT** - Pusher (commands)** + **HTTP (events + audio)**.

Phone = modem. Backend = brain.

Be a default dialer app (replaces the system dialer entirely)
Use CAPTURE_AUDIO_OUTPUT permission — system-only, requires root or system signing

Without root — you can only capture microphone input (MIC / VOICE_COMMUNICATION), not the phone's audio output (what speakers play).
REMOTE_SUBMIX / CAPTURE_AUDIO_OUTPUT = system permission = root only.


# Remove Android App via ADB

## 1. Check device connected
```bash
adb devices
```

Uninstall the app
```bash
adb uninstall com.nicitaacom.androidgsm
```

Stop app
```bash
adb shell am force-stop com.nicitaacom.androidgsm
```

Install app
```bash
adb install -r $(ls -t ~/Documents/GitHub/AndroidGSM/app/build/outputs/apk/debug/*.apk | head -n1) && adb shell monkey -p com.nicitaacom.androidgsm -c android.intent.category.LAUNCHER 1
```

---

## Important!
Create separated branch - test if app not crashes - only then merge changes to production
BEFORE coding make sure `androidgsm.config.json` actually exists - implement validation if file is missing

## 📡 Architecture Overview

```
┌─────────────────┐          ┌──────────────┐          ┌──────────────────┐
│     Website     │◄────────►│  server.ts   │◄────────►│  Android app     │
│                 │          │              │          │                  │
│  • Next.js      │          │              │          │                  │
│  • Next.js API  │          │              │          │  • Audio         │
│                 │          │              │          │  • GSM Pusher    │
└─────────────────┘          └──────────────┘          └──────────────────┘
```

## Buttons logic
if it shows "STOP TEST" then "START SERVICE" button is disabled
if no SIM card then "START SERVICE" button is disabled

if it shows "STOP TEST"
then "START SERVICE" button is disabled (so should be only either TEST mode or SERVICE mode)

If it shows "START TEST" then duplex works like android audio input sent to server.ts and also android receive audio from server.ts and plays it as "audio output"

If it shows "STOP TEST" it stop duplex connection and no audio should be sent or received (unless it shows "STOP SERVICE")

If it shows "START SERVICE" then duplex works like android audio input sent to server.ts and also android receive audio from server.ts and plays it as "audio input"

If it shows "STOP SERVICE" it stop duplex connection and no audio should be sent or received (unless it shows "STOP TEST")

## 🧠 Architecture (Current)
```agsl
Backend
├─ Pusher
│ └─ Commands → Android (CALL_START, CALL_END, AUDIO_CHUNK, SEND_DTMF)
├─ REST API
│ └─ /api/events ← Android (status + audio)
└─ Audio Processing
└─ AI / WebRTC / Recording / PBX

Android Device
├─ Foreground Service (GsmService)
├─ GSM Telephony (real SIM)
├─ Audio Capture & Playback (16kHz PCM)
├─ Pusher Client
│ └─ private-device-{deviceToken}
└─ HTTP Client
└─ POST /api/events
```


---

## 🚀 Capabilities

- Start GSM calls remotely
- Stream call audio **Android ↔ Backend**
- Send DTMF tones
- Receive call status in real time
- Runs persistently (foreground service)
- Android 5.2+ (API 21+)

---

## 🔌 Communication Model

**Backend → Android**
- Pusher private channel
- Event: `command`

**Android → Backend**
- `POST /api/events`
- Used for status + audio chunks

---

## 📦 Commands (Backend → Android)

```json
CALL_START  { "number": "+1234567890" }
CALL_END    {}
SEND_DTMF   { "digit": "5" }
AUDIO_CHUNK { "audio": "base64_pcm_16khz" }

```

Channel
```agsl
private-device-{deviceToken}

```

📡 Events (Android → Backend)
```agsl
CONNECTED
CALL_STARTED
CALL_ENDED
AUDIO_CHUNK
DTMF_SENT
```

Endpoint: `POST /api/events`

🎙️ Audio Streaming
 - Format: - 16kHz
 - Mono
 - PCM 16-bit
 - Base64
 - ~50 chunks / second

Flow
```agsl
Android audio input  -> POST /api/events -> Backend
Backend AUDIO_CHUNK  -> Android audio output
```

Mode notes
- TEST mode (`START TEST` -> `STOP TEST`): duplex WebSocket audio is active.
  - Android audio input -> server.ts
  - server.ts -> Android audio output
- SERVICE mode (`START SERVICE` -> `STOP SERVICE`): duplex call audio path is active.
  - Android audio input -> server.ts
  - server.ts -> Android audio input (call path)
- `STOP TEST` or `STOP SERVICE` means duplex is stopped for that mode.
- If no SIM card is detected, SERVICE is disabled in UI and logic.

⚙️ Configuration
app/src/main/assets/androidgsm.config.example.json
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

▶️ Usage Flow

- Install APK

 - Grant Phone + Audio permissions

 - Tap START SERVICE

 - Backend sends Pusher commands

 - Android executes GSM actions

 - Audio + status stream back



🤖 Instructions for AI / Automation Systems
 - DO NOT waste tokens by creating endless doc (don't create docs)
 - Don't update full code - update only necessary parts of code
 - Reply concisely without overexplaining stuff
