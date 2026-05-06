import { NextRequest, NextResponse } from "next/server"

const BACKEND_URL = "https://gsm.nexgem.studio"

export const dynamic = "force-dynamic"
export const revalidate = 0

export async function GET(_request: NextRequest) {
  try {
    const authToken = process.env.NEXT_PUBLIC_BACKEND_BEARER
    if (!authToken) {
      return NextResponse.json({ error: "Missing NEXT_PUBLIC_BACKEND_BEARER" }, { status: 500 })
    }

    const res = await fetch(`${BACKEND_URL}/api/logs?n=50`, {
      headers: { Authorization: `Bearer ${authToken}` },
      cache: "no-store",
    })

    if (!res.ok) {
      return NextResponse.json({ error: `Backend error ${res.status}` }, { status: 502 })
    }

    const data = await res.json()
    return NextResponse.json({ logs: data.logs ?? [] })
  } catch (error) {
    return NextResponse.json(
      { logs: [], error: error instanceof Error ? error.message : String(error) },
      { status: 200 },
    )
  }
}
