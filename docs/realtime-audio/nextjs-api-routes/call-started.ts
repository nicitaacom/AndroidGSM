import { NextRequest } from "next/server"

// TODO for now I leave it as is but if lots of people need it I create a setup (like with twilio so everybody can use GSM)
const BACKEND_URL = "https://gsm.nexgem.studio"

export async function POST(request: NextRequest) {
  try {
    const body = await request.json()
    const { num, deviceToken, simAccountId, simComponentName } = body

    if (!num || !deviceToken) {
      return new Response(JSON.stringify({ error: "Missing num or deviceToken" }), { status: 400 })
    }

    const res = await fetch(`${BACKEND_URL}/api/commands`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${process.env.NEXT_PUBLIC_BACKEND_BEARER}`,
      },
      body: JSON.stringify({
        deviceToken,
        commands: {
          type: "CALL_STARTED",
          data: { number: num, simAccountId, simComponentName },
        },
      }),
    })

    if (!res.ok) {
      const errorData = await res.json().catch(() => ({ error: "Failed to start call" }))
      throw new Error(errorData.error || `HTTP ${res.status}: ${res.statusText}`)
    }

    return new Response(JSON.stringify({ success: true }), { status: 200 })
  } catch (error) {
    console.error("Call start proxy error:", error)
    return new Response(JSON.stringify({ error: error instanceof Error ? error.message : String(error) }), { status: 500 })
  }
}
