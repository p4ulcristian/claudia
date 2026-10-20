// Claudia: chat on the left, Claude's picture on the right.
import { listSessions, renameSession } from "@anthropic-ai/claude-agent-sdk";
import type { ServerWebSocket } from "bun";
import index from "./web/index.html";
import { Chat, WORK, loadHistory, type ChatEvent } from "./src/chat";
import { CHAT_COLORS, pickColor } from "./src/colors";
import { store } from "./src/db";
import { watch } from "node:fs";
import { getMeta, onMeta, probe } from "./src/meta";
import { open as openFile } from "node:fs/promises";
import { serveTunnel } from "./src/tunnel";

const PORT = Number(process.env.PORT ?? 4510);
const chats = new Map<string, Chat>();
type Sock = ServerWebSocket<{ chat: string | null; off: (() => void) | null }>;
const socks = new Set<Sock>();

const uuidRe = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

function broadcastChats() {
  for (const s of socks) s.send(JSON.stringify({ t: "chats_changed" }));
}

function chat(id: string) {
  let c = chats.get(id);
  if (!c) {
    chats.set(id, (c = new Chat(id, broadcastChats)));
    // Every chat gets a colour of its own from the start.
    if (!c.settings.color) void c.setSettings({ color: pickColor(store.colors().values()) });
  }
  return c;
}

async function listChats(all: boolean, archivedView: boolean) {
  const ours = store.ourChats();
  const archived = store.archived();
  const colors = store.colors();
  const sessions = await listSessions({ dir: WORK, includeWorktrees: false });
  const shown = sessions.filter((s) =>
    archivedView ? archived.has(s.sessionId) : !archived.has(s.sessionId) && (all || ours.has(s.sessionId) || chats.has(s.sessionId)),
  );
  // Chats from before colours, or never opened here, get one the first time they're listed.
  for (const s of shown) {
    if (colors.has(s.sessionId)) continue;
    const color = pickColor(colors.values());
    colors.set(s.sessionId, color);
    const open = chats.get(s.sessionId);
    if (open) void open.setSettings({ color });
    else store.setColor(s.sessionId, color);
  }
  return shown
    .map((s) => ({
      id: s.sessionId,
      title: s.customTitle || s.summary || s.firstPrompt || "New chat",
      at: s.lastModified,
      ours: ours.has(s.sessionId),
      running: chats.get(s.sessionId)?.running ?? false,
      color: colors.get(s.sessionId) ?? null,
      archived: archived.has(s.sessionId),
    }));
}

async function openChat(ws: Sock, id: string) {
  ws.data.off?.();
  ws.data.chat = id;
  const c = chat(id);
  const items = await loadHistory(id).catch(() => []);
  if (ws.data.chat !== id) return;
  ws.send(
    JSON.stringify({
      t: "history",
      chat: id,
      items,
      pictures: store.pictures(id),
      scene: c.scene,
      running: c.running,
      since: c.since,
      partial: c.partial,
      asks: [...c.asks.values()].map((w) => w.value),
      permits: [...c.permits.values()].map((w) => w.value),
      tasks: [...c.tasks.values()],
      subs: Object.fromEntries(c.subs),
      context: c.context,
      settings: c.settings,
      model: c.model,
      changes: store.changes(id),
    }),
  );
  ws.data.off = c.on((ev: ChatEvent) => ws.send(JSON.stringify({ t: "event", chat: id, ev })));
}

const json = (v: unknown, status = 200) => Response.json(v, { status });

Bun.serve({
  hostname: "127.0.0.1",
  port: PORT,
  idleTimeout: 0,
  development: process.env.NODE_ENV !== "production" && { hmr: true, console: true },
  routes: {
    "/": index,
    "/c/:id": index,
    "/api/chats": {
      GET: async (req) => {
        const p = new URL(req.url).searchParams;
        return json(await listChats(p.get("all") === "1", p.get("archived") === "1"));
      },
    },
    // The last part of a background task's output file (only files the task reported).
    "/api/chats/:id/tasks/:task/output": {
      GET: async (req) => {
        const file = chats.get(req.params.id)?.tasks.get(req.params.task)?.outputFile;
        if (!file) return json({ text: "" });
        try {
          const f = await openFile(file, "r");
          const { size } = await f.stat();
          const len = Math.min(size, 16_000);
          const buf = Buffer.alloc(len);
          await f.read(buf, 0, len, size - len);
          await f.close();
          return json({ text: buf.toString("utf8"), truncated: size > len });
        } catch {
          return json({ text: "" });
        }
      },
    },
    // One change's before and after, for the diff view.
    "/api/chats/:id/changes/:change": {
      GET: (req) => {
        const row = store.change(req.params.id, Number(req.params.change));
        return row ? json(row) : json({ error: "not found" }, 404);
      },
    },
    "/api/chats/:id": {
      // Rename and/or archive: { title?, archived? }
      PATCH: async (req) => {
        const id = req.params.id;
        if (!uuidRe.test(id)) return json({ error: "bad id" }, 400);
        const body = (await req.json()) as { title?: string; archived?: boolean };
        if (typeof body.title === "string") await renameSession(id, body.title.slice(0, 200), { dir: WORK });
        if (body.archived === true) store.archive(id);
        if (body.archived === false) store.unarchive(id);
        broadcastChats();
        return json({ ok: true });
      },
    },
  },
  fetch(req, server) {
    if (new URL(req.url).pathname === "/ws" && server.upgrade(req, { data: { chat: null, off: null } })) return;
    return new Response("Not found", { status: 404 });
  },
  websocket: {
    maxPayloadLength: 64 * 1024 * 1024,
    open: (ws: Sock) => {
      socks.add(ws);
      ws.send(JSON.stringify({ t: "meta", meta: getMeta() }));
    },
    close: (ws: Sock) => {
      ws.data.off?.();
      socks.delete(ws);
    },
    async message(ws: Sock, raw) {
      const m = JSON.parse(String(raw));
      if (!uuidRe.test(m.chat ?? "")) return;
      try {
        switch (m.t) {
          case "open": return await openChat(ws, m.chat);
          case "send": {
            const c = chat(m.chat);
            store.unarchive(m.chat); // writing to an archived chat brings it back
            if (ws.data.chat !== m.chat) await openChat(ws, m.chat);
            await c.send(String(m.text ?? ""), m.images ?? []);
            return broadcastChats();
          }
          case "stop": return await chats.get(m.chat)?.stop();
          case "answer": return chats.get(m.chat)?.answer(m.id, m.answers ?? {});
          case "permit": return chats.get(m.chat)?.permit(m.id, !!m.allow);
          case "settings": {
            const next: Record<string, string | null> = {};
            if ("model" in m) next.model = m.model || null;
            if ("effort" in m) next.effort = m.effort || null;
            if ("color" in m) next.color = (CHAT_COLORS as readonly string[]).includes(m.color) ? m.color : null;
            await chat(m.chat).setSettings(next);
            return "color" in m ? broadcastChats() : undefined;
          }
          case "stop_task": return await chats.get(m.chat)?.stopTask(String(m.task));
        }
      } catch (err) {
        ws.send(JSON.stringify({ t: "event", chat: m.chat, ev: { t: "error", text: String((err as Error)?.message ?? err) } }));
      }
    },
  },
});

// Models, commands and plan usage: read now, then every 5 minutes, and soon after each turn (see Chat).
onMeta((meta) => { for (const s of socks) s.send(JSON.stringify({ t: "meta", meta })); });
probe(WORK);
setInterval(() => socks.size && probe(WORK), 5 * 60_000);
// The desktop widget switched accounts (claude-account's state file): show the new plan's limits now.
const runtime = process.env.XDG_RUNTIME_DIR;
if (runtime) watch(runtime, (_, file) => file === "claude-account" && probe(WORK));

console.log(`Claudia on http://claudia.localhost:${PORT} (chats in ${WORK})`);

// Reachable through a reverse proxy: only when the unit sets both (drop-in, not in this repo).
if (process.env.TUNNEL_HOST && process.env.ALLOWED_EMAIL) serveTunnel(process.env.TUNNEL_HOST, PORT, process.env.ALLOWED_EMAIL);
