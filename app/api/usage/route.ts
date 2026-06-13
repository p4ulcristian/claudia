import { getUsage, type UsageData } from "@/lib/usage";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

// Capturing /usage spawns the TUI (a few seconds), so cache briefly and
// de-duplicate concurrent requests.
const TTL = 60_000;
let cache: { at: number; data: UsageData } | null = null;
let inflight: Promise<UsageData> | null = null;

export async function GET(req: Request) {
  const force = new URL(req.url).searchParams.get("refresh") === "1";
  if (!force && cache && Date.now() - cache.at < TTL) {
    return Response.json({ ...cache.data, cached: true });
  }
  try {
    if (!inflight) {
      inflight = getUsage({ signal: req.signal }).then((data) => {
        cache = { at: Date.now(), data };
        return data;
      });
    }
    const data = await inflight;
    return Response.json(data);
  } catch (e) {
    return new Response(
      JSON.stringify({ error: e instanceof Error ? e.message : String(e) }),
      { status: 500, headers: { "content-type": "application/json" } },
    );
  } finally {
    inflight = null;
  }
}
