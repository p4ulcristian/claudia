// The gate's way in: a second listener on the WireGuard address that passes requests to the
// local server. The gate lets only the admins group through its login; this side
// also refuses any signed-in email but ALLOWED_EMAIL, so a Caddy mistake still opens nothing.
import { forward, pipe, upgrade, type PipeData } from "./proxy";

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
          if (new URL(req.url).pathname === "/ws") {
            if (upgrade(req, server)) return;
            return new Response("Upgrade failed", { status: 400 });
          }
          return forward(req, local);
        },
        websocket: pipe(local),
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
