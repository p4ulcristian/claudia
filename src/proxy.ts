// Passing requests and the chat socket on from the page to a profile's runner, where the chats live.
// A target is a local port or a profile runner's unix socket.
import type { Server, ServerWebSocket, WebSocketHandler } from "bun";

export type Target = { tcp: string } | { unix: string };
export type PipeData = { target: Target; upstream: WebSocket | null; queue: (string | Buffer)[] };
type Pipe = ServerWebSocket<PipeData>;

export function forward(req: Request, target: Target) {
  const url = new URL(req.url);
  const headers = new Headers(req.headers);
  headers.delete("accept-encoding");
  headers.delete("host");
  const base = "tcp" in target ? `http://${target.tcp}` : "http://localhost";
  return fetch(`${base}${url.pathname}${url.search}`, {
    method: req.method,
    headers,
    body: req.body,
    redirect: "manual",
    ...("unix" in target ? { unix: target.unix } : {}),
  } as RequestInit).catch(() => new Response("Claudia's runner is not up", { status: 502 }));
}

export function upgrade(req: Request, server: Server<PipeData>, target: Target) {
  return server.upgrade(req, { data: { target, upstream: null, queue: [] } });
}

// One upstream socket per browser socket; when either side closes, so does the other.
export function pipe(): WebSocketHandler<PipeData> {
  return {
    maxPayloadLength: 64 * 1024 * 1024,
    open(ws: Pipe) {
      const t = ws.data.target;
      const up = new WebSocket("tcp" in t ? `ws://${t.tcp}/ws` : `ws+unix://${t.unix}:/ws`);
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
  };
}
