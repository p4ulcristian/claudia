// The gate's way in: a second listener on the WireGuard address that passes requests to the
// local server. The gate lets only the admins group through its login; this side
// also refuses any signed-in email but ALLOWED_EMAIL, so a Caddy mistake still opens nothing.
import type { ServerWebSocket } from "bun";

type PipeData = { upstream: WebSocket | null; queue: (string | Buffer)[] };
type Pipe = ServerWebSocket<PipeData>;

export function serveTunnel(host: string, port: number, allowedEmail: string) {
  const local = `127.0.0.1:${port}`;
  const allowed = (req: Request) => req.headers.get("x-auth-request-email")?.toLowerCase() === allowedEmail.toLowerCase();

  const start = () => {
    try {
      Bun.serve<PipeData>({
        hostname: host,
        port,
        idleTimeout: 0,
        async fetch(req, server) {
          if (!allowed(req)) return new Response("403: not the owner", { status: 403 });
          const url = new URL(req.url);
          if (url.pathname === "/ws") {
            if (server.upgrade(req, { data: { upstream: null, queue: [] } })) return;
            return new Response("Upgrade failed", { status: 400 });
          }
          const headers = new Headers(req.headers);
          headers.delete("accept-encoding");
          headers.delete("host");
          return fetch(`http://${local}${url.pathname}${url.search}`, {
            method: req.method,
            headers,
            body: req.body,
            redirect: "manual",
          });
        },
        websocket: {
          maxPayloadLength: 64 * 1024 * 1024,
          open(ws: Pipe) {
            const up = new WebSocket(`ws://${local}/ws`);
            ws.data.upstream = up;
            up.onopen = () => { for (const m of ws.data.queue.splice(0)) up.send(m); };
            up.onmessage = (e) => ws.send(e.data as string);
            up.onclose = () => ws.close();
            up.onerror = () => ws.close();
          },
          message(ws: Pipe, m) {
            const up = ws.data.upstream;
            if (up?.readyState === WebSocket.OPEN) up.send(m);
            else ws.data.queue.push(m);
          },
          close(ws: Pipe) {
            ws.data.upstream?.close();
          },
        },
      });
      console.log(`Claudia tunnel on http://${host}:${port}`);
    } catch (err) {
      // WireGuard may come up after the desktop session; try again until it does.
      console.error(`Tunnel listener on ${host}:${port} failed (${(err as Error).message}); retrying in 30 s`);
      setTimeout(start, 30_000);
    }
  };
  start();
}
