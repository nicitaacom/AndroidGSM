/**
 * Reference Next.js API route handlers for GSM command/status proxying.
 *
 * Flow:
 *   browser useInitGSM.ts -> /api/gsm/* (Next.js) -> backend server.ts
 *
 * IMPORTANT:
 * - Keep BACKEND_BEARER in server-side env (not hardcoded in production).
 * - Commands (`/api/commands`) and status (`/api/devices`, `/api/device-status/...`) must use the same bearer.
 */

import { NextRequest, NextResponse } from "next/server"

const BACKEND_URL = process.env.BACKEND_URL || "https://gsm.nexgem.studio"
const BACKEND_BEARER = process.env.BACKEND_BEARER || ""

function authHeaders() {
  return {
    "Content-Type": "application/json",
    Authorization: `Bearer ${BACKEND_BEARER}`,
  }
}

export async function callStarted(request: NextRequest) {
  const { num, deviceToken } = await request.json()
  return fetch(`${BACKEND_URL}/api/commands`, {
    method: "POST",
    headers: authHeaders(),
    body: JSON.stringify({
      deviceToken,
      commands: { type: "CALL_STARTED", data: { number: num } },
    }),
  })
}

export async function callEnded(request: NextRequest) {
  const { deviceToken } = await request.json()
  return fetch(`${BACKEND_URL}/api/commands`, {
    method: "POST",
    headers: authHeaders(),
    body: JSON.stringify({
      deviceToken,
      commands: { type: "CALL_ENDED", data: {} },
    }),
  })
}

export async function sendDtmf(request: NextRequest) {
  const { digit, deviceToken } = await request.json()
  return fetch(`${BACKEND_URL}/api/commands`, {
    method: "POST",
    headers: authHeaders(),
    body: JSON.stringify({
      deviceToken,
      commands: { type: "SEND_DTMF", data: { digit } },
    }),
  })
}

export async function sendAudioChunk(request: NextRequest) {
  const { audio, deviceToken } = await request.json()
  return fetch(`${BACKEND_URL}/api/commands`, {
    method: "POST",
    headers: authHeaders(),
    body: JSON.stringify({
      deviceToken,
      commands: { type: "AUDIO_CHUNK", data: { audio } },
    }),
  })
}

export async function status(_request: NextRequest) {
  const devicesRes = await fetch(`${BACKEND_URL}/api/devices`, {
    headers: { Authorization: `Bearer ${BACKEND_BEARER}` },
  })

  if (!devicesRes.ok) {
    return NextResponse.json({ isAuthorized: false, error: "Failed to fetch devices" }, { status: 502 })
  }

  const { devices } = await devicesRes.json()
  if (!devices?.length) {
    return NextResponse.json({ isAuthorized: false, error: "No devices connected" }, { status: 503 })
  }

  const deviceToken = devices[0].deviceToken
  const response = await fetch(`${BACKEND_URL}/api/device-status/${deviceToken}`, {
    headers: { Authorization: `Bearer ${BACKEND_BEARER}` },
  })

  if (!response.ok) {
    return NextResponse.json({ isAuthorized: false, error: `Backend error ${response.status}` }, { status: 502 })
  }

  const data = await response.json()
  return NextResponse.json({
    isAuthorized: !!data?.isAuthorized,
    lastSeen: data?.lastSeen,
    deviceToken,
  })
}
