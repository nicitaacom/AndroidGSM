# Backend Integration Guide - Pusher with Bi-Directional Audio

## 📡 Architecture Overview

```
┌─────────────────┐          ┌──────────────┐          ┌──────────────────┐
│   Android App   │◄────────►│    Pusher    │◄────────►│  Backend Server  │
│                 │          │              │          │                  │
│  • GsmService   │          │  Channels:   │          │  • Express API   │
│  • PusherClient │          │  - Commands  │          │  • Pusher Trigger│
│  • AudioHandler │          │  - Audio     │          │  • Audio Process │
└─────────────────┘          └──────────────┘          └──────────────────┘
```

## 🔄 Communication Flow

### 1. **Android → Backend (Events & Audio)**
   - Endpoint: `POST /api/events`
   - Auth: `Bearer YOUR_BACKEND_BEARER`
   - Content: JSON events + audio chunks

### 2. **Backend → Android (Commands)**
   - Channel: `private-device-{deviceToken}`
   - Event: `command`
   - Auth: `/pusher/auth` endpoint

## 📋 Android Configuration

```json
{
  "BACKEND_URL": "https://gsm.nexgem.studio/api/ws",
  "DEVICE_TOKEN": "android-gateway-01",
  "BACKEND_BEARER": "wss-1234-$3cre7",
  "PUSHER_APP_ID": "2093123",
  "PUSHER_KEY": "1ce1dcec8fd97743a14f",
  "PUSHER_SECRET": "43a11a3d28d58fc14bae",
  "PUSHER_CLUSTER": "eu"
}
```

## 🎯 Backend API Endpoints

### 1. Health Check
```typescript
GET /health

Response:
{
  status: "ok",
  connectedDevices: 1,
  uptime: 12345,
  timestamp: "2024-01-01T12:00:00Z"
}
```

### 2. List Connected Devices
```typescript
GET /api/devices

Response:
{
  devices: [
    {
      deviceToken: "android-gateway-01",
      lastSeen: "2024-01-01T12:00:00Z"
    }
  ]
}
```

### 3. Receive Events from Android
```typescript
POST /api/events
Authorization: Bearer wss-1234-$3cre7
Content-Type: application/json

Body:
{
  "deviceToken": "android-gateway-01",
  "type": "CALL_STARTED",
  "data": {
    "number": "+1234567890"
  }
}
```

**Event Types:**
- `CONNECTED` - Device connected to Pusher
- `CALL_STARTED` - Call initiated
- `CALL_ENDED` - Call terminated
- `DTMF_SENT` - DTMF tone sent
- `AUDIO_CHUNK` - Audio data from Android microphone
- `STATUS` - General status update

### 4. Send Commands to Android
```typescript
POST /api/commands
Authorization: Bearer wss-1234-$3cre7
Content-Type: application/json

Body:
{
  "deviceToken": "android-gateway-01",
  "command": {
    "type": "CALL_START",
    "data": {
      "number": "+1234567890"
    }
  }
}
```

**Command Types:**
- `CALL_START` - Initiate GSM call
- `CALL_END` - End call (user must manually hang up)
- `SEND_DTMF` - Send DTMF tone
- `AUDIO_CHUNK` - Send audio to Android for playback

### 5. Pusher Authentication
```typescript
POST /pusher/auth

Body:
{
  "socket_id": "12345.67890",
  "channel_name": "private-device-android-gateway-01"
}

Response:
{
  "auth": "pusher-key:auth-signature",
  "channel_data": "{...}"
}
```

## 🎙️ Audio Streaming

### Android → Backend (Capture)

Android automatically starts audio capture when a call begins:

```typescript
// Android sends to /api/events
{
  "deviceToken": "android-gateway-01",
  "type": "AUDIO_CHUNK",
  "data": {
    "audio": "SGVsbG8gV29ybGQ=", // Base64 encoded PCM
    "timestamp": 1234567890
  }
}
```

**Audio Format:**
- Sample Rate: 16kHz
- Channels: Mono
- Encoding: PCM 16-bit
- Chunk Frequency: ~50 chunks/second

### Backend → Android (Playback)

Send audio to Android via Pusher:

```typescript
pusher.trigger(`private-device-android-gateway-01`, 'command', {
  type: 'AUDIO_CHUNK',
  data: {
    audio: 'SGVsbG8gV29ybGQ=' // Base64 encoded PCM
  },
  timestamp: new Date().toISOString()
});
```

## 💻 Backend Implementation Examples

### Making a Call

```typescript
app.post('/make-call', async (req, res) => {
  const { deviceToken, phoneNumber } = req.body;
  
  await pusher.trigger(`private-device-${deviceToken}`, 'command', {
    type: 'CALL_START',
    data: { number: phoneNumber },
    timestamp: new Date().toISOString()
  });
  
  res.json({ success: true });
});
```

### Processing Incoming Audio

```typescript
app.post('/api/events', (req, res) => {
  const { deviceToken, type, data } = req.body;
  
  if (type === 'AUDIO_CHUNK') {
    const audioBase64 = data.audio;
    const audioBuffer = Buffer.from(audioBase64, 'base64');
    
    // Process audio
    processAudio(deviceToken, audioBuffer);
    
    // Forward to WebRTC, save to file, etc.
  }
  
  res.json({ success: true });
});
```

### Sending Audio to Android

```typescript
function sendAudioToAndroid(deviceToken: string, audioBuffer: Buffer) {
  const base64Audio = audioBuffer.toString('base64');
  
  pusher.trigger(`private-device-${deviceToken}`, 'command', {
    type: 'AUDIO_CHUNK',
    data: { audio: base64Audio },
    timestamp: new Date().toISOString()
  });
}
```

### Complete Call Flow

```typescript
// 1. Make call
await pusher.trigger('private-device-android-gateway-01', 'command', {
  type: 'CALL_START',
  data: { number: '+1234567890' },
  timestamp: new Date().toISOString()
});

// 2. Android responds with CALL_STARTED event
// 3. Android starts sending AUDIO_CHUNK events

// 4. Process and forward audio
app.post('/api/events', (req, res) => {
  if (req.body.type === 'AUDIO_CHUNK') {
    // Decode audio
    const audioBuffer = Buffer.from(req.body.data.audio, 'base64');
    
    // Send to WebRTC peer, AI service, etc.
    webrtcPeer.sendAudio(audioBuffer);
    
    // Forward audio back to Android (echo test)
    sendAudioToAndroid(req.body.deviceToken, audioBuffer);
  }
  res.json({ success: true });
});

// 5. End call when done
await pusher.trigger('private-device-android-gateway-01', 'command', {
  type: 'CALL_END',
  data: {},
  timestamp: new Date().toISOString()
});
```

## 🔐 Security

1. **Always use HTTPS/WSS** in production
2. **Validate BACKEND_BEARER** on all `/api/*` endpoints
3. **Authenticate Pusher channels** properly
4. **Rate limit** audio chunk endpoints
5. **Sanitize phone numbers** before initiating calls

## 📊 Monitoring

Track these metrics:
- Connected devices count
- Audio chunk throughput
- Call success rate
- Pusher connection stability
- API response times

## 🐛 Debugging

### Enable Verbose Logging

In Android app logs, you'll see:
```
[12:34:56] GsmService: Pusher connecting...
[12:34:57] ✅ Pusher connected!
[12:34:58] 📶 Subscribed to private-device-android-gateway-01
[12:35:00] 📡 Command received: CALL_START
[12:35:01] 🎤 Starting audio capture...
[12:35:02] Sent 50 audio chunks
```

### Common Issues

**Pusher won't connect:**
- Check Pusher credentials
- Verify `/pusher/auth` endpoint
- Check BACKEND_BEARER token

**No audio streaming:**
- Ensure RECORD_AUDIO permission granted
- Check audio format compatibility
- Verify audio chunks are being sent to `/api/events`

**Commands not received:**
- Check private channel subscription
- Verify deviceToken matches
- Check Pusher event name is exactly "command"

## 📚 Additional Resources

- [Pusher Docs](https://pusher.com/docs)
- [Android Audio APIs](https://developer.android.com/reference/android/media/AudioRecord)
- [PCM Audio Format](https://en.wikipedia.org/wiki/Pulse-code_modulation)

## 🚀 Production Checklist

- [ ] Configure HTTPS/WSS endpoints
- [ ] Set up proper authentication
- [ ] Implement rate limiting
- [ ] Add audio processing pipeline
- [ ] Set up monitoring/logging
- [ ] Test bi-directional audio quality
- [ ] Handle reconnection logic
- [ ] Document your specific use case
