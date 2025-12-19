# Android GSM Gateway

A lightweight Android service that enables remote GSM calling via WebSocket commands.

## 🚀 Features

- **Remote Call Control**: Make and end GSM calls via WebSocket
- **DTMF Support**: Send DTMF tones during active calls
- **Android 5.2+ Compatible**: Works on API level 21+
- **Dark Modern UI**: Clean green-branded interface

## 📱 Requirements

- Android device with GSM capabilities
- Android 5.2 (API 21) or higher
- Active SIM card
- Internet connection for WebSocket

## ⚙️ Configuration

1. Edit `app/src/main/assets/androidgsm.config.json`:

```json
{
  "WS_URL": "wss://your-backend.com/api/ws",
  "DEVICE_TOKEN": "unique-device-identifier",
  "BACKEND_AUTH_KEY": "your-auth-key",
  "PUSHER_APP_ID": "not-used",
  "PUSHER_KEY": "not-used",
  "PUSHER_SECRET": "not-used",
  "PUSHER_CLUSTER": "not-used"
}
```

**Note**: Only `WS_URL`, `DEVICE_TOKEN`, and `BACKEND_AUTH_KEY` are actively used.

## 🔌 Backend Integration

### WebSocket Connection

The Android app connects to your backend via WebSocket:

```
wss://your-backend.com/api/ws
```

**Connection Headers:**
```
Authorization: Bearer YOUR_BACKEND_AUTH_KEY
```

### Sending Commands to Android (Backend → Android)

Send JSON commands via WebSocket:

#### 1. Start a Call
```json
{
  "type": "CALL_START",
  "data": {
    "number": "+1234567890"
  }
}
```

#### 2. End a Call
```json
{
  "type": "CALL_END",
  "data": {}
}
```

#### 3. Send DTMF Tone
```json
{
  "type": "SEND_DTMF",
  "data": {
    "digit": "5"
  }
}
```

**Supported DTMF digits**: 0-9, *, #

### Receiving Status Updates (Android → Backend)

The Android app sends status updates via WebSocket:

#### Call Started
```json
{
  "type": "CALL_STARTED",
  "data": {
    "number": "+1234567890"
  }
}
```

#### Call Ended
```json
{
  "type": "CALL_ENDED",
  "data": {}
}
```

#### DTMF Sent
```json
{
  "type": "DTMF_SENT",
  "data": {
    "digit": "5"
  }
}
```

## 🎯 Usage Flow

1. **Install the app** on your Android device
2. **Grant permissions** when prompted (Phone, Audio, Internet)
3. **Tap "START SERVICE"** - button turns green when active
4. **Backend sends commands** via WebSocket
5. **Android executes** GSM operations and sends status updates

## 🛠️ Development

### Build Requirements
- Android Studio
- Kotlin
- Gradle

### Building
```bash
./gradlew assembleDebug
```

### Installing
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 📋 Example Backend Implementation (Node.js)

```javascript
const WebSocket = require('ws');
const wss = new WebSocket.Server({ port: 8080 });

wss.on('connection', (ws, req) => {
  console.log('Android device connected');

  // Receive status updates from Android
  ws.on('message', (message) => {
    const data = JSON.parse(message);
    console.log('Status update:', data);
  });

  // Send command to Android
  function makeCall(phoneNumber) {
    ws.send(JSON.stringify({
      type: 'CALL_START',
      data: { number: phoneNumber }
    }));
  }

  function endCall() {
    ws.send(JSON.stringify({
      type: 'CALL_END',
      data: {}
    }));
  }

  function sendDTMF(digit) {
    ws.send(JSON.stringify({
      type: 'SEND_DTMF',
      data: { digit: digit }
    }));
  }

  // Example: Make a call after 5 seconds
  setTimeout(() => makeCall('+1234567890'), 5000);
});
```

## 🔐 Security Notes

- Use WSS (WebSocket Secure) in production
- Keep `BACKEND_AUTH_KEY` secret
- Never commit `androidgsm.config.json` with real credentials
- Use `.env.local` for sensitive configuration

## 📝 Logs

The app provides real-time logs in the UI:
- Service status
- WebSocket connection state
- Command execution
- Call status
- Errors and warnings

## 🐛 Troubleshooting

### Service crashes on start
- Check logs in the app
- Verify `androidgsm.config.json` exists and is valid JSON
- Ensure WebSocket URL is accessible

### Calls don't start
- Verify Phone permission is granted
- Check if SIM card is active
- Ensure valid phone number format

### WebSocket won't connect
- Check internet connection
- Verify WebSocket URL is correct
- Check backend is running and accessible

## 📄 License

MIT License - feel free to use in your projects

## 🤝 Contributing

Pull requests are welcome!

---

**Made with ❤️ for remote GSM operations**
