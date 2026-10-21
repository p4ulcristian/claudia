// Claudia's session host: owns every chat's Claude Code process (an Agent SDK query) and outlives
// the runner. It knows nothing about Claudia itself: it starts queries as the runner describes them,
// streams their messages to the runner, and passes every callback (tools, hooks, permission asks)
// to the runner, waiting while the runner restarts. Keep it small; restarting it stops every chat.
import { createSdkMcpServer, query, tool, type Options, type Query, type SDKMessage, type SDKUserMessage } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";
import { unlink, chmod } from "node:fs/promises";
import type { Socket } from "bun";
import { HOST_SOCKET, type FromHost, type LiveSession, type McpDef, type Rpc, type RpcKind, type StartSpec, type ToHost } from "./src/host-protocol";

// Buffered between results: what a restarted runner needs to pick a turn up again.
const MAX_BUFFER = 4000;
const KEEP_SYSTEM = new Set(["init", "task_started", "task_updated", "task_notification", "task_progress", "background_tasks_changed"]);

class Inbox implements AsyncIterable<SDKUserMessage> {
  private buf: SDKUserMessage[] = [];
  private wake: (() => void) | null = null;
  private done = false;
  push(m: SDKUserMessage) { this.buf.push(m); this.wake?.(); }
  end() { this.done = true; this.wake?.(); }
  async *[Symbol.asyncIterator]() {
    while (true) {
      if (this.buf.length) { yield this.buf.shift()!; continue; }
      if (this.done) return;
      await new Promise<void>((r) => (this.wake = r));
      this.wake = null;
    }
  }
}

type Session = {
  id: string;
  q: Query;
  inbox: Inbox;
  busy: boolean;
  kv: Map<string, unknown>;
  buffer: SDKMessage[];
  closed: boolean;
};

const sessions = new Map<string, Session>();
// Rpcs waiting for the runner: req → session, what was asked, how to answer.
const waiting = new Map<number, { id: string; rpc: Rpc; resolve: (v: any) => void; reject: (e: Error) => void }>();
let nextReq = 1;
type Conn = { buf: string; dec: TextDecoder; out: Buffer[] };
// The runner's connection, once it said hello; before that nothing is sent to it.
let runner: Socket<Conn> | null = null;

// Large messages may not fit the socket at once: what is left waits for drain, in order.
function send(msg: FromHost) {
  if (!runner) return;
  runner.data.out.push(Buffer.from(JSON.stringify(msg) + "\n"));
  flush(runner);
}

function flush(sock: Socket<Conn>) {
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

function rpc(id: string, kind: RpcKind, args: any, signal?: AbortSignal): Promise<any> {
  const req = nextReq++;
  return new Promise((resolve, reject) => {
    const r: Rpc = { req, kind, args };
    waiting.set(req, { id, rpc: r, resolve, reject });
    signal?.addEventListener("abort", () => {
      if (!waiting.delete(req)) return;
      send({ op: "cancel", req });
      reject(new Error("aborted"));
    }, { once: true });
    send({ op: "rpc", id, ...r });
  });
}

// Between results only what still matters is kept: background tasks' state, and the model.
function prune(buffer: SDKMessage[]): SDKMessage[] {
  const lastProgress = new Map<string, number>();
  buffer.forEach((m: any, i) => m.type === "system" && m.subtype === "task_progress" && lastProgress.set(m.task_id, i));
  return buffer.filter((m: any, i) =>
    m.type === "system" && KEEP_SYSTEM.has(m.subtype) && (m.subtype !== "task_progress" || lastProgress.get(m.task_id) === i));
}

// The runner's tools as in-process MCP servers whose every call is asked of the runner.
function mcpServers(id: string, defs: Record<string, McpDef>) {
  const out: Record<string, any> = {};
  for (const [name, def] of Object.entries(defs)) {
    out[name] = createSdkMcpServer({
      name,
      version: def.version,
      alwaysLoad: def.alwaysLoad,
      tools: def.tools.map((t) =>
        tool(t.name, t.description, (z.fromJSONSchema(t.schema as any) as any).shape, (args) => rpc(id, "mcp", { server: name, tool: t.name, args }))),
    });
  }
  return out;
}

function start(id: string, spec: StartSpec) {
  sessions.get(id) && close(id);
  const inbox = new Inbox();
  const hooks: Record<string, any[]> = {};
  for (const [event, matchers] of Object.entries(spec.hooks))
    hooks[event] = matchers.map((m, index) => ({ ...m, hooks: [async (input: unknown) => rpc(id, "hook", { event, index, input })] }));
  const options: Options = {
    ...(spec.options as Options),
    mcpServers: mcpServers(id, spec.mcp),
    hooks,
    canUseTool: (name, input, opts) =>
      rpc(id, "canUseTool", { name, input, toolUseID: opts.toolUseID, title: opts.title, description: opts.description }, opts.signal),
    stderr: (s) => console.error(`[${id.slice(0, 8)}] ${s.trimEnd()}`),
  };
  const s: Session = { id, q: query({ prompt: inbox, options }), inbox, busy: false, kv: new Map(), buffer: [], closed: false };
  sessions.set(id, s);
  pump(s);
}

async function pump(s: Session) {
  let error: string | undefined;
  try {
    for await (const m of s.q) {
      if (s.closed) break;
      // A turn Claude starts by itself (after a background task) is busy too.
      if (m.type === "stream_event" || m.type === "assistant") s.busy = true;
      if (m.type !== "stream_event") {
        s.buffer.push(m);
        if (s.buffer.length > MAX_BUFFER) s.buffer.splice(0, s.buffer.length - MAX_BUFFER);
      }
      if (m.type === "result") {
        s.busy = false;
        s.buffer = prune(s.buffer);
      }
      send({ op: "msg", id: s.id, m });
    }
  } catch (err) {
    error = String((err as Error)?.message ?? err);
  }
  if (s.closed) return;
  // It ended by itself (Claude Code exited or failed): tell the runner, forget it.
  sessions.delete(s.id);
  dropRpcs(s.id);
  send({ op: "end", id: s.id, error });
}

function dropRpcs(id: string) {
  for (const [req, w] of waiting) if (w.id === id) { waiting.delete(req); w.reject(new Error("session closed")); }
}

function close(id: string) {
  const s = sessions.get(id);
  if (!s) return;
  s.closed = true;
  sessions.delete(id);
  dropRpcs(id);
  s.inbox.end();
  s.q.close();
}

async function call(s: Session, method: string, args: any[]) {
  switch (method) {
    case "interrupt": return s.q.interrupt();
    case "setModel": return s.q.setModel(args[0]);
    case "applyFlagSettings": return s.q.applyFlagSettings(args[0]);
    case "stopTask": return s.q.stopTask(args[0]);
    case "getContextUsage": return s.q.getContextUsage(args[0]);
    case "setMcp": {
      // A server under the same name is kept as it was: take them all off, then put the new ones on.
      const off = await s.q.setMcpServers({});
      const on = await s.q.setMcpServers(mcpServers(s.id, args[0]));
      return { off, on };
    }
  }
  throw new Error(`unknown call ${method}`);
}

async function handle(sock: Socket<Conn>, msg: ToHost) {
  switch (msg.op) {
    case "hello": {
      // A runner (re)connected: it takes over, and gets every live session to pick up.
      if (runner && runner !== sock) runner.end();
      runner = sock;
      const list: LiveSession[] = [...sessions.values()].map((s) => ({
        id: s.id, busy: s.busy, kv: Object.fromEntries(s.kv), buffer: s.buffer,
        pending: [...waiting.values()].filter((w) => w.id === s.id).map((w) => w.rpc),
      }));
      return send({ op: "sessions", sessions: list });
    }
    case "start": return start(msg.id, msg.spec);
    case "push": {
      const s = sessions.get(msg.id);
      if (!s) return;
      s.busy = true;
      return s.inbox.push(msg.message);
    }
    case "call": {
      const s = sessions.get(msg.id);
      if (!s) return send({ op: "result", req: msg.req, error: "no such session" });
      try {
        send({ op: "result", req: msg.req, result: await call(s, msg.method, msg.args) });
      } catch (err) {
        send({ op: "result", req: msg.req, error: String((err as Error)?.message ?? err) });
      }
      return;
    }
    case "close": return close(msg.id);
    case "kv": {
      const s = sessions.get(msg.id);
      if (!s) return;
      if ("value" in msg && msg.value !== undefined) s.kv.set(msg.key, msg.value);
      else s.kv.delete(msg.key);
      return;
    }
    case "reply": {
      const w = waiting.get(msg.req);
      if (!w) return;
      waiting.delete(msg.req);
      if (msg.error !== undefined) w.reject(new Error(msg.error));
      else w.resolve(msg.result);
    }
  }
}

await unlink(HOST_SOCKET).catch(() => {});
Bun.listen<Conn>({
  unix: HOST_SOCKET,
  socket: {
    open(sock) {
      sock.data = { buf: "", dec: new TextDecoder(), out: [] };
    },
    data(sock, chunk) {
      sock.data.buf += sock.data.dec.decode(chunk, { stream: true });
      let nl: number;
      while ((nl = sock.data.buf.indexOf("\n")) !== -1) {
        const line = sock.data.buf.slice(0, nl);
        sock.data.buf = sock.data.buf.slice(nl + 1);
        if (!line) continue;
        let msg: ToHost;
        try {
          msg = JSON.parse(line);
        } catch (err) {
          console.error("host: bad line", err);
          continue;
        }
        // Only the runner that said hello is listened to.
        if (msg.op !== "hello" && sock !== runner) continue;
        void handle(sock, msg).catch((err) => console.error("host:", err));
      }
    },
    drain(sock) {
      flush(sock);
    },
    close(sock) {
      if (runner === sock) runner = null;
    },
  },
});
await chmod(HOST_SOCKET, 0o600);
console.log(`Claudia session host on ${HOST_SOCKET}`);
