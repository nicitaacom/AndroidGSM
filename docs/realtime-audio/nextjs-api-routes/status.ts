import { NextRequest, NextResponse } from "next/server"

const BACKEND_URL = "https://gsm.nexgem.studio"

// 1. force this route to be fully dynamic – no static render, no cache, no revalidation
export const dynamic = "force-dynamic"
export const revalidate = 0

export async function GET(request: NextRequest) {
  try {
    const authToken = process.env.NEXT_PUBLIC_BACKEND_BEARER
    if (!authToken) {
      return NextResponse.json({ isAuthorized: false, error: "Missing NEXT_PUBLIC_BACKEND_BEARER" }, { status: 200 })
    }

    const devicesRes = await fetch(`${BACKEND_URL}/api/devices`, {
      headers: { Authorization: `Bearer ${authToken}` },
      cache: "no-store",
    })
    if (!devicesRes.ok) {
      const text = await devicesRes.text()
      return NextResponse.json({ error: `Failed to fetch devices: ${text}` }, { status: 502 })
    }

    const { devices } = await devicesRes.json()
    if (!devices?.length) {
      // Keep response shape stable for frontend polling logic: "not ready" is not a hard error.
      return NextResponse.json({ isAuthorized: false, lastSeen: null, deviceToken: null, error: "No devices connected" }, { status: 200 })
    }

    const deviceToken = devices[0].deviceToken

    // 1. check if device sent CONNECTED event recently (last 30s)
    const response = await fetch(`${BACKEND_URL}/api/device-status/${deviceToken}`, {
      headers: { Authorization: `Bearer ${authToken}` },
      cache: "no-store",
    })

    if (!response.ok)
      return NextResponse.json({ isAuthorized: false, error: `Backend error ${response.status}` }, { status: 502 })

    const data = await response.json()
    const lastSeen = data.lastSeen ? new Date(data.lastSeen) : null
    const isRecent = lastSeen && Date.now() - lastSeen.getTime() < 30000 // 2. 30s timeout

    return NextResponse.json({ isAuthorized: isRecent, lastSeen: data.lastSeen, deviceToken })
  } catch (error) {
    return NextResponse.json(
      { isAuthorized: false, error: error instanceof Error ? error.message : String(error) },
      { status: 200 },
    )
  }
}
