// Claudia's runner: owns the Claude sessions, the chat socket and the API. The web server
// (server.ts) serves the page and passes everything else here, so restarting it keeps chats running.
import { listSessions, renameSession } from "@anthropic-ai/claude-agent-sdk";
import type { ServerWebSocket } from "bun";
import { Chat, WORK, loadHistory, useHost, type ChatEvent } from "./src/chat";
import { HostClient } from "./src/host-client";
import { CHAT_COLORS, pickColor } from "./src/colors";
import { store } from "./src/db";
import { watch } from "node:fs";
import { getMeta, onMeta, probe } from "./src/meta";
import { open as openFile, chmod, unlink } from "node:fs/promises";
import { basename, dirname } from "node:path";
import { TOKEN_FILE } from "./src/mode";
import { githubUser, setGithubToken } from "./src/github";
import { mergedSends, timeline, timelineDetail } from "./src/timeline";

const PORT = Number(process.env.RUNNER_PORT ?? 4511);
// On the box: a unix socket only the page can reach (the unit's runtime folder grants it).
const SOCKET = process.env.RUNNER_SOCKET;
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
    // Every chat gets a colour of its own from the start, unless the preference says no.
    if (!c.settings.color && store.prefs().chatColors === "each") void c.setSettings({ color: pickColor(store.colors().values()) });
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
  for (const s of store.prefs().chatColors === "each" ? shown : []) {
    if (colors.has(s.sessionId)) continue;
    const color = pickColor(colors.values());
    colors.set(s.sessionId, color);
    const open = chats.get(s.sessionId);
    if (open) void open.setSettings({ color });
    else store.setColor(s.sessionId, color);
  }
  // In the order they were started, a new chat last, so a chat getting a new message doesn't move.
  return shown
    .sort((a, b) => (a.createdAt ?? a.lastModified) - (b.createdAt ?? b.lastModified))
    .map((s) => ({
      id: s.sessionId,
      title: s.customTitle || s.summary || s.firstPrompt || "New chat",
      at: s.lastModified,
      ours: ours.has(s.sessionId),
      running: chats.get(s.sessionId)?.running ?? false,
      waiting: chats.get(s.sessionId)?.waiting ?? false,
      agents: chats.get(s.sessionId)?.agents ?? 0,
      color: colors.get(s.sessionId) ?? null,
      archived: archived.has(s.sessionId),
    }));
}

// Listen first and take the live state now; only the transcript is read afterwards. Events that
// come while it loads are held and sent after it (the page drops items it already has), so none is lost.
async function openChat(ws: Sock, id: string) {
  ws.data.off?.();
  ws.data.chat = id;
  const c = chat(id);
  const held: ChatEvent[] = [];
  let ready = false;
  const forward = (ev: ChatEvent) => ws.send(JSON.stringify({ t: "event", chat: id, ev }));
  ws.data.off = c.on((ev: ChatEvent) => (ready ? forward(ev) : held.push(ev)));
  const now = {
    pictures: store.pictures(id),
    scene: c.scene,
    running: c.running,
    since: c.since,
    partial: c.partial,
    asks: [...c.asks.values()].map((w) => w.value),
    permits: [...c.permits.values()].map((w) => w.value),
    tasks: [...c.tasks.values()],
    // (copies: these lists grow in place, and their new entries come again as held events)
    subs: Object.fromEntries([...c.subs].map(([k, v]) => [k, [...v]])),
    context: c.context,
    settings: c.settings,
    model: c.model,
    changes: store.changes(id),
  };
  const [items, merged] = await Promise.all([loadHistory(id).catch(() => []), mergedSends(id).catch(() => new Set<string>())]);
  if (ws.data.chat !== id) return;
  ws.send(JSON.stringify({ t: "history", chat: id, items: c.withUnsaved(items, merged), ...now }));
  ready = true;
  for (const ev of held.splice(0)) forward(ev);
}

const json = (v: unknown, status = 200) => Response.json(v, { status });

// The chats' Claude Code processes live in the session host (host.ts), so restarting this runner
// leaves them running: the host hands back every live session, and its chat picks it up. Chats are
// served only once that happened, so none is started twice.
const host = new HostClient((live, session) => chat(live.id).adopt(live, session));
useHost(host);
await host.connected;
console.log("session host connected");

if (SOCKET) await unlink(SOCKET).catch(() => {});

Bun.serve({
  // (cast: Bun's types want the unix and hostname/port forms written out separately)
  ...((SOCKET ? { unix: SOCKET } : { hostname: "127.0.0.1", port: PORT }) as { hostname: string; port: number }),
  idleTimeout: 0,
  routes: {
    // This profile's preferences (each runner has its own store).
    "/api/prefs": {
      GET: () => json(store.prefs()),
      PATCH: async (req) => json(store.setPrefs((await req.json()) as Record<string, unknown> as any)),
    },
    // This profile's GitHub login (the token itself never comes back).
    "/api/github": {
      GET: async () => json({ user: await githubUser() }),
      PUT: async (req) => {
        try {
          return json({ user: await setGithubToken(((await req.json()) as { token?: string }).token) });
        } catch (err) {
          return json({ error: (err as Error).message }, 400);
        }
      },
    },
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
    // What the chat and each of its subagents did, when (from the session files). ?v= the version the
    // page has: the same version comes back as { same: true } instead of the whole timeline again.
    "/api/chats/:id/timeline": {
      GET: async (req) => {
        const id = req.params.id;
        if (!uuidRe.test(id)) return json({ error: "bad id" }, 400);
        const c = chats.get(id);
        const agents = new Set([...(c?.tasks.values() ?? [])].filter((t) => t.status === "running" && t.toolUseId).map((t) => t.toolUseId!));
        const t = await timeline(id, c?.running ?? false, agents);
        if (!t) return json({ error: "not found" }, 404);
        return new URL(req.url).searchParams.get("v") === t.v ? json({ same: true, v: t.v, now: t.now }) : json(t);
      },
    },
    // One step of the timeline in full.
    "/api/chats/:id/timeline/:lane/:event": {
      GET: async (req) => {
        if (!uuidRe.test(req.params.id)) return json({ error: "bad id" }, 400);
        const d = await timelineDetail(req.params.id, req.params.lane, decodeURIComponent(req.params.event)).catch(() => null);
        return d ? json(d) : json({ error: "not found" }, 404);
      },
    },
    // One change's before and after, for the diff view.
    "/api/chats/:id/changes/:change": {
      GET: (req) => {
        const row = store.change(req.params.id, Number(req.params.change));
        return row ? json(row) : json({ error: "not found" }, 404);
      },
    },
    // Another program on this machine asks this profile's Claude for something (Eye on Rainbow's Gus,
    // for the person who asked him): a new chat with the text sent, like a message typed here. Only
    // what can reach this runner can call it: the page, and on the box the users its socket allows.
    "/api/delegate": {
      POST: async (req) => {
        const body = (await req.json().catch(() => ({}))) as { text?: unknown };
        const text = typeof body.text === "string" ? body.text.trim() : "";
        if (!text || text.length > 20_000) return json({ error: "text: 1 to 20000 characters" }, 400);
        const id = crypto.randomUUID();
        await chat(id).send(text);
        broadcastChats();
        return json({ id });
      },
    },
    // A follow-up into a chat started from outside (Gus passing on what the person said next), sent
    // like a typed message; it waits in the queue if a turn is running.
    "/api/chats/:id/send": {
      POST: async (req) => {
        const id = req.params.id;
        if (!uuidRe.test(id)) return json({ error: "bad id" }, 400);
        const body = (await req.json().catch(() => ({}))) as { text?: unknown };
        const text = typeof body.text === "string" ? body.text.trim() : "";
        if (!text || text.length > 20_000) return json({ error: "text: 1 to 20000 characters" }, 400);
        if (!chats.has(id) && (await loadHistory(id).catch(() => [])).length === 0) return json({ error: "no such chat" }, 404);
        store.unarchive(id);
        await chat(id).send(text);
        broadcastChats();
        return json({ ok: true });
      },
    },
    // How a chat stands, for whoever started it from outside: working, waiting on a question, and its
    // last answer (the text after the last message sent).
    "/api/chats/:id/state": {
      GET: async (req) => {
        const id = req.params.id;
        if (!uuidRe.test(id)) return json({ error: "bad id" }, 400);
        const c = chats.get(id);
        const items = await loadHistory(id).catch(() => []);
        const lastUser = items.map((i) => i.k).lastIndexOf("user");
        const last = items.slice(lastUser + 1).filter((i) => i.k === "text").map((i) => (i as { text: string }).text).join("\n\n");
        return json({
          known: !!c || items.length > 0,
          running: c?.running ?? false,
          waiting: c?.waiting ?? false,
          asks: c ? [...c.asks.values()].map((w) => w.value) : [],
          last,
        });
      },
    },
    // Saved templates (freeform blocks kept by name): the forms gallery lists them.
    "/api/templates": {
      GET: () => json(store.templates()),
    },
    "/api/templates/:name": {
      DELETE: (req) => {
        store.deleteTemplate(decodeURIComponent(req.params.name));
        return json({ ok: true });
      },
    },
    // The Save button on a freeform block: { name, description? }
    "/api/chats/:id/blocks/:block/template": {
      POST: async (req) => {
        const id = req.params.id;
        if (!uuidRe.test(id)) return json({ error: "bad id" }, 400);
        const body = (await req.json()) as { name?: string; description?: string };
        const text = chat(id).saveTemplate(String(body.name ?? ""), { block: decodeURIComponent(req.params.block), description: body.description });
        return json({ ok: /^(Saved|Updated)/.test(text), text });
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
// Someone replaced the token in Settings: show the new plan's limits now.
watch(dirname(TOKEN_FILE), (_, file) => file === basename(TOKEN_FILE) && probe(WORK));

if (SOCKET) await chmod(SOCKET, 0o660);
console.log(`Claudia runner on ${SOCKET ?? `127.0.0.1:${PORT}`} (chats in ${WORK})`);
