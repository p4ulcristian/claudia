// The timeline: what the main thread and every subagent did, when, from the session files on disk.
// Claude Code appends to them as it goes (one file for the chat, one per subagent), so each file is
// read once and then only from where the last read stopped. Only a compact line per step is kept;
// a step's full input and output are read again from its place in the file when it is opened.
import { homedir } from "node:os";
import { join } from "node:path";
import { open, readdir, readFile, stat } from "node:fs/promises";
import { WORK, resultText, stripReminders, toolLabel, toolTitle } from "./chat";

// Where a line sits in its file: byte offset and length.
type Loc = [number, number];

export type TimelineEvent =
  | { k: "tool"; id: string; name: string; title: string; start: number; end?: number; error?: boolean; agent?: string }
  | { k: "text"; id: string; at: number; text: string }
  | { k: "user"; id: string; at: number; text: string }
  | { k: "note"; id: string; at: number; text: string };

export type Lane = {
  id: string; // "main" or the subagent's id
  title: string;
  kind: string; // subagent type; "main" for the chat itself
  parent: string | null; // lane id of whoever started it
  toolUseId: string | null; // the Agent call that started it
  start: number;
  end: number;
  running: boolean;
  events: TimelineEvent[];
};

export type Timeline = { v: string; now: number; lanes: Lane[] };

export type Detail =
  | { k: "tool"; name: string; input: string; output: string | null; error: boolean; truncated: boolean }
  | { k: "text"; text: string; truncated: boolean };

const PREVIEW = 300;
// Larger inputs and outputs are cut here; nothing a person reads is near it.
const FULL = 1_000_000;
const CHUNK = 4 << 20;

type ToolEv = Extract<TimelineEvent, { k: "tool" }>;
type Parsed = {
  offset: number;
  rest: Buffer;
  restAt: number;
  first: number;
  last: number;
  events: TimelineEvent[];
  tools: Map<string, ToolEv>;
  // Where each event's line(s) are: the tool_use line, the tool_result line.
  locs: Map<string, { use?: Loc; result?: Loc }>;
};

const ms = (t: unknown) => (typeof t === "string" ? Date.parse(t) : NaN);
const cut = (s: string, n: number) => (s.length > n ? s.slice(0, n - 1) + "…" : s);

function fresh(): Parsed {
  return { offset: 0, rest: Buffer.alloc(0), restAt: 0, first: 0, last: 0, events: [], tools: new Map(), locs: new Map() };
}

// One line of a session file into events.
function take(p: Parsed, line: string, loc: Loc) {
  let d: any;
  try {
    d = JSON.parse(line);
  } catch {
    return;
  }
  if (d.type !== "user" && d.type !== "assistant") return;
  if (d.isMeta || d.isCompactSummary) return;
  const at = ms(d.timestamp);
  if (!Number.isFinite(at)) return;
  p.first ||= at;
  p.last = Math.max(p.last, at);
  const content = d.message?.content;

  if (d.type === "assistant") {
    if (!Array.isArray(content)) return;
    content.forEach((b: any, n: number) => {
      if (b.type === "text" && b.text?.trim()) {
        const id = `${d.uuid}:${n}`;
        p.events.push({ k: "text", id, at, text: cut(b.text.trim(), PREVIEW) });
        p.locs.set(id, { use: loc });
      }
      if (b.type === "tool_use" && !p.tools.has(b.id)) {
        const ev: ToolEv = { k: "tool", id: b.id, name: toolLabel(b.name), title: toolTitle(b.name, b.input), start: at };
        p.tools.set(b.id, ev);
        p.events.push(ev);
        p.locs.set(b.id, { use: loc });
      }
    });
    return;
  }

  // A user line: tool results, or something a person (or Claude Code) said.
  if (Array.isArray(content) && content.some((b: any) => b.type === "tool_result")) {
    for (const b of content) {
      if (b.type !== "tool_result") continue;
      const ev = p.tools.get(b.tool_use_id);
      if (!ev) continue;
      ev.end = at;
      ev.error = !!b.is_error;
      p.locs.get(b.tool_use_id)!.result = loc;
    }
    return;
  }
  const raw = typeof content === "string" ? content : Array.isArray(content) ? content.filter((b: any) => b.type === "text").map((b: any) => b.text).join("\n") : "";
  const text = stripReminders(raw);
  if (!text) return;
  if (text.startsWith("<task-notification>")) {
    const summary = text.match(/<summary>([\s\S]*?)<\/summary>/)?.[1]?.trim() ?? "A background task finished.";
    p.events.push({ k: "note", id: d.uuid, at, text: cut(summary, PREVIEW) });
    return;
  }
  // Slash-command bookkeeping and similar tagged lines are not something anyone wrote.
  if (/^<[a-z-]+>/.test(text)) return;
  p.events.push({ k: "user", id: d.uuid, at, text: cut(text, PREVIEW) });
  p.locs.set(d.uuid, { use: loc });
}

// Files read so far, by path. Kept small: a few open chats at a time.
const cache = new Map<string, { parsed: Parsed; used: number }>();
const MAX_FILES = 64;

async function readNew(path: string): Promise<Parsed | null> {
  let size: number;
  try {
    size = (await stat(path)).size;
  } catch {
    return null;
  }
  let hit = cache.get(path);
  // A file that shrank was rewritten: start over.
  if (!hit || size < hit.parsed.offset) {
    hit = { parsed: fresh(), used: 0 };
    cache.set(path, hit);
  }
  hit.used = Date.now();
  const p = hit.parsed;
  if (size > p.offset) {
    const f = await open(path, "r");
    try {
      while (p.offset < size) {
        const len = Math.min(CHUNK, size - p.offset);
        const buf = Buffer.alloc(len);
        const { bytesRead } = await f.read(buf, 0, len, p.offset);
        if (!bytesRead) break;
        const data = p.rest.length ? Buffer.concat([p.rest, buf.subarray(0, bytesRead)]) : buf.subarray(0, bytesRead);
        const base = p.rest.length ? p.restAt : p.offset;
        let from = 0;
        for (let nl = data.indexOf(10); nl !== -1; nl = data.indexOf(10, from)) {
          if (nl > from) take(p, data.toString("utf8", from, nl), [base + from, nl - from]);
          from = nl + 1;
        }
        // The last line may still be being written: keep it for next time.
        p.rest = Buffer.from(data.subarray(from));
        p.restAt = base + from;
        p.offset += bytesRead;
      }
    } finally {
      await f.close();
    }
  }
  if (cache.size > MAX_FILES) {
    const old = [...cache.entries()].sort((a, b) => a[1].used - b[1].used).slice(0, cache.size - MAX_FILES);
    for (const [k] of old) cache.delete(k);
  }
  return p;
}

// ~/.claude/projects/<cwd with every non-alphanumeric as "-">/<session>.jsonl; other folders are searched if it isn't there.
const PROJECTS = join(process.env.CLAUDE_CONFIG_DIR ?? join(homedir(), ".claude"), "projects");
const dirs = new Map<string, string>();

async function sessionDir(sessionId: string): Promise<string | null> {
  const known = dirs.get(sessionId);
  if (known) return known;
  const guess = join(PROJECTS, WORK.replace(/[^a-zA-Z0-9]/g, "-"));
  const candidates = [guess, ...(await readdir(PROJECTS).catch(() => [] as string[])).map((d) => join(PROJECTS, d))];
  for (const dir of candidates) {
    if (await stat(join(dir, `${sessionId}.jsonl`)).then(() => true, () => false)) {
      dirs.set(sessionId, dir);
      return dir;
    }
  }
  return null;
}

type AgentMeta = { agentType?: string; description?: string; toolUseId?: string; parentAgentId?: string };

async function agentFiles(dir: string, sessionId: string) {
  const sub = join(dir, sessionId, "subagents");
  const names = await readdir(sub).catch(() => [] as string[]);
  const ids = names.filter((n) => n.startsWith("agent-") && n.endsWith(".jsonl")).map((n) => n.slice(6, -6));
  return Promise.all(
    ids.map(async (id) => {
      const meta: AgentMeta = await readFile(join(sub, `agent-${id}.meta.json`), "utf8").then(JSON.parse, () => ({}));
      return { id, path: join(sub, `agent-${id}.jsonl`), meta };
    }),
  );
}

function filePath(dir: string, sessionId: string, lane: string) {
  if (lane === "main") return join(dir, `${sessionId}.jsonl`);
  if (!/^[a-zA-Z0-9]+$/.test(lane)) return null;
  return join(dir, sessionId, "subagents", `agent-${lane}.jsonl`);
}

// running: is the main thread working now; agents: the Agent call ids of subagents that are running now.
export async function timeline(sessionId: string, running: boolean, agents: Set<string>): Promise<Timeline | null> {
  const dir = await sessionDir(sessionId);
  if (!dir) return null;
  const main = await readNew(join(dir, `${sessionId}.jsonl`));
  if (!main) return null;
  const files = await agentFiles(dir, sessionId);
  const parsed = await Promise.all(files.map(async (f) => ({ ...f, p: await readNew(f.path) })));
  const now = Date.now();

  // Which lane holds each Agent call, so a subagent hangs under the one that started it.
  const holder = new Map<string, string>();
  for (const [id] of main.tools) holder.set(id, "main");
  for (const a of parsed) for (const [id] of a.p?.tools ?? []) holder.set(id, a.id);

  const lanes: Lane[] = [
    { id: "main", title: "Chat", kind: "main", parent: null, toolUseId: null, start: main.first, end: running ? now : main.last, running, events: main.events },
  ];
  for (const a of parsed) {
    if (!a.p || !a.p.first) continue;
    const use = a.meta.toolUseId ?? null;
    const parent = (use && holder.get(use)) ?? a.meta.parentAgentId ?? "main";
    // Running: the SDK says so (background agents), or its Agent call is still open while the chat works.
    const call = use ? (parent === "main" ? main : parsed.find((x) => x.id === parent)?.p)?.tools.get(use) : undefined;
    const live = (!!use && agents.has(use)) || (running && !!call && call.end === undefined);
    lanes.push({
      id: a.id,
      title: a.meta.description || "Agent",
      kind: a.meta.agentType || "agent",
      parent,
      toolUseId: use,
      start: a.p.first,
      end: live ? now : a.p.last,
      running: live,
      events: a.p.events,
    });
  }
  // Which lane an Agent call started, for the bars that point at it.
  const started = new Map(lanes.filter((l) => l.toolUseId).map((l) => [l.toolUseId!, l.id] as const));
  for (const l of lanes) for (const e of l.events) if (e.k === "tool" && started.has(e.id)) e.agent = started.get(e.id);

  // The version: changes whenever any file grew or anything started or stopped.
  const v = [main.offset, ...parsed.map((a) => a.p?.offset ?? 0), running ? 1 : 0, ...[...agents].sort()].join(".");
  return { v, now, lanes: order(lanes) };
}

// The chat first, then every agent right under the lane that started it, in start order.
function order(lanes: Lane[]): Lane[] {
  const out: Lane[] = [];
  const kids = (id: string) => lanes.filter((l) => l.parent === id).sort((a, b) => a.start - b.start);
  const visit = (l: Lane) => {
    out.push(l);
    for (const k of kids(l.id)) visit(k);
  };
  for (const l of lanes.filter((l) => !l.parent || !lanes.some((x) => x.id === l.parent))) visit(l);
  return out;
}

async function readLoc(path: string, [off, len]: Loc): Promise<any> {
  const f = await open(path, "r");
  try {
    const buf = Buffer.alloc(len);
    await f.read(buf, 0, len, off);
    return JSON.parse(buf.toString("utf8"));
  } finally {
    await f.close();
  }
}

// One event in full: a tool call's input and output, or a message's whole text.
export async function timelineDetail(sessionId: string, lane: string, id: string): Promise<Detail | null> {
  const dir = await sessionDir(sessionId);
  const path = dir && filePath(dir, sessionId, lane);
  if (!path) return null;
  const p = await readNew(path);
  const loc = p?.locs.get(id);
  if (!p || !loc?.use) return null;
  const line = await readLoc(path, loc.use);
  const content = line.message?.content;

  const tool = p.tools.get(id);
  if (tool) {
    const use = (content as any[]).find((b) => b.type === "tool_use" && b.id === id);
    const input = JSON.stringify(use?.input ?? {}, null, 2);
    let output: string | null = null;
    let error = false;
    if (loc.result) {
      const r = await readLoc(path, loc.result);
      const b = (r.message?.content as any[]).find((x) => x.type === "tool_result" && x.tool_use_id === id);
      output = resultText(b?.content);
      error = !!b?.is_error;
    }
    const truncated = input.length > FULL || (output?.length ?? 0) > FULL;
    return { k: "tool", name: tool.name, input: cut(input, FULL), output: output === null ? null : cut(output, FULL), error, truncated };
  }

  let text: string;
  if (line.type === "assistant") {
    const n = Number(id.split(":").pop());
    text = (content as any[])[n]?.text ?? "";
  } else {
    text = stripReminders(typeof content === "string" ? content : (content as any[]).filter((b) => b.type === "text").map((b) => b.text).join("\n"));
  }
  return { k: "text", text: cut(text, FULL), truncated: text.length > FULL };
}
