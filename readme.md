# 📞 Android GSM Gateway

Android foreground service that exposes **real GSM calls** to a backend via
**Pusher (commands)** + **HTTP (events + audio)**.

Phone = modem. Backend = brain.

---

## Important!
Create separated branch - test if app not crashes - only then merge changes to production

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

Endpoint: `POST /api/events
`

🎙️ Audio Streaming
 - Format: - 16kHz
 - Mono
 - PCM 16-bit
 - Base64
 - ~50 chunks / second

Flow
```agsl
Android Mic → POST /api/events → Backend
Backend     → Pusher AUDIO_CHUNK → Android Speaker
```

⚙️ Configuration
app/src/main/assets/androidgsm.config.json
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