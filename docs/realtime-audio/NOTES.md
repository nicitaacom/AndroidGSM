# Realtime audio notes for GSM bridge

## 1) Can root make uplink injection possible?
Yes, **with root and extra audio-stack components** it can be made possible, but not by root alone.

Root gives you access to install/modify lower-level audio routing (AudioFlinger/HAL hooks, virtual microphone modules, custom ROM patches). Without those, a normal app still cannot reliably inject arbitrary PCM directly into the GSM modem uplink path.

## 2) Why your previous implementation failed
- Browser sent audio in HTTP requests per chunk (high overhead/jitter).
- Pusher is fine for signaling but not ideal for realtime media transport.
- Android app routing was forced to speaker in older implementation.

## 3) Do you need Pion?
- **Pion is Go**, so not usable directly in `server.ts`.
- If you keep Node (`server.ts`), use WebSocket or a Node WebRTC stack.
- If you can move backend to Go, then Pion is a great choice (Opus/RTP/jitter are handled by WebRTC stack).

## 4) Practical low-load path (single-user, <=1000ms latency)
- Keep control plane on HTTP/Pusher.
- Move media plane to persistent WebSocket (`/ws/audio`) with `seq` + `ts` packets.
- Use receiver-side jitter queue as shown in `useInitGSM.ts`.

## 5) Opus + RTP timestamps
For true Opus+RTP, prefer WebRTC end-to-end. In plain WS mode above, packet headers mimic RTP fields (`seq`, `ts`) but codec remains PCM16 for simplicity.
