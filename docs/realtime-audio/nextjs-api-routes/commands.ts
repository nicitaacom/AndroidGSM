import { NextRequest, NextResponse } from "next/server"

const BACKEND_URL = "https://gsm.nexgem.studio"

export async function POST(request: NextRequest) {
  try {
    const body = await request.json()
    const { deviceToken, commands } = body

    if (!deviceToken || !commands?.type) {
      return NextResponse.json({ error: "Missing deviceToken or commands.type" }, { status: 400 })
    }

    const res = await fetch(`${BACKEND_URL}/api/commands`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${process.env.NEXT_PUBLIC_BACKEND_BEARER}`,
      },
      body: JSON.stringify({ deviceToken, commands }),
    })

    if (!res.ok) {
      const text = await res.text()
      return NextResponse.json({ error: text }, { status: 502 })
    }

    return NextResponse.json({ success: true })
  } catch (error) {
    return NextResponse.json(
      { error: error instanceof Error ? error.message : String(error) },
      { status: 500 },
    )
  }
}
