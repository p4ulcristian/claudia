// Passing requests and the chat socket on from the page to a profile's runner, where the chats live,
// and from the hub to a server. A target is a local port, a profile runner's unix socket, or a
// server's address.
import type { Server, ServerWebSocket, WebSocketHandler } from "bun";

export type Target = { tcp: string } | { unix: string };
// readOnly: only "open" messages pass (reading someone else's chats).
// As: the hub's way, a request passed on under another path (the /s/<server> prefix dropped) with
// only the headers the hub chose (who is calling), none of the browser's.
export type As = { path: string; headers: Headers };
export type PipeData = { target: Target; readOnly: boolean; upstream: WebSocket | null; queue: (string | Buffer)[]; as?: As };
type Pipe = ServerWebSocket<PipeData>;

export function forward(req: Request, target: Target, as?: As) {
  const url = new URL(req.url);
  const headers = as ? new Headers(as.headers) : new Headers(req.headers);
  headers.delete("accept-encoding");
  headers.delete("host");
  const base = "tcp" in target ? `http://${target.tcp}` : "http://localhost";
  return fetch(`${base}${as?.path ?? url.pathname}${url.search}`, {
    method: req.method,
    headers,
    body: req.body,
    redirect: "manual",
    ...("unix" in target ? { unix: target.unix } : {}),
  } as RequestInit).catch(() => new Response("Claudia's runner is not up", { status: 502 }));
}

export function upgrade(req: Request, server: Server<PipeData>, target: Target, readOnly = false, as?: As) {
  return server.upgrade(req, { data: { target, readOnly, upstream: null, queue: [], as } });
}

function opensOnly(m: string | Buffer) {
  try {
    return JSON.parse(String(m)).t === "open";
  } catch {
    return false;
  }
}

// One upstream socket per browser socket; when either side closes, so does the other.
export function pipe(): WebSocketHandler<PipeData> {
  return {
    maxPayloadLength: 64 * 1024 * 1024,
    open(ws: Pipe) {
      const t = ws.data.target;
      const path = ws.data.as?.path ?? "/ws";
      // (Bun's WebSocket takes headers: the hub's say who is calling)
      const opts = ws.data.as ? ({ headers: Object.fromEntries(ws.data.as.headers) } as unknown as string[]) : undefined;
      const up = new WebSocket("tcp" in t ? `ws://${t.tcp}${path}` : `ws+unix://${t.unix}:${path}`, opts);
      ws.data.upstream = up;
      up.onopen = () => { for (const m of ws.data.queue.splice(0)) up.send(m); };
      up.onmessage = (e) => ws.send(e.data as string);
      up.onclose = () => ws.close();
      up.onerror = () => ws.close();
    },
    message(ws: Pipe, m) {
      if (ws.data.readOnly && !opensOnly(m)) return;
      const up = ws.data.upstream;
      if (up?.readyState === WebSocket.OPEN) up.send(m);
      else ws.data.queue.push(m);
    },
    close(ws: Pipe) {
      ws.data.upstream?.close();
    },
  };
}
