# 📞 Android GSM Gateway (com.nicitaacom.androidgsm)

An Android-based GSM-to-WebRTC bridge designed for IoT, PBX, and remote telephony integration.

---

## 🚀 Overview

**Android GSM Gateway** turns your Android phone into a programmable GSM-to-VoIP gateway.

It connects your device’s **cellular telephony** (GSM) with **WebRTC audio streams** and **WebSocket-based remote control**, enabling you to:

- Initiate and manage GSM calls remotely
- Send DTMF tones during calls
- Stream real-time GSM audio over WebRTC
- Integrate Android telephony with modern web or SIP systems

All of this runs as a **foreground background service** — always on, always connected.

---

## 🧱 Architecture

| Component | Description |
|------------|-------------|
| `GsmService.kt` | Main foreground service handling telephony, WebRTC, and WebSocket bridging |
| `WebRtcManager.kt` | Manages WebRTC peer connections, ICE servers, and local audio tracks |
| `ConfigReader.kt` | Loads app configuration from `assets/androidgsm.config.json` |
| `MainActivity.kt` | Minimal UI for permissions and service startup |
| `androidgsm.config.json` | Configuration file (WebSocket URL, auth token, ICE servers) |

---

## 🛠️ Tech Stack

- **Kotlin 1.9.25**
- **Android SDK 34 (minSdk 21 for android 5.2 compatibility)**
- **WebRTC (org.webrtc:google-webrtc:1.0.36908)**
- **OkHttp WebSocket (Square)**
- **Gson (Google)**
- **Kotlin Coroutines**
- **AndroidX + Material Components**

---

## 📂 Project Structure

```agsl
android-app/
├── app/
│ ├── src/main/
│ │ ├── java/com/nicitaacom/androidgsm/
│ │ │ ├── service/GsmService.kt
│ │ │ ├── webrtc/WebRtcManager.kt
│ │ │ ├── config/ConfigReader.kt
│ │ │ └── ui/MainActivity.kt
│ │ ├── assets/androidgsm.config.json
│ │ └── res/layout/activity_main.xml
│ └── build.gradle
├── settings.gradle
├── build.gradle
└── gradle.properties
```



---

## ⚙️ Configuration

Place your configuration in:


Example:
```json
{
  "WS_URL": "wss://yourserver.com/ws",
  "AUTH_KEY": "super-secret-key",
  "DEVICE_TOKEN": "gateway-01",
  "ICE_SERVERS": [
    { "urls": ["stun:stun.l.google.com:19302"] },
    { "urls": ["turn:turn.example.com"], "username": "user", "credential": "pass" }
  ]
}
```

### 🧾 Permissions

Declared in AndroidManifest.xml:

```agsl
<uses-permission android:name="android.permission.CALL_PHONE" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />

```


### 🧠 Future Enhancements

- Remote audio track support for bidirectional WebRTC audio

 - Configurable audio codecs (Opus, G.711)

- Error recovery and reconnection strategies

- Basic Web UI dashboard
