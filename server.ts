// Claudia: chat on the left, Claude's picture on the right. This serves the page; the chats,
// the chat socket and the API live in the runner (runner.ts), reached through the proxy.
import index from "./web/index.html";
import { forward, pipe, upgrade, type PipeData } from "./src/proxy";
import { serveTunnel } from "./src/tunnel";

const PORT = Number(process.env.PORT ?? 4510);
const RUNNER = `127.0.0.1:${process.env.RUNNER_PORT ?? 4511}`;

Bun.serve<PipeData>({
  hostname: "127.0.0.1",
  port: PORT,
  idleTimeout: 0,
  development: process.env.NODE_ENV !== "production" && { hmr: true, console: true },
  routes: {
    "/": index,
    "/c/:id": index,
  },
  fetch(req, server) {
    const path = new URL(req.url).pathname;
    if (path === "/ws") return upgrade(req, server) ? undefined : new Response("Upgrade failed", { status: 400 });
    if (path.startsWith("/api/")) return forward(req, RUNNER);
    return new Response("Not found", { status: 404 });
  },
  websocket: pipe(RUNNER),
});

console.log(`Claudia on http://claudia.localhost:${PORT} (runner at ${RUNNER})`);

// Reachable through a reverse proxy: only when the unit sets both (drop-in, not in this repo).
if (process.env.TUNNEL_HOST && process.env.ALLOWED_EMAIL) serveTunnel(process.env.TUNNEL_HOST, PORT, process.env.ALLOWED_EMAIL);
