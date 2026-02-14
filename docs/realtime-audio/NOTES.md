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

## 6) Why WS for media instead of Pusher Channels?
- Pusher Channels is excellent for signaling/events, but media streaming needs tighter control over packet size/rate/backpressure and lower overhead.
- WS gives direct, persistent, bidirectional transport where you can tune framing and queue behavior for jitter buffer logic.
- Keep Pusher for call-state signaling (`CALL_STARTED`, `CALL_CONNECTED`, `CALL_ENDED`), and use WS/WebRTC for continuous audio frames.

## 7) "`/ws/audio` does not exist" confusion
- `wss://your-domain/ws/audio` is a **WebSocket upgrade endpoint**, not a normal browser page route.
- Opening it in the browser address bar via `https://.../ws/audio` will not prove much; use DevTools WS tab or `wscat`.
- If WS never connects, the usual issue is reverse proxy config not forwarding `Upgrade`/`Connection` headers.

## 8) If `/api/commands` is unauthorized but `/api/events` works
- Your website Next.js API routes and Android app may be using different bearer values.
- Verify the exact token used by website proxy routes matches backend `BACKEND_BEARER` byte-for-byte (no extra quotes/spaces/newlines).
- Prefer reading bearer from server-side env in Next routes, not hardcoded literals.

## 9) If frontend shows ready but commands still fail
- "Ready" can come from status polling / pusher device events, but dialing still depends on `/api/commands` auth.
- If you use multiple Pusher apps, ensure frontend subscribes with the same key/cluster as backend triggers for GSM channels.
- Add a dedicated `NEXT_PUBLIC_GSM_PUSHER_APP_KEY` (or equivalent) if your app has separate realtime domains.
