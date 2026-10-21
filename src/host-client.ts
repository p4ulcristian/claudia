// The runner's end of the session host (host.ts): starts and drives chats' Claude Code processes
// there, gets their messages, and answers the host's callbacks. After a runner restart the host hands
// back every live session (with this turn's messages and what the runner kept there), so chats go on.
import type { SDKMessage, SDKUserMessage } from "@anthropic-ai/claude-agent-sdk";
import type { Socket } from "bun";
import { HOST_SOCKET, type CallMethod, type FromHost, type LiveSession, type McpDef, type Rpc, type RpcKind, type StartSpec, type ToHost } from "./host-protocol";

export interface SessionHandler {
  onMessage(m: SDKMessage): void;
  onEnd(error?: string): void;
  onRpc(kind: RpcKind, args: any, signal: AbortSignal): Promise<any>;
}

// One chat's session in the host, as the chat sees it.
export class RemoteSession {
  constructor(private host: HostClient, readonly id: string) {}
  push(message: SDKUserMessage) { this.host.send({ op: "push", id: this.id, message }); }
  close() { this.host.close(this.id); }
  // Something the runner keeps in the host for itself, to have it back after a restart.
  kv(key: string, value?: unknown) { this.host.send({ op: "kv", id: this.id, key, value }); }
  interrupt() { return this.host.call(this.id, "interrupt", []); }
  setModel(model?: string) { return this.host.call(this.id, "setModel", [model]); }
  applyFlagSettings(s: Record<string, unknown>) { return this.host.call(this.id, "applyFlagSettings", [s]); }
  stopTask(taskId: string) { return this.host.call(this.id, "stopTask", [taskId]); }
  getContextUsage(o: Record<string, unknown>): Promise<any> { return this.host.call(this.id, "getContextUsage", [o]); }
  setMcp(defs: Record<string, McpDef>) { return this.host.call(this.id, "setMcp", [defs]); }
}

type Conn = { buf: string; dec: TextDecoder; out: Buffer[] };

export class HostClient {
  private sock: Socket<Conn> | null = null;
  // Sent only after the host answered hello; queued until then (and while the host is away).
  private ready = false;
  private queue: ToHost[] = [];
  private handlers = new Map<string, SessionHandler>();
  private calls = new Map<number, { resolve: (v: any) => void; reject: (e: Error) => void }>();
  private aborts = new Map<number, AbortController>();
  private nextReq = 1;
  private first: (() => void) | null = null;
  // The first hello's answer is in: the runner may serve chats.
  readonly connected: Promise<void>;

  // adopt: a live session the runner has no chat for yet (it restarted); returns that chat's handler.
  constructor(private adopt: (s: LiveSession, session: RemoteSession) => SessionHandler) {
    this.connected = new Promise((r) => (this.first = r));
    this.connect();
  }

  private connect() {
    Bun.connect<Conn>({
      unix: HOST_SOCKET,
      socket: {
        open: (sock) => {
          sock.data = { buf: "", dec: new TextDecoder(), out: [] };
          this.sock = sock;
          this.write({ op: "hello" });
        },
        data: (sock, chunk) => {
          sock.data.buf += sock.data.dec.decode(chunk, { stream: true });
          let nl: number;
          while ((nl = sock.data.buf.indexOf("\n")) !== -1) {
            const line = sock.data.buf.slice(0, nl);
            sock.data.buf = sock.data.buf.slice(nl + 1);
            if (line) this.handle(JSON.parse(line));
          }
        },
        drain: (sock) => this.flush(sock),
        close: () => this.lost(),
        error: () => this.lost(),
      },
    }).catch(() => this.lost());
  }

  private retrying = false;
  private lost() {
    if (this.retrying) return;
    this.retrying = true;
    if (this.sock) {
      // Answers to calls in flight are gone with the connection.
      for (const [, c] of this.calls) c.reject(new Error("The session host connection dropped."));
      this.calls.clear();
      console.error("session host: connection lost; reconnecting");
    }
    this.sock = null;
    this.ready = false;
    setTimeout(() => {
      this.retrying = false;
      this.connect();
    }, 1000);
  }

  private write(msg: ToHost) {
    const sock = this.sock;
    if (!sock) return;
    sock.data.out.push(Buffer.from(JSON.stringify(msg) + "\n"));
    this.flush(sock);
  }

  private flush(sock: Socket<Conn>) {
    const out = sock.data.out;
    while (out.length) {
      const n = sock.write(out[0]);
      if (n < out[0].length) {
        out[0] = out[0].subarray(Math.max(0, n));
        return;
      }
      out.shift();
    }
  }

  send(msg: ToHost) {
    if (this.ready) this.write(msg);
    else this.queue.push(msg);
  }

  call(id: string, method: CallMethod, args: unknown[]): Promise<any> {
    const req = this.nextReq++;
    return new Promise((resolve, reject) => {
      this.calls.set(req, { resolve, reject });
      this.send({ op: "call", id, req, method, args });
    });
  }

  start(id: string, spec: StartSpec, handler: SessionHandler): RemoteSession {
    this.handlers.set(id, handler);
    this.send({ op: "start", id, spec });
    return new RemoteSession(this, id);
  }

  close(id: string) {
    this.handlers.delete(id);
    this.send({ op: "close", id });
  }

  private dispatch(id: string, r: Rpc) {
    const h = this.handlers.get(id);
    if (!h || this.aborts.has(r.req)) return;
    const ac = new AbortController();
    this.aborts.set(r.req, ac);
    h.onRpc(r.kind, r.args, ac.signal).then(
      (result) => this.send({ op: "reply", req: r.req, result }),
      (err) => this.send({ op: "reply", req: r.req, error: String(err?.message ?? err) }),
    ).finally(() => this.aborts.delete(r.req));
  }

  private sessions(list: LiveSession[]) {
    const live = new Set(list.map((s) => s.id));
    // Sessions the host no longer has (it restarted): those chats ended.
    for (const [id, h] of this.handlers)
      if (!live.has(id)) {
        this.handlers.delete(id);
        h.onEnd("Claude's session host restarted; this chat's work stopped. Send a message to carry on.");
      }
    for (const s of list) {
      if (!this.handlers.has(s.id)) this.handlers.set(s.id, this.adopt(s, new RemoteSession(this, s.id)));
      for (const r of s.pending) this.dispatch(s.id, r);
    }
    this.ready = true;
    for (const m of this.queue.splice(0)) this.write(m);
    this.first?.();
    this.first = null;
  }

  private handle(msg: FromHost) {
    switch (msg.op) {
      case "sessions": return this.sessions(msg.sessions);
      case "msg": return this.handlers.get(msg.id)?.onMessage(msg.m);
      case "end": {
        const h = this.handlers.get(msg.id);
        this.handlers.delete(msg.id);
        return h?.onEnd(msg.error);
      }
      case "rpc": return this.dispatch(msg.id, msg);
      case "cancel": return this.aborts.get(msg.req)?.abort();
      case "result": {
        const c = this.calls.get(msg.req);
        if (!c) return;
        this.calls.delete(msg.req);
        return msg.error !== undefined ? c.reject(new Error(msg.error)) : c.resolve(msg.result);
      }
    }
  }
}
