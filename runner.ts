// Claudia's runner: owns the Claude sessions, the chat socket and the API. The web server
// (server.ts) serves the page and passes everything else here, so restarting it keeps chats running.
import { listSessions, renameSession } from "@anthropic-ai/claude-agent-sdk";
import type { ServerWebSocket } from "bun";
import { Chat, NOT_HERE, WORK, loadHistory, useHost, type ChatEvent } from "./src/chat";
import { HostClient } from "./src/host-client";
import { CHAT_COLORS, PASSIVE, pickColor } from "./src/colors";
import { store } from "./src/db";
import { watch } from "node:fs";
import { projectOf, projectLogo } from "./src/projects";
import { search, sessionsDir, warm } from "./src/search";
import { explain, type ExplainInput } from "./src/explain";
import { imageFile, listDir, readText } from "./src/files";
import { REV, commitDetail, filePatch, fingerprint, smartlog } from "./src/git";
import { edgeUses as cljEdge, namespaces as cljNamespaces, nsDetail as cljNs, varSource as cljVar } from "./src/clojure";
import { callsFp, callsMap, callsVar } from "./src/calls";
import { getMeta, onMeta, probe } from "./src/meta";
import { open as openFile, chmod, unlink } from "node:fs/promises";
import { basename, dirname } from "node:path";
import { homedir } from "node:os";
import { TOKEN_FILE } from "./src/mode";
import { githubUser, setGithubToken } from "./src/github";
import { mergedSends, timeline, timelineDetail } from "./src/timeline";
import { forkChat } from "./src/fork";

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
    if (!c.settings.color && store.prefs().chatColors === "each") void c.setSettings({ color: pickColor(usedColors(id)) });
  }
  return c;
}

// The colours the chat list wears now (not archived chats', not this one's): a new pick avoids them.
function usedColors(except?: string): string[] {
  const archived = store.archived();
  const ours = store.ourChats();
  return [...store.colors()].filter(([id]) => id !== except && !archived.has(id) && (ours.has(id) || chats.has(id))).map(([, c]) => c);
}

// Writing to a passive (ash) chat makes it active again: the colour it had before, else a fresh one.
async function wake(c: Chat) {
  if (c.settings.color === PASSIVE) await c.setSettings({ color: c.settings.prevColor ?? pickColor(usedColors(c.id)), prevColor: null });
}

function projectFields(folder: string | null) {
  const p = projectOf(folder);
  return { project: p?.name ?? null, logo: !!p?.logo };
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
  const used = usedColors();
  for (const s of store.prefs().chatColors === "each" ? shown : []) {
    if (colors.has(s.sessionId)) continue;
    const color = pickColor(used);
    used.push(color);
    colors.set(s.sessionId, color);
    const open = chats.get(s.sessionId);
    if (open) void open.setSettings({ color });
    else store.setColor(s.sessionId, color);
  }
  // Chats sent to but not yet written by Claude Code: listed at once, titled by their first message.
  const have = new Set(sessions.map((s) => s.sessionId));
  const fresh = archivedView ? [] : [...chats.values()].filter((c) => c.firstSent && !have.has(c.id) && !archived.has(c.id));
  // In the order they were started, a new chat last, so a chat getting a new message doesn't move.
  return [
    ...shownRows(shown, ours, archived, colors),
    ...fresh.map((c) => ({
      id: c.id,
      title: c.firstSent!.text,
      at: c.firstSent!.at,
      started: c.firstSent!.at,
      ours: true,
      running: c.running,
      waiting: c.waiting,
      agents: c.agents,
      color: colors.get(c.id) ?? c.settings.color ?? null,
      archived: false,
      ...projectFields(c.settings.folder),
    })),
  ];
}

function shownRows(shown: Awaited<ReturnType<typeof listSessions>>, ours: Set<string>, archived: Set<string>, colors: Map<string, string>) {
  return shown
    .sort((a, b) => (a.createdAt ?? a.lastModified) - (b.createdAt ?? b.lastModified))
    .map((s) => ({
      id: s.sessionId,
      title: s.customTitle || s.summary || s.firstPrompt || "New chat",
      at: s.lastModified,
      // When it was started: the list's order, and how the hub's page merges several servers' lists.
      started: s.createdAt ?? s.lastModified,
      ours: ours.has(s.sessionId),
      running: chats.get(s.sessionId)?.running ?? false,
      waiting: chats.get(s.sessionId)?.waiting ?? false,
      agents: chats.get(s.sessionId)?.agents ?? 0,
      color: colors.get(s.sessionId) ?? null,
      archived: archived.has(s.sessionId),
      ...projectFields(chats.get(s.sessionId)?.settings.folder ?? store.settings(s.sessionId).folder),
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
      // notHere: opt-in forms this runner doesn't have (src/chat.ts); the page leaves them out.
      GET: () => json({ ...store.prefs(), notHere: NOT_HERE }),
      PATCH: async (req) => {
        const next = (await req.json()) as Record<string, unknown>;
        const out = store.setPrefs(next as any);
        // A form deleted or restored: every open chat gets the new draw tool now.
        if ("hiddenForms" in next) for (const c of chats.values()) c.swapTools();
        return json(out);
      },
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
    // Full-text search over the chats in view (the same ones /api/chats lists): chats with their best hits.
    "/api/search": {
      GET: async (req) => {
        const p = new URL(req.url).searchParams;
        const text = (p.get("q") ?? "").slice(0, 200);
        const rows = await listChats(p.get("all") === "1", p.get("archived") === "1");
        const found = await search(SESSIONS, text, new Map(rows.map((r) => [r.id, r.title])));
        return json(found);
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
    // A project's logo, for its tag on the rail (found in the repo by src/projects.ts).
    "/api/projects/:name/logo": {
      GET: (req) => {
        const path = projectLogo(decodeURIComponent(req.params.name));
        return path ? new Response(Bun.file(path), { headers: { "Cache-Control": "max-age=600" } }) : new Response("no logo", { status: 404 });
      },
    },
    // Explain one step in plain words (src/explain.ts): Haiku on the side, not the chat's Claude.
    "/api/explain": {
      POST: async (req) => {
        const body = (await req.json().catch(() => null)) as ExplainInput | null;
        if (!body || typeof body.name !== "string" || typeof body.detail !== "string") return json({ error: "a step: name, title, detail" }, 400);
        try {
          return json({ text: await explain(body) });
        } catch (err) {
          return json({ error: String((err as Error)?.message ?? err) }, 500);
        }
      },
    },
    // The files form: a folder's entries or a file's text, inside a root this chat drew a files block for.
    "/api/chats/:id/files": {
      GET: (req) => {
        const id = req.params.id;
        if (!uuidRe.test(id)) return json({ error: "bad id" }, 400);
        const p = new URL(req.url).searchParams;
        const root = p.get("root") ?? "";
        const roots = new Set<string>();
        const scenes = [chats.get(id)?.scene, ...store.pictures(id).map((x) => x.scene)];
        for (const sc of scenes) for (const b of sc?.blocks ?? []) if (b.kind === "form" && b.form === "files" && typeof b.data.root === "string") roots.add(b.data.root);
        // A drawn root, a folder inside one, or one above it up to the home folder (the path in the
        // form's header can be clicked to go up).
        const home = homedir();
        const ok = [...roots].some((r) => r === root || root.startsWith(r + "/") || (r.startsWith(root + "/") && (root === home || root.startsWith(home + "/"))));
        if (!ok) return json({ error: "not a folder of this chat's pictures" }, 403);
        const path = p.get("path") ?? "";
        if (p.get("read") === "1") return json(readText(root, path, p.has("since") ? Number(p.get("since")) : undefined));
        if (p.get("image") === "1") {
          const img = imageFile(root, path);
          return img ? new Response(Bun.file(img), { headers: { "Cache-Control": "max-age=60" } }) : json({ error: "not an image" }, 404);
        }
        const entries = listDir(root, path);
        return entries ? json({ entries }) : json({ error: "no such folder" }, 404);
      },
    },
    // The git form: a repo's graph (only when it changed since the fingerprint the page has), or one
    // commit's files and patch, inside a repo this chat drew a git block for. Read only.
    "/api/chats/:id/git": {
      GET: async (req) => {
        const id = req.params.id;
        if (!uuidRe.test(id)) return json({ error: "bad id" }, 400);
        if (NOT_HERE.includes("git")) return json({ error: "no git form here" }, 404);
        const p = new URL(req.url).searchParams;
        const repo = p.get("repo") ?? "";
        const repos = new Set<string>();
        const scenes = [chats.get(id)?.scene, ...store.pictures(id).map((x) => x.scene)];
        for (const sc of scenes) for (const b of sc?.blocks ?? []) if (b.kind === "form" && b.form === "git" && typeof b.data.repo === "string") repos.add(b.data.repo);
        if (!repos.has(repo)) return json({ error: "not a repo of this chat's pictures" }, 403);
        try {
          const rev = p.get("commit");
          if (rev !== null) {
            if (!REV.test(rev)) return json({ error: "bad commit" }, 400);
            const file = p.get("file");
            if (file === null) return json(await commitDetail(repo, rev));
            if (file.includes("\0") || file.startsWith("-")) return json({ error: "bad path" }, 400);
            return json({ diff: await filePatch(repo, rev, file) });
          }
          const fp = await fingerprint(repo);
          if (p.get("fp") === fp) return json({ same: true });
          const branches = p.get("branches");
          const limit = Math.min(2000, Math.max(1, Number(p.get("limit")) || 100));
          return json({ fp, ...(await smartlog(repo, { limit, branches: branches ? branches.split(",") : null })) });
        } catch (err) {
          return json({ error: String((err as Error)?.message ?? err) }, 500);
        }
      },
    },
    // The clojure form: a project's namespaces (only when a source file changed since the page's
    // fingerprint), one namespace's vars, one var's source, or the vars one arrow uses, inside a root
    // this chat drew. Read only.
    "/api/chats/:id/clojure": {
      GET: async (req) => {
        const id = req.params.id;
        if (!uuidRe.test(id)) return json({ error: "bad id" }, 400);
        if (NOT_HERE.includes("clojure")) return json({ error: "no clojure form here" }, 404);
        const p = new URL(req.url).searchParams;
        const root = p.get("root") ?? "";
        const roots = new Set<string>();
        const scenes = [chats.get(id)?.scene, ...store.pictures(id).map((x) => x.scene)];
        for (const sc of scenes) for (const b of sc?.blocks ?? []) if (b.kind === "form" && b.form === "clojure" && typeof b.data.root === "string") roots.add(b.data.root);
        if (!roots.has(root)) return json({ error: "not a project of this chat's pictures" }, 403);
        try {
          const from = p.get("from"), to = p.get("to");
          if (from !== null && to !== null) return json(await cljEdge(root, from, p.get("fx") === "1", to, p.get("tx") === "1"));
          const ns = p.get("ns");
          const v = p.get("var");
          if (ns !== null && v !== null) return json(await cljVar(root, ns, v));
          if (ns !== null) return json(await cljNs(root, ns));
          return json(await cljNamespaces(root, p.get("fp") ?? undefined));
        } catch (err) {
          return json({ error: String((err as Error)?.message ?? err) }, 500);
        }
      },
    },
    // The calls form: one var's code, callees and callers, or the map of calls around it, inside a
    // project this chat drew with the calls form; without var, the project's fingerprint
    // ({ same } while the page has it). Read only.
    "/api/chats/:id/calls": {
      GET: async (req) => {
        const id = req.params.id;
        if (!uuidRe.test(id)) return json({ error: "bad id" }, 400);
        if (NOT_HERE.includes("calls")) return json({ error: "no calls form here" }, 404);
        const p = new URL(req.url).searchParams;
        const root = p.get("root") ?? "";
        const roots = new Set<string>();
        const scenes = [chats.get(id)?.scene, ...store.pictures(id).map((x) => x.scene)];
        for (const sc of scenes) for (const b of sc?.blocks ?? []) if (b.kind === "form" && b.form === "calls" && typeof b.data.root === "string") roots.add(b.data.root);
        if (!roots.has(root)) return json({ error: "not a project of this chat's pictures" }, 403);
        try {
          const v = p.get("var");
          if (v === null) return json(await callsFp(root, p.get("fp") ?? undefined));
          if (p.get("map") === "1") return json(await callsMap(root, v, Number(p.get("up") ?? 2) || 0, Number(p.get("down") ?? 3) || 0));
          return json(await callsVar(root, v));
        } catch (err) {
          return json({ error: String((err as Error)?.message ?? err) }, 500);
        }
      },
    },
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
        await wake(chat(id));
        await chat(id).send(text);
        broadcastChats();
        return json({ ok: true });
      },
    },
    // Answer the chat's open question(s) in words (Gus passing on what the person said in his thread):
    // the text is the answer to every question asked, as if typed into "Other".
    "/api/chats/:id/answer": {
      POST: async (req) => {
        const id = req.params.id;
        if (!uuidRe.test(id)) return json({ error: "bad id" }, 400);
        const body = (await req.json().catch(() => ({}))) as { text?: unknown };
        const text = typeof body.text === "string" ? body.text.trim() : "";
        if (!text || text.length > 20_000) return json({ error: "text: 1 to 20000 characters" }, 400);
        const c = chats.get(id);
        const open = c ? [...c.asks.values()].map((w) => w.value) : [];
        if (!c || open.length === 0) return json({ error: "no open question in this chat" }, 409);
        for (const a of open) c.answer(a.id, Object.fromEntries(a.questions.map((q) => [q.question, text])));
        return json({ ok: true, answered: open.length });
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
        // What it is doing now, for a watcher's progress line: the newest step of this turn (a tool's
        // one-line title, or the start of what it said) and how many tools it has run in it.
        const turn = items.slice(lastUser + 1);
        const tools = turn.filter((i) => i.k === "tool") as { name: string; title: string }[];
        const newest = turn.at(-1);
        const step = !newest ? "" : newest.k === "tool" ? `${(newest as { name: string }).name}: ${(newest as { title: string }).title}`.slice(0, 160) : newest.k === "text" ? (newest as { text: string }).text.split("\n")[0].slice(0, 160) : "";
        return json({
          known: !!c || items.length > 0,
          running: c?.running ?? false,
          waiting: c?.waiting ?? false,
          asks: c ? [...c.asks.values()].map((w) => w.value) : [],
          last,
          step,
          steps: tools.length,
          since: c?.running && c.since ? new Date(c.since).toISOString() : null,
          agents: c?.agents ?? 0,
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
    // Fork: a new chat holding this one up to the end of a turn. { at?: the uuid of a user message,
    // whose turn is the last kept; none = up to the last finished turn }. Returns the new chat's id.
    "/api/chats/:id/fork": {
      POST: async (req) => {
        const id = req.params.id;
        if (!uuidRe.test(id)) return json({ error: "bad id" }, 400);
        const body = (await req.json().catch(() => ({}))) as { at?: unknown };
        const at = typeof body.at === "string" && body.at ? body.at : null;
        try {
          const fork = await forkChat(id, at, chats.get(id)?.running ?? false);
          chat(fork); // its own colour, like any new chat
          broadcastChats();
          return json({ id: fork });
        } catch (err) {
          return json({ error: String((err as Error)?.message ?? err) }, 409);
        }
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
            await wake(c);
            await c.send(String(m.text ?? ""), m.images ?? []);
            return broadcastChats();
          }
          // The user typing in an editor block (saved as they type) and its Send.
          case "code_edit": {
            if (typeof m.block !== "string" || typeof m.file !== "string" || typeof m.text !== "string" || m.text.length > 1_000_000) return;
            return chat(m.chat).editCode(m.block, m.file, m.text, Number(m.base) || 0);
          }
          case "code_send": {
            if (typeof m.block !== "string") return;
            const c = chat(m.chat);
            store.unarchive(m.chat);
            if (ws.data.chat !== m.chat) await openChat(ws, m.chat);
            await wake(c);
            await c.sendCode(m.block, String(m.note ?? "").slice(0, 20_000));
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
            if ("cols" in m) (next as any).cols = [0, 1, 2, 3].includes(Number(m.cols)) ? Number(m.cols) : 0;
            if ("forms" in m && m.forms && typeof m.forms === "object")
              (next as any).forms = Object.fromEntries(Object.entries(m.forms).filter(([, v]) => v === "required" || v === "off"));
            await chat(m.chat).setSettings(next);
            return "color" in m ? broadcastChats() : undefined;
          }
          case "wake": {
            await wake(chat(m.chat));
            return broadcastChats();
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
// The search index catches up with every session in the background, newest first.
const SESSIONS = sessionsDir(WORK);
void listSessions({ dir: WORK, includeWorktrees: false })
  .then((all) => warm(SESSIONS, all.sort((a, b) => b.lastModified - a.lastModified).map((s) => s.sessionId)))
  .catch(() => {});
setInterval(() => socks.size && probe(WORK), 5 * 60_000);
// Someone replaced the token in Settings: show the new plan's limits now.
watch(dirname(TOKEN_FILE), (_, file) => file === basename(TOKEN_FILE) && probe(WORK));

if (SOCKET) await chmod(SOCKET, 0o660);
console.log(`Claudia runner on ${SOCKET ?? `127.0.0.1:${PORT}`} (chats in ${WORK})`);
