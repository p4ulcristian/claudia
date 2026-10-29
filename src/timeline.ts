// The timeline: what the main thread and every subagent did, when, from the session files on disk.
// Claude Code appends to them as it goes (one file for the chat, one per subagent), so each file is
// read once and then only from where the last read stopped. Only a compact line per step is kept;
// a step's full input and output are read again from its place in the file when it is opened.
import { homedir } from "node:os";
import { join } from "node:path";
import { open, readdir, readFile, stat } from "node:fs/promises";
import { structuredPatch } from "diff";
import { WORK, resultText, sentMidTurn, stripReminders, toolLabel, toolTitle } from "./chat";

// Where a line sits in its file: byte offset and length.
export type Loc = [number, number];

export type TimelineEvent =
  // diff: lines added and removed, for file edits whose result carries a patch (a subagent's: counted
  // from the call's input).
  | { k: "tool"; id: string; name: string; title: string; start: number; end?: number; error?: boolean; agent?: string; diff?: { added: number; removed: number } }
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
  | { k: "tool"; name: string; input: string; output: string | null; error: boolean; truncated: boolean; patch: Patch | null }
  | { k: "text"; text: string; truncated: boolean };

// A file edit as Claude Code saved it with the result: hunks with line numbers and context; a new
// file comes as one hunk of added lines.
export type Hunk = { oldStart: number; oldLines: number; newStart: number; newLines: number; lines: string[] };
export type Patch = { file: string; created: boolean; hunks: Hunk[]; cut: boolean };

const PATCH_LINES = 5000;

function patchOf(r: any): Patch | null {
  if (!r || typeof r !== "object" || typeof r.filePath !== "string") return null;
  if (r.type === "create" && typeof r.content === "string") {
    const all = r.content.replace(/\n$/, "").split("\n");
    const lines = all.slice(0, PATCH_LINES).map((l: string) => "+" + l);
    return { file: r.filePath, created: true, hunks: [{ oldStart: 0, oldLines: 0, newStart: 1, newLines: all.length, lines }], cut: all.length > PATCH_LINES };
  }
  if (!Array.isArray(r.structuredPatch)) return null;
  let left = PATCH_LINES;
  const hunks: Hunk[] = [];
  for (const h of r.structuredPatch as Hunk[]) {
    if (left <= 0) break;
    hunks.push({ ...h, lines: h.lines.slice(0, left) });
    left -= h.lines.length;
  }
  return { file: r.filePath, created: false, hunks, cut: left < 0 };
}

// An edit's patch made from its call's input, for a file that keeps none of Claude Code's (a
// subagent's): the replaced text against the new, numbered within the text (where in the file it
// sits isn't known); a Write is all its lines added.
const EDIT_CALLS = /^(Write|Edit|MultiEdit|NotebookEdit)$/;
export function inputPatch(name: string, i: any): Patch | null {
  const file = String(i?.file_path ?? i?.notebook_path ?? "");
  if (!file || !EDIT_CALLS.test(name)) return null;
  if (name === "Write") return patchOf({ type: "create", filePath: file, content: String(i.content ?? "") });
  const pairs: [string, string][] = name === "MultiEdit" && Array.isArray(i.edits)
    ? i.edits.map((e: any) => [String(e?.old_string ?? ""), String(e?.new_string ?? "")])
    : name === "NotebookEdit" ? [["", String(i.new_source ?? "")]] : [[String(i.old_string ?? ""), String(i.new_string ?? "")]];
  const hunks: Hunk[] = pairs.flatMap(([a, b]) => structuredPatch("a", "b", a.endsWith("\n") ? a : `${a}\n`, b.endsWith("\n") ? b : `${b}\n`, "", "", { context: 3 }).hunks);
  return { file, created: false, hunks: hunks.map((h) => ({ ...h, lines: h.lines.slice(0, PATCH_LINES) })), cut: false };
}

function inputCounts(name: string, i: any): { added: number; removed: number } | undefined {
  const p = inputPatch(name, i);
  if (!p) return undefined;
  let added = 0, removed = 0;
  for (const h of p.hunks) for (const l of h.lines) {
    if (l[0] === "+") added++;
    else if (l[0] === "-") removed++;
  }
  return { added, removed };
}

function counts(r: any): { added: number; removed: number } | undefined {
  if (!r || typeof r !== "object" || typeof r.filePath !== "string") return undefined;
  if (r.type === "create" && typeof r.content === "string") return { added: r.content.replace(/\n$/, "").split("\n").length, removed: 0 };
  if (!Array.isArray(r.structuredPatch)) return undefined;
  let added = 0, removed = 0;
  for (const h of r.structuredPatch) for (const l of h.lines ?? []) {
    if (l[0] === "+") added++;
    else if (l[0] === "-") removed++;
  }
  return { added, removed };
}

const PREVIEW = 300;
// Larger inputs and outputs are cut here; nothing a person reads is near it.
const FULL = 1_000_000;
const CHUNK = 4 << 20;

type ToolEv = Extract<TimelineEvent, { k: "tool" }>;
// What src/agents.ts reads about the work Claude started from a file: an Agent call's choices, a
// background shell's command, and how each ended (its task notification, or a foreground agent's
// saved result).
export type Call =
  | { kind: "agent"; description: string; type: string; model: string | null; effort: string | null; background: boolean; isolation: string | null }
  | { kind: "shell"; description: string; command: string };
export type Notice = { task: string; status: string; at: number; tokens: number | null; tools: number | null; ms: number | null; loc: Loc };
// status: "completed" (a foreground agent's end), "async_launched" (a background one's start), "failed".
export type Outcome = { status: string; tokens: number | null; tools: number | null; ms: number | null; agent: string | null; model: string | null };
export type Parsed = {
  offset: number;
  rest: Buffer;
  restAt: number;
  first: number;
  last: number;
  events: TimelineEvent[];
  tools: Map<string, ToolEv>;
  // Where each event's line(s) are: the tool_use line, the tool_result line.
  locs: Map<string, { use?: Loc; result?: Loc }>;
  // What each tool call works on (a file's full path, a command, a query), for an agent's step line.
  targets: Map<string, string>;
  calls: Map<string, Call>;
  // By the tool_use id of the call that started the task; the newest notice wins.
  notices: Map<string, Notice>;
  outcomes: Map<string, Outcome>;
  // A subagent's edits waiting for their result, counted from their input (inputCounts).
  edits: Map<string, { added: number; removed: number }>;
  // The newest assistant message's model and its tokens (input, cache and output: the size the SDK
  // reports as an agent's total_tokens).
  model: string | null;
  tokens: number | null;
};

// The thing a tool call works on, whole (toolTitle shortens it for the chat's one-line steps).
function targetOf(name: string, i: any): string {
  switch (name) {
    case "Read": case "Write": case "Edit": case "MultiEdit": case "NotebookEdit": return String(i?.file_path || i?.notebook_path || "");
    case "Bash": return String(i?.command ?? "").split("\n")[0]!.slice(0, 300);
    case "Grep": case "Glob": return String(i?.pattern ?? "").slice(0, 200);
    case "WebSearch": return String(i?.query ?? "").slice(0, 200);
    case "WebFetch": return String(i?.url ?? "").slice(0, 300);
    default: return "";
  }
}

const tag = (s: string, t: string) => s.match(new RegExp(`<${t}>([^<]*)</${t}>`))?.[1]?.trim() ?? null;
const num = (s: string | null) => (s !== null && /^\d+$/.test(s) ? Number(s) : null);

// Task notifications in a raw line (a user message, or the queue entry before it): JSON-escaped
// there, which the ids, the status and the usage numbers never need.
function takeNotices(p: Parsed, line: string, loc: Loc, at: number) {
  for (const m of line.matchAll(/<task-notification>([\s\S]*?)<\/task-notification>/g)) {
    const body = m[1]!;
    const use = tag(body, "tool-use-id");
    const task = tag(body, "task-id");
    if (!use || !task) continue;
    const had = p.notices.get(use);
    if (had && had.at > at) continue;
    p.notices.set(use, {
      task, status: tag(body, "status") ?? "completed", at,
      tokens: num(tag(body, "subagent_tokens") ?? tag(body, "total_tokens")), tools: num(tag(body, "tool_uses")), ms: num(tag(body, "duration_ms")), loc,
    });
  }
}

const ms = (t: unknown) => (typeof t === "string" ? Date.parse(t) : NaN);
const cut = (s: string, n: number) => (s.length > n ? s.slice(0, n - 1) + "…" : s);

function fresh(): Parsed {
  return {
    offset: 0, rest: Buffer.alloc(0), restAt: 0, first: 0, last: 0, events: [], tools: new Map(), locs: new Map(),
    targets: new Map(), calls: new Map(), notices: new Map(), outcomes: new Map(), edits: new Map(), model: null, tokens: null,
  };
}

const str = (v: unknown) => (typeof v === "string" && v ? v : null);

// One line of a session file into events.
function take(p: Parsed, line: string, loc: Loc) {
  let d: any;
  try {
    d = JSON.parse(line);
  } catch {
    return;
  }
  if (line.includes("<task-notification>") && Number.isFinite(ms(d.timestamp))) takeNotices(p, line, loc, ms(d.timestamp));
  if (d.type !== "user" && d.type !== "assistant") return;
  if (d.isMeta || d.isCompactSummary) return;
  const at = ms(d.timestamp);
  if (!Number.isFinite(at)) return;
  p.first ||= at;
  p.last = Math.max(p.last, at);
  const content = d.message?.content;

  if (d.type === "assistant") {
    if (typeof d.message?.model === "string" && !d.message.model.startsWith("<")) p.model = d.message.model;
    const u = d.message?.usage;
    if (u && typeof u.input_tokens === "number")
      p.tokens = (u.input_tokens ?? 0) + (u.cache_creation_input_tokens ?? 0) + (u.cache_read_input_tokens ?? 0) + (u.output_tokens ?? 0);
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
        const target = targetOf(b.name, b.input);
        if (target) p.targets.set(b.id, target);
        // (only a subagent's lines: the chat's own results carry Claude Code's patch)
        if (d.isSidechain && EDIT_CALLS.test(b.name)) {
          const c = inputCounts(b.name, b.input);
          if (c) p.edits.set(b.id, c);
        }
        const i = b.input ?? {};
        if (b.name === "Agent" || b.name === "Task")
          p.calls.set(b.id, {
            kind: "agent", description: String(i.description ?? ""), type: String(i.subagent_type ?? "general-purpose"),
            model: str(i.model), effort: str(i.effort), background: !!i.run_in_background, isolation: str(i.isolation),
          });
        else if (b.name === "Bash" && i.run_in_background)
          p.calls.set(b.id, { kind: "shell", description: String(i.description ?? ""), command: String(i.command ?? "").slice(0, 4000) });
      }
    });
    return;
  }

  // A user line: tool results, or something a person (or Claude Code) said.
  if (Array.isArray(content) && content.some((b: any) => b.type === "tool_result")) {
    const results = content.filter((b: any) => b.type === "tool_result");
    for (const b of results) {
      const ev = p.tools.get(b.tool_use_id);
      if (!ev) continue;
      ev.end = at;
      ev.error = !!b.is_error;
      // The saved result (with a file edit's patch) belongs to the line's only tool result.
      // A subagent's file keeps no patch: its counts come from the call's input.
      if (!b.is_error) ev.diff = (results.length === 1 ? counts(d.toolUseResult) : undefined) ?? p.edits.get(b.tool_use_id);
      p.edits.delete(b.tool_use_id);
      p.locs.get(b.tool_use_id)!.result = loc;
      // A foreground agent's result says how it went; a background one's says it was started.
      const r = results.length === 1 ? d.toolUseResult : null;
      if (p.calls.get(b.tool_use_id)?.kind === "agent")
        p.outcomes.set(b.tool_use_id, {
          status: b.is_error ? "failed" : typeof r?.status === "string" ? r.status : "completed",
          tokens: typeof r?.totalTokens === "number" ? r.totalTokens : null,
          tools: typeof r?.totalToolUseCount === "number" ? r.totalToolUseCount : null,
          ms: typeof r?.totalDurationMs === "number" ? r.totalDurationMs : null,
          agent: typeof r?.agentId === "string" ? r.agentId : null,
          model: typeof r?.resolvedModel === "string" ? r.resolvedModel : null,
        });
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

const readNew = (path: string) => readLines(path, cache, fresh, take, MAX_FILES);

// A session file read on from where the last read stopped, each whole line handed to take with its
// place; what take builds is kept per path (up to max files, the least used dropped).
export type Tail = { offset: number; rest: Buffer; restAt: number };
export async function readLines<S extends Tail>(
  path: string,
  cache: Map<string, { parsed: S; used: number }>,
  fresh: () => S,
  take: (s: S, line: string, loc: Loc) => void,
  max: number,
): Promise<S | null> {
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
  if (cache.size > max) {
    const old = [...cache.entries()].sort((a, b) => a[1].used - b[1].used).slice(0, cache.size - max);
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

// Sent uuids the session file records under another entry, so the history may not show them under
// their own uuid: sends Claude Code took in together (one user entry listing them in
// mergedSendUuids) and sends taken in mid-turn (a queued_command attachment, see sentMidTurn).
export async function mergedSends(sessionId: string): Promise<Set<string>> {
  const merged = new Set<string>();
  const dir = await sessionDir(sessionId);
  if (!dir) return merged;
  const text = await readFile(join(dir, `${sessionId}.jsonl`), "utf8").catch(() => "");
  for (const line of text.split("\n")) {
    const together = line.includes('"mergedSendUuids"');
    if (!together && !line.includes('"queued_command"')) continue;
    try {
      const e = JSON.parse(line);
      if (together) for (const id of e.mergedSendUuids ?? []) merged.add(id);
      const sent = sentMidTurn(e);
      if (sent) merged.add(sent);
    } catch {}
  }
  return merged;
}

// worktreePath and worktreeBranch: an agent started in a git worktree of its own.
type AgentMeta = { agentType?: string; description?: string; toolUseId?: string; parentAgentId?: string; worktreePath?: string; worktreeBranch?: string; finished?: boolean };

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

// The chat's own file and every subagent's, read as far as they are written (src/agents.ts).
export type AgentFile = { id: string; path: string; meta: AgentMeta; p: Parsed | null };
export async function sessionParts(sessionId: string): Promise<{ main: Parsed; mainPath: string; lanes: AgentFile[] } | null> {
  const dir = await sessionDir(sessionId);
  if (!dir) return null;
  const mainPath = join(dir, `${sessionId}.jsonl`);
  const main = await readNew(mainPath);
  if (!main) return null;
  const files = await agentFiles(dir, sessionId);
  const lanes = await Promise.all(files.map(async (f) => ({ ...f, p: await readNew(f.path) })));
  return { main, mainPath, lanes };
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

export async function readLoc(path: string, [off, len]: Loc): Promise<any> {
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
    let patch: Patch | null = null;
    if (loc.result) {
      const r = await readLoc(path, loc.result);
      const b = (r.message?.content as any[]).find((x) => x.type === "tool_result" && x.tool_use_id === id);
      output = resultText(b?.content);
      error = !!b?.is_error;
      if (tool.diff) patch = patchOf(r.toolUseResult) ?? inputPatch(String(use?.name ?? ""), use?.input);
    }
    const truncated = input.length > FULL || (output?.length ?? 0) > FULL;
    return { k: "tool", name: tool.name, input: cut(input, FULL), output: output === null ? null : cut(output, FULL), error, truncated, patch };
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
