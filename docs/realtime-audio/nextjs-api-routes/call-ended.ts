import { NextRequest } from "next/server"

const BACKEND_URL = "https://gsm.nexgem.studio"

export async function POST(request: NextRequest) {
  try {
    const body = await request.json()
    const { deviceToken } = body

    if (!deviceToken) {
      return new Response(JSON.stringify({ error: "Missing deviceToken" }), { status: 400 })
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
          type: "CALL_ENDED",
          data: {},
        },
      }),
    })

    if (!res.ok) {
      const errorData = await res.json().catch(() => ({ error: "Failed to end call" }))
      throw new Error(errorData.error || `HTTP ${res.status}: ${res.statusText}`)
    }

    return new Response(JSON.stringify({ success: true }), { status: 200 })
  } catch (error) {
    console.error("Call end proxy error:", error)
    return new Response(JSON.stringify({ error: error instanceof Error ? error.message : String(error) }), { status: 500 })
  }
}
