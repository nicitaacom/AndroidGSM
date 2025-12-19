# Quick Start Guide

## 🚀 Installation

1. **Install the APK on your Android device:**
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

2. **Configure the WebSocket URL:**
   Edit `app/src/main/assets/androidgsm.config.json`:
   ```json
   {
     "WS_URL": "wss://your-backend.com/api/ws",
     "DEVICE_TOKEN": "device-001",
     "BACKEND_AUTH_KEY": "your-secret-key",
     "PUSHER_APP_ID": "",
     "PUSHER_KEY": "",
     "PUSHER_SECRET": "",
     "PUSHER_CLUSTER": ""
   }
   ```

3. **Grant permissions** when the app starts
4. **Tap "START SERVICE"** - button turns green/red to show status

## 📡 Backend WebSocket Commands

### Start a Call
```json
{
  "type": "CALL_START",
  "data": {
    "number": "+1234567890"
  }
}
```

### End a Call (Limited Support)
```json
{
  "type": "CALL_END",
  "data": {}
}
```
**Note:** Ending calls programmatically is restricted on Android - user must hang up manually

### Send DTMF (Limited Support)
```json
{
  "type": "SEND_DTMF",
  "data": {
    "digit": "5"
  }
}
```
**Note:** DTMF requires active call connection - limited on older Android versions

## ✅ What Works

- ✅ Making GSM calls remotely via WebSocket
- ✅ WebSocket bi-directional communication
- ✅ Real-time status updates
- ✅ Dark modern UI with toggle button
- ✅ Android 5.2+ (API 21+) compatible
- ✅ Persistent service (survives screen lock)

## ⚠️ Known Limitations

- ❌ **Cannot end calls programmatically** on Android < 9 (requires system permissions)
- ❌ **DTMF tones** require active call connection (limited API support)
- ⚠️ Service requires user to keep app running or start manually after reboot

## 🧪 Testing

1. Start the service in the app
2. From your backend, connect to WebSocket
3. Send a CALL_START command with a valid phone number
4. Watch the Android device dial the number
5. Check logs in the app UI

## 🐛 Troubleshooting

**Service stops immediately:**
- Check logs in app UI
- Verify config file exists and is valid JSON
- Grant all permissions

**Calls don't start:**
- Ensure CALL_PHONE permission granted
- Check phone number format (include country code)
- Verify SIM card is active

**WebSocket won't connect:**
- Check internet connection
- Verify WebSocket URL is correct (must start with `ws://` or `wss://`)
- Check backend is running

## 📖 Full Documentation

See [README.md](README.md) for complete documentation and backend examples.
