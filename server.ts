// Claudia: chat on the left, Claude's picture on the right. This serves the page; the chats, the
// chat socket and the API live in the runners (runner.ts), one per profile, reached through the proxy.
// Two listeners: 127.0.0.1 for the desktop window, and GATE_HOST for the gate (src/profiles.ts).
import index from "./web/index.html";
import type { Server } from "bun";
import { forward, pipe, upgrade, type PipeData } from "./src/proxy";
import { NAME } from "./src/mode";
import { LOCAL_PROFILE, cleanToken, setToken, tokenNote, who } from "./src/profiles";

const PORT = Number(process.env.PORT ?? 4510);
const GATE_HOST = process.env.GATE_HOST;

const json = (v: unknown, status = 200) => Response.json(v, { status });

function serve(hostname: string, gate: boolean) {
  // Who this is, which profile, the instance's name and the token note.
  const me = (req: Request) => {
    const w = who(req, gate);
    return json({ name: NAME, email: w.email, profile: w.profile?.name ?? null, claudeToken: tokenNote() });
  };

  const putToken = async (req: Request) => {
    const w = who(req, gate);
    if (!w.profile) return json({ error: "not allowed" }, 403);
    const token = cleanToken(((await req.json().catch(() => ({}))) as { token?: string }).token);
    if (!token) return json({ error: "That doesn't look like a Claude token (sk-ant-…). Make one with `claude setup-token`." }, 400);
    await setToken(token, w.email ?? w.profile.name);
    console.log(`Claude token replaced by ${w.email ?? w.profile.name}`);
    return json({ ok: true, claudeToken: tokenNote() });
  };

  return Bun.serve<PipeData>({
    hostname,
    port: PORT,
    idleTimeout: 0,
    development: process.env.NODE_ENV !== "production" && { hmr: true, console: true },
    routes: {
      "/": index,
      "/c/:id": index,
      "/api/me": { GET: me },
      "/api/claude-token": { PUT: putToken },
    },
    fetch(req: Request, server: Server<PipeData>) {
      const path = new URL(req.url).pathname;
      if (path !== "/ws" && !path.startsWith("/api/")) return new Response("Not found", { status: 404 });
      const w = who(req, gate);
      if (!w.profile) return json({ error: w.email ? `no Claudia profile for ${w.email}` : "not signed in" }, 403);
      if (path === "/ws") return upgrade(req, server, w.profile.runner) ? undefined : new Response("Upgrade failed", { status: 400 });
      return forward(req, w.profile.runner);
    },
    websocket: pipe(),
  });
}

// The desktop window: only where a local profile is set (on the box, nobody uses it locally).
if (LOCAL_PROFILE) {
  serve("127.0.0.1", false);
  console.log(`Claudia on http://claudia.localhost:${PORT} (as ${LOCAL_PROFILE})`);
}

// The gate's way in, on the WireGuard address. WireGuard may come up after the service; retry until it does.
function serveGate(host: string) {
  try {
    serve(host, true);
    console.log(`Claudia for the gate on http://${host}:${PORT}`);
  } catch (err) {
    console.error(`Gate listener on ${host}:${PORT} failed (${(err as Error).message}); retrying in 30 s`);
    setTimeout(() => serveGate(host), 30_000);
  }
}
if (GATE_HOST) serveGate(GATE_HOST);
