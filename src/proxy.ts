// Passing requests and the chat socket on to another local listener. The web server uses it to
// reach the runner (chats live there), and the gate's tunnel uses it to reach the web server.
import type { Server, ServerWebSocket, WebSocketHandler } from "bun";

export type PipeData = { upstream: WebSocket | null; queue: (string | Buffer)[] };
type Pipe = ServerWebSocket<PipeData>;

export function forward(req: Request, target: string) {
  const url = new URL(req.url);
  const headers = new Headers(req.headers);
  headers.delete("accept-encoding");
  headers.delete("host");
  return fetch(`http://${target}${url.pathname}${url.search}`, {
    method: req.method,
    headers,
    body: req.body,
    redirect: "manual",
  }).catch(() => new Response("Claudia's runner is not up", { status: 502 }));
}

export function upgrade(req: Request, server: Server<PipeData>) {
  return server.upgrade(req, { data: { upstream: null, queue: [] } });
}

// One upstream socket per browser socket; when either side closes, so does the other.
export function pipe(target: string): WebSocketHandler<PipeData> {
  return {
    maxPayloadLength: 64 * 1024 * 1024,
    open(ws: Pipe) {
      const up = new WebSocket(`ws://${target}/ws`);
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
