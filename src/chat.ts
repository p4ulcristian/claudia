// One chat = one Claude Code session, driven through the Agent SDK.
import { getSessionMessages, getSessionInfo, type SDKMessage, type PermissionResult } from "@anthropic-ai/claude-agent-sdk";
import { homedir } from "node:os";
import { join, resolve } from "node:path";
import { stat } from "node:fs/promises";
import { readFileSync, statSync } from "node:fs";
import { store } from "./db";
import { noteLimits, refreshSoon } from "./meta";
import { account, type Account } from "./account";
import { PICTURE_MCP, WORKSPACE_MCP, SYSTEM_APPEND } from "./prompt";
import type { HostClient, RemoteSession, SessionHandler } from "./host-client";
import type { LiveSession, RpcKind } from "./host-protocol";
import { EDIT_TOOLS, blobState, editedPath, repoRoot, lineCounts, projectOf, readState, snapshotTree, treeChanges, type ChangeMeta, type FileState } from "./changes";
import { DATA_DIR } from "./db";
import { applyDraw, describe, emptyScene, type DrawInput, type Scene } from "./scene";

// Decision 6: every chat runs in ~/Work, where the user's full memory lives.
export const WORK = process.env.CLAUDIA_CWD ?? join(homedir(), "Work");
const IDLE_MS = 15 * 60_000;
const DRAW_TOOL = "mcp__picture__draw";
const FOLDER_TOOL = "mcp__workspace__set_folder";

export type Item =
  // queued: sent while Claude was working and not taken in yet; it waits below the chat until then.
  | { k: "user"; uuid: string; text: string; images: number; queued?: boolean }
  | { k: "text"; id: string; text: string }
  | { k: "tool"; id: string; name: string; title: string; detail: string; result?: { error: boolean; text: string } }
  | { k: "note"; id: string; text: string };

// Work Claude runs beside the chat: subagents and background shell commands.
export type Task = {
  id: string;
  toolUseId?: string;
  description: string;
  kind: string;
  status: "running" | "completed" | "failed" | "stopped";
  background: boolean;
  startedAt: number;
  endedAt?: number;
  tokens?: number;
  toolUses?: number;
  lastTool?: string;
  summary?: string;
  outputFile?: string;
  // The agent task that launched it (a subagent's shell); absent for the main thread's own.
  parentId?: string;
  // Housekeeping the SDK marks as not activity: never shown.
  ambient?: boolean;
};
export type Context = { used: number; max: number; percent: number; model: string };
export type Settings = { model: string | null; effort: string | null; folder: string | null; color: string | null };

export type Question = {
  question: string;
  header: string;
  multiSelect?: boolean;
  options: { label: string; description: string; preview?: string }[];
};
export type Ask = { id: string; questions: Question[] };
export type Permit = { id: string; tool: string; title: string; description: string; detail: string };

export type ChatEvent =
  | { t: "user"; item: Item }
  // A queued message was taken in (it joins the chat where Claude read it) or dropped.
  | { t: "queue"; uuid: string; state: "started" | "cancelled" }
  | { t: "delta"; text: string }
  | { t: "items"; items: Item[] }
  | { t: "tool_result"; id: string; error: boolean; text: string }
  | { t: "scene"; scene: Scene; changed: string[]; turn: string }
  | { t: "ask"; ask: Ask }
  | { t: "ask_done"; id: string }
  | { t: "permit"; permit: Permit }
  | { t: "permit_done"; id: string }
  | { t: "status"; running: boolean; note?: string }
  | { t: "turn_end"; turn: string; drew: boolean; error?: string; stopped?: boolean }
  | { t: "error"; text: string }
  | { t: "tasks"; tasks: Task[] }
  | { t: "sub"; parent: string; items: Item[] }
  | { t: "sub_result"; parent: string; id: string; error: boolean; text: string }
  | { t: "context"; context: Context }
  | { t: "settings"; settings: Settings; model?: string }
  | { t: "changes"; changes: ChangeMeta[] };

const short = (s: string, n: number) => (s.length > n ? s.slice(0, n - 1) + "…" : s);
const base = (p: unknown) => String(p ?? "").split("/").pop() ?? "";

// One line per tool call, so the chat stays readable.
export function toolTitle(name: string, input: any): string {
  const i = input ?? {};
  switch (name) {
    case "Bash": return short(i.description || i.command || "", 90);
    case "Read": case "Write": case "Edit": case "NotebookEdit": return base(i.file_path || i.notebook_path);
    case "Grep": case "Glob": return short(i.pattern ?? "", 80);
    case "WebFetch": return short(i.url ?? "", 80);
    case "WebSearch": return short(i.query ?? "", 80);
    case "Agent": case "Task": return short(i.description ?? "", 80);
    case "TodoWrite": return `${(i.todos ?? []).length} todos`;
    case DRAW_TOOL: return i.title ? `picture: ${i.title}` : "picture";
    case FOLDER_TOOL: return String(i.path ?? "").replace(homedir(), "~");
    case "AskUserQuestion": return (i.questions ?? []).map((q: Question) => q.header).join(", ");
    default: return "";
  }
}

export function toolLabel(name: string) {
  if (name === DRAW_TOOL) return "Draw";
  if (name === FOLDER_TOOL) return "Folder";
  if (name === "AskUserQuestion") return "Ask";
  return name.startsWith("mcp__") ? name.split("__").slice(1).join(" ") : name;
}

export const stripReminders = (s: string) => s.replace(/<system-reminder>[\s\S]*?<\/system-reminder>/g, "").trim();

// An answer to AskUserQuestion, as the user's own message in the chat.
function answerText(questions: Question[], answers: Record<string, string>) {
  const pairs = questions.map((q) => [q.header || q.question, answers[q.question] ?? ""] as const).filter(([, a]) => a);
  return pairs.length === 1 ? pairs[0][1] : pairs.map(([h, a]) => `**${h}:** ${a}`).join("\n");
}

// Slash commands are stored as tagged user text; show the command and its output plainly.
// Claude Code stores "background task finished" notices as user messages; show them as a note.
export function taskNotice(text: string): string | null {
  if (!text.startsWith("<task-notification>")) return null;
  const summary = text.match(/<summary>([\s\S]*?)<\/summary>/)?.[1]?.trim();
  return summary ?? "A background task finished.";
}

export function commandParts(text: string): { command?: string; output?: string } | null {
  const name = text.match(/<command-name>([\s\S]*?)<\/command-name>/)?.[1]?.trim();
  const args = text.match(/<command-args>([\s\S]*?)<\/command-args>/)?.[1]?.trim();
  const out = text.match(/<local-command-std(?:out|err)>([\s\S]*?)<\/local-command-std(?:out|err)>/)?.[1]?.trim();
  if (!name && out === undefined && !/<command-message>/.test(text)) return null;
  return { command: name ? `${name.startsWith("/") ? name : "/" + name}${args ? " " + args : ""}` : undefined, output: out };
}

export function resultText(content: unknown): string {
  if (typeof content === "string") return content;
  if (Array.isArray(content)) return content.map((b: any) => (b.type === "text" ? b.text : b.type === "image" ? "[image]" : "")).join("\n");
  return "";
}

// Messages API content → chat items. Thinking is left out on purpose.
function assistantItems(msgId: string, content: any[]): Item[] {
  const out: Item[] = [];
  content.forEach((b, n) => {
    if (b.type === "text" && b.text.trim()) out.push({ k: "text", id: `${msgId}:${n}`, text: b.text });
    if (b.type === "tool_use")
      out.push({ k: "tool", id: b.id, name: toolLabel(b.name), title: toolTitle(b.name, b.input), detail: short(JSON.stringify(b.input, null, 2), 4000) });
  });
  return out;
}

export async function loadHistory(sessionId: string): Promise<Item[]> {
  const msgs = await getSessionMessages(sessionId, { dir: WORK });
  const items: Item[] = [];
  const tools = new Map<string, Extract<Item, { k: "tool" }>>();
  const asked = new Map<string, Question[]>();
  for (const m of msgs) {
    if (m.parent_tool_use_id) continue;
    const msg = m.message as any;
    if (m.type === "assistant") {
      for (const b of msg?.content ?? []) if (b.type === "tool_use" && b.name === "AskUserQuestion") asked.set(b.id, b.input?.questions ?? []);
      for (const it of assistantItems(m.uuid, msg?.content ?? [])) {
        items.push(it);
        if (it.k === "tool") tools.set(it.id, it);
      }
    } else if (m.type === "user") {
      const content = msg?.content;
      if (typeof content === "string") {
        const text = stripReminders(content);
        const notice = taskNotice(text);
        const cmd = notice ? null : commandParts(text);
        if (notice) items.push({ k: "note", id: m.uuid, text: notice });
        else if (cmd) {
          if (cmd.command) items.push({ k: "user", uuid: m.uuid, text: cmd.command, images: 0 });
          if (cmd.output) items.push({ k: "note", id: m.uuid, text: cmd.output });
        } else if (text) items.push({ k: "user", uuid: m.uuid, text, images: 0 });
        continue;
      }
      const texts: string[] = [];
      let images = 0;
      for (const b of content ?? []) {
        if (b.type === "tool_result") {
          const t = tools.get(b.tool_use_id);
          if (t) t.result = { error: !!b.is_error, text: short(resultText(b.content), 4000) };
          const qs = asked.get(b.tool_use_id);
          if (qs && !b.is_error) {
            // The result reads: … "question"="answer" …
            const answers: Record<string, string> = {};
            for (const [, q, a] of resultText(b.content).matchAll(/"((?:[^"\\]|\\.)*)"="((?:[^"\\]|\\.)*)"/g)) answers[q] = a;
            const text = answerText(qs, answers);
            if (text) items.push({ k: "user", uuid: `answer-${b.tool_use_id}`, text, images: 0 });
          }
        } else if (b.type === "text") texts.push(stripReminders(b.text));
        else if (b.type === "image") images++;
      }
      const text = texts.filter(Boolean).join("\n");
      const notice = taskNotice(text);
      if (notice) items.push({ k: "note", id: m.uuid, text: notice });
      else if (text || images) items.push({ k: "user", uuid: m.uuid, text, images });
    }
  }
  return items;
}

// The saved templates, for Claude: name, description, and the parts each one has.
function templateList(): string {
  const all = store.templates();
  if (!all.length) return "No templates saved yet.";
  return all.map((t) => `- "${t.name}": ${t.description || "(no description)"}${Object.keys(t.parts).length ? ` Parts: ${Object.keys(t.parts).join(", ")}.` : ""}`).join("\n")
    + '\nDraw one with blocks: [{ id, template: "<name>", parts? }]; change one with save_template.';
}

const TOOLS = { picture: PICTURE_MCP, workspace: WORKSPACE_MCP };
const TOOLS_VERSION = Bun.hash(JSON.stringify(TOOLS)).toString(36);

// The session host the chats' Claude Code processes live in (set by the runner at start).
let host: HostClient | null = null;
export function useHost(h: HostClient) { host = h; }

type Waiting<T> = { value: T; resolve: (r: PermissionResult) => void; input: Record<string, unknown> };

export class Chat implements SessionHandler {
  private q: RemoteSession | null = null;
  // The account (token) name this chat's Claude Code process was started on.
  private account = "";
  private idle: Timer | null = null;
  private listeners = new Set<(e: ChatEvent) => void>();
  private turn = "";
  private drew = false;
  private stopping = false;
  // Rebuilding state from the host's buffer after a runner restart: no side effects.
  private replaying = false;
  // This session's tools are older than the runner's and couldn't be swapped: start Claude Code
  // again (resumed) once the turn and its background work are done.
  private staleTools = false;
  // Files touched since the last turn ended: their content before the first touch.
  private before = new Map<string, FileState>();
  // Git repos snapshotted at the start of the turn (or when the folder was set): repo root → tree.
  private trees = new Map<string, Promise<string | null>>();
  running = false;
  since = 0;
  partial = "";
  scene: Scene;
  asks = new Map<string, Waiting<Ask>>();
  permits = new Map<string, Waiting<Permit>>();
  tasks = new Map<string, Task>();
  // Sent messages Claude Code hasn't written to the session file yet (a message sent mid-turn waits
  // until Claude takes it in), so a chat opened again in that time still shows them. Keyed by uuid,
  // which the session file keeps.
  private unsaved = new Map<string, Item>();
  // Subagent transcripts, by the tool_use id of the Agent call that started them.
  subs = new Map<string, Item[]>();
  context: Context | null = null;
  settings: Settings;
  model = "";

  constructor(readonly id: string, private onChange: () => void) {
    this.scene = store.pictures(id).at(-1)?.scene ?? emptyScene();
    this.settings = store.settings(id);
  }

  async setSettings(next: Partial<Settings>) {
    const prev = this.settings;
    this.settings = { ...prev, ...next };
    store.saveSettings(this.id, this.settings);
    if (this.q) {
      if ("model" in next) await this.q.setModel(next.model ?? undefined).catch(() => {});
      if ("effort" in next) await this.q.applyFlagSettings({ effortLevel: (next.effort ?? null) as any }).catch(() => {});
    }
    this.emit({ t: "settings", settings: this.settings, model: this.model });
  }

  // The project Claude works in. The session stays in ~/Work; this is what Claudia shows and diffs follow.
  setFolder(folder: string) {
    if (this.settings.folder === folder) return;
    if (this.running) this.snapshot(folder);
    this.settings = { ...this.settings, folder };
    store.saveSettings(this.id, this.settings);
    this.emit({ t: "settings", settings: this.settings, model: this.model });
    this.onChange();
  }

  private async folderTool(path: string): Promise<string> {
    const abs = resolve(WORK, path.replace(/^~(?=\/|$)/, homedir()));
    const st = await stat(abs).catch(() => null);
    if (!st?.isDirectory()) return `No folder at ${abs}. Nothing changed.`;
    this.setFolder(abs);
    const read = async (f: string) => (await Bun.file(join(abs, f)).exists() ? (await Bun.file(join(abs, f)).text()).slice(0, 6000) : "");
    const claudeMd = (await read("CLAUDE.md")) || (await read(".claude/CLAUDE.md"));
    const git = Bun.spawnSync(["git", "-C", abs, "status", "--short", "--branch"], { stderr: "ignore" });
    const status = git.success ? git.stdout.toString().split("\n").slice(0, 40).join("\n") : "(not a git repository)";
    return [
      `Working folder is now ${abs}. Run commands there (cd ${abs} && …).`,
      claudeMd ? `\n<claude-md path="${abs}/CLAUDE.md">\n${claudeMd}\n</claude-md>` : "\n(no CLAUDE.md in this folder)",
      `\n<git-status>\n${status}\n</git-status>`,
    ].join("\n");
  }

  private indexFile(root: string) {
    return join(DATA_DIR, "index", `${this.id}-${Bun.hash(root).toString(36)}`);
  }

  private async snapshot(folder: string) {
    const root = await repoRoot(folder);
    if (!root || this.trees.has(root)) return;
    const tree = snapshotTree(root, this.indexFile(root)).catch(() => null);
    this.trees.set(root, tree);
    tree.then((t) => this.trees.get(root) === tree && this.q?.kv(`tree:${root}`, t));
  }

  // Before Claude first touches a file in this turn, keep what it looked like.
  private async beforeEdit(input: any) {
    const path = editedPath(input);
    if (path && !this.before.has(path)) {
      const state = await readState(path);
      this.before.set(path, state);
      this.q?.kv(`before:${path}`, state);
    }
    return { continue: true };
  }

  private async afterEdit(input: any) {
    const path = editedPath(input);
    if (path && !this.settings.folder) this.setFolder(projectOf(path));
    return { continue: true };
  }

  // After a turn: compare every touched file with its copy, store the changes.
  private async finishChanges(turn: string) {
    const trees = [...this.trees];
    this.trees = new Map();
    for (const [root] of trees) this.q?.kv(`tree:${root}`);
    // Shell edits inside snapshotted repos: compare the folder with its start-of-turn tree.
    for (const [root, start] of trees) {
      const a = await start;
      if (!a) continue;
      const b = await snapshotTree(root, this.indexFile(root));
      if (!b || a === b) continue;
      for (const { rel, abs } of await treeChanges(root, a, b))
        if (!this.before.has(abs)) this.before.set(abs, await blobState(root, a, rel));
    }
    if (!this.before.size) return;
    const touched = [...this.before];
    this.before = new Map();
    for (const [path] of touched) this.q?.kv(`before:${path}`);
    const made: ChangeMeta[] = [];
    for (const [path, before] of touched) {
      const after = await readState(path);
      if (before.text === after.text && before.skipped === after.skipped) continue;
      made.push(store.addChange(this.id, turn, path, before, after, lineCounts(before.text, after.text)));
    }
    if (!made.length) return;
    this.emit({ t: "changes", changes: made });
  }

  async stopTask(taskId: string) {
    await this.q?.stopTask(taskId).catch(() => {});
    // A task whose agent was stopped never reports its end: Stop always
    // clears it here, whether or not the SDK still knew it.
    const t = this.tasks.get(taskId);
    if (t && t.status === "running") this.patchTask(taskId, { status: "stopped", endedAt: Date.now() });
  }

  private restartIfStale() {
    if (!this.staleTools || this.running || [...this.tasks.values()].some((t) => t.status === "running")) return;
    this.staleTools = false;
    this.close();
  }

  private emitTasks() {
    this.emit({ t: "tasks", tasks: [...this.tasks.values()] });
    this.onChange();
    if (!this.replaying) this.restartIfStale();
  }

  private patchTask(id: string, patch: Partial<Task>) {
    const t = this.tasks.get(id);
    if (t) {
      // A finished agent sent another message runs again without a new
      // task_started: its timer restarts from now.
      const again = patch.status === "running" && t.status !== "running";
      this.tasks.set(id, { ...t, ...patch, ...(again ? { startedAt: Date.now(), endedAt: undefined } : {}) });
    } else if (patch.status === "running") {
      // Tasks live in memory only: one resumed after a restart of this
      // service shows up on its first progress.
      this.tasks.set(id, { id, description: "Agent", kind: "task", background: true, startedAt: Date.now(), ...patch, status: "running" });
    }
    this.emitTasks();
  }

  on(fn: (e: ChatEvent) => void) {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  private emit(e: ChatEvent) {
    for (const fn of this.listeners) fn(e);
    // The chat list shows which chats wait on the user.
    if (e.t === "ask" || e.t === "permit" || e.t === "ask_done" || e.t === "permit_done") this.onChange();
  }

  // Subagents still working (shells left out: a dev server would count forever).
  get agents() {
    let n = 0;
    for (const t of this.tasks.values()) if (t.status === "running" && !t.ambient && !/shell|bash|local/i.test(t.kind)) n++;
    return n;
  }

  // A question or a permission ask is open: the turn waits on the user.
  get waiting() {
    return this.asks.size + this.permits.size > 0;
  }

  async send(text: string, images: { media_type: string; data: string }[] = []) {
    const uuid = crypto.randomUUID();
    const content: any[] = images.map((im) => ({ type: "image", source: { type: "base64", media_type: im.media_type, data: im.data } }));
    if (text) content.push({ type: "text", text });
    const acct = await account().catch((err) => {
      this.emit({ t: "error", text: String(err?.message ?? err) });
      return null;
    });
    if (!acct) return;
    // The token was replaced since this chat's Claude Code started: start it again on the new one.
    if (this.q && !this.running && this.account !== acct.name) this.close();
    if (!this.q) await this.start(acct);
    this.turn = uuid;
    this.drew = false;
    this.q!.kv("turn", uuid);
    this.q!.kv("drew", false);
    if (this.settings.folder) this.snapshot(this.settings.folder);
    const queued = this.running;
    this.setRunning(true);
    const item: Item = { k: "user", uuid, text, images: images.length, ...(queued ? { queued } : {}) };
    this.unsaved.set(uuid, item);
    this.q!.kv(`unsaved:${uuid}`, item);
    this.emit({ t: "user", item });
    this.q!.push({ type: "user", uuid: uuid as any, session_id: this.id, parent_tool_use_id: null, message: { role: "user", content } });
  }

  async stop() {
    for (const [id, w] of this.asks) { w.resolve({ behavior: "deny", message: "The user stopped the turn.", interrupt: true }); this.emit({ t: "ask_done", id }); }
    for (const [id, w] of this.permits) { w.resolve({ behavior: "deny", message: "The user stopped the turn.", interrupt: true }); this.emit({ t: "permit_done", id }); }
    this.asks.clear();
    this.permits.clear();
    if (this.running) this.stopping = true;
    await this.q?.interrupt().catch(() => {});
  }

  answer(id: string, answers: Record<string, string>) {
    const w = this.asks.get(id);
    if (!w) return;
    this.asks.delete(id);
    w.resolve({ behavior: "allow", updatedInput: { ...w.input, answers } });
    this.emit({ t: "ask_done", id });
    const text = answerText(w.value.questions, answers);
    if (text) this.emit({ t: "user", item: { k: "user", uuid: `answer-${id}`, text, images: 0 } });
  }

  permit(id: string, allow: boolean) {
    const w = this.permits.get(id);
    if (!w) return;
    this.permits.delete(id);
    w.resolve(allow ? { behavior: "allow", updatedInput: w.input } : { behavior: "deny", message: "The user said no." });
    this.emit({ t: "permit_done", id });
  }

  // Claude Code reports each sent message as queued, then started (taken in) or cancelled.
  private lifecycle(m: { command_uuid?: string; state?: string }) {
    const it = m.command_uuid ? this.unsaved.get(m.command_uuid) : undefined;
    if (!it || it.k !== "user" || !it.queued) return;
    if (m.state === "started") {
      delete it.queued;
      this.q?.kv(`unsaved:${it.uuid}`, it);
      this.emit({ t: "queue", uuid: it.uuid, state: "started" });
    } else if (m.state === "cancelled") {
      this.unsaved.delete(it.uuid);
      this.q?.kv(`unsaved:${it.uuid}`);
      this.emit({ t: "queue", uuid: it.uuid, state: "cancelled" });
    }
  }

  // The loaded history plus what was sent but isn't in the session file yet.
  // merged = sent uuids Claude Code saved inside another entry (mergedSends).
  withUnsaved(items: Item[], merged = new Set<string>()): Item[] {
    const saved = new Set([...merged, ...items.flatMap((it) => (it.k === "user" ? [it.uuid] : []))]);
    for (const id of this.unsaved.keys())
      if (saved.has(id)) {
        this.unsaved.delete(id);
        this.q?.kv(`unsaved:${id}`);
      }
    return [...items, ...this.unsaved.values()];
  }

  close() {
    this.q?.close();
    this.q = null;
  }

  private setRunning(r: boolean) {
    if (this.running === r) return;
    if (this.replaying) {
      this.running = r;
      return;
    }
    this.running = r;
    if (r) {
      this.since = Date.now();
      this.q?.kv("since", this.since);
    }
    this.emit({ t: "status", running: r });
    // A turn that ended took in everything sent during it (a send not taken in starts the next turn),
    // even sends Claude Code merged and never reported as started.
    if (!r) for (const it of this.unsaved.values()) if (it.k === "user" && it.queued) this.lifecycle({ command_uuid: it.uuid, state: "started" });
    this.onChange();
    if (this.idle) clearTimeout(this.idle);
    if (!r) this.idle = setTimeout(() => !this.running && this.close(), IDLE_MS);
  }

  private draw(input: DrawInput): string {
    // Blocks drawn from a saved template get its page and parts (the template's own parts first).
    const missing: string[] = [];
    for (const b of input.blocks ?? []) {
      if (!b.template || b.html !== undefined) continue;
      const t = store.template(b.template);
      if (!t) { missing.push(b.template); continue; }
      b.template = t.name;
      b.html = t.html;
      b.parts = { ...t.parts, ...(b.parts ?? {}) };
      b.name ??= t.name;
    }
    if (missing.length) input = { ...input, blocks: input.blocks!.filter((b) => !b.template || !missing.includes(b.template)) };
    // A code block given a file and no code shows the whole file, read here (as it is now).
    const unread: string[] = [];
    for (const b of input.blocks ?? []) {
      const d = b.data;
      const isCode = b.form === "code" || (!b.form && this.scene.blocks?.some((x) => x.id === b.id && x.kind === "form" && x.form === "code"));
      if (!isCode || !d || typeof d.file !== "string" || d.code !== undefined) continue;
      const text = this.readForPicture(d.file);
      if (typeof text === "string") b.data = { ...d, code: text, line: 1, diff: false, error: null };
      else { b.data = { ...d, code: "", error: text.error }; unread.push(text.error); }
    }
    // A files block reads its folder live (src/files.ts): the root is made absolute here, which is
    // also what lets the page read inside it.
    for (const b of input.blocks ?? []) {
      const isFiles = b.form === "files" || (!b.form && this.scene.blocks?.some((x) => x.id === b.id && x.kind === "form" && x.form === "files"));
      if (!isFiles) continue;
      const known = this.scene.blocks?.find((x) => x.id === b.id && x.kind === "form");
      const given = typeof b.data?.root === "string" ? b.data.root : undefined;
      if (given === undefined && known) continue;
      const base = this.settings.folder ?? WORK;
      b.data = { ...b.data, root: resolve(base, (given ?? ".").replace(/^~(?=\/|$)/, homedir())) };
    }
    const { scene, changed } = applyDraw(this.scene, input);
    this.scene = scene;
    this.drew = true;
    this.q?.kv("drew", true);
    store.savePicture(this.id, this.turn, scene, changed);
    this.emit({ t: "scene", scene, changed, turn: this.turn });
    return `Picture updated: ${describe(scene)}${missing.length ? `. No template named ${missing.map((m) => `"${m}"`).join(", ")} (see the templates tool).` : ""}${unread.length ? `. ${unread.join(". ")}` : ""}`;
  }

  // A file for a code block: its path relative to the working folder (or ~/Work), at most 2 MB of text.
  private readForPicture(path: string): string | { error: string } {
    const abs = resolve(this.settings.folder ?? WORK, path.replace(/^~(?=\/|$)/, homedir()));
    try {
      const st = statSync(abs);
      if (!st.isFile()) return { error: `Not a file: ${path}` };
      if (st.size > 2_000_000) return { error: `Too big to show whole: ${path} (${Math.round(st.size / 1000)} KB); send the lines as code instead` };
      const text = readFileSync(abs, "utf8");
      if (text.includes("\0")) return { error: `Not a text file: ${path}` };
      return text;
    } catch {
      return { error: `Can't read ${path}` };
    }
  }

  // Save a block of the picture as a template (from Claude's save_template tool or the Save button).
  saveTemplate(name: string, from: { block?: string; html?: string; parts?: Record<string, string>; description?: string }): string {
    name = name.trim().slice(0, 80);
    if (!name) return "A template needs a name.";
    const old = store.template(name);
    let html = from.html, parts = from.parts;
    if (from.block) {
      const b = this.scene.blocks?.find((x) => x.id === from.block);
      if (b?.kind !== "html") return `No freeform block "${from.block}" in the picture.`;
      html ??= b.html;
      parts = { ...b.parts, ...(parts ?? {}) };
    }
    html ??= old?.html;
    if (!html) return `No template "${name}" yet: give its html or a block to take it from.`;
    store.saveTemplate({ name: old?.name ?? name, html, parts: parts ?? old?.parts ?? {}, description: from.description ?? old?.description ?? "" });
    return `${old ? "Updated" : "Saved"} template "${old?.name ?? name}".`;
  }

  private async start(acct: Account) {
    if (!host) throw new Error("No session host.");
    const exists = !!(await getSessionInfo(this.id, { dir: WORK }).catch(() => undefined));
    if (!exists) store.addChat(this.id);
    this.account = acct.name;
    this.q = host.start(this.id, {
      options: {
        cwd: WORK,
        env: acct.env,
        ...(exists ? { resume: this.id } : { sessionId: this.id }),
        systemPrompt: { type: "preset", preset: "claude_code", append: SYSTEM_APPEND },
        allowedTools: [DRAW_TOOL, "mcp__picture__templates", "mcp__picture__save_template", FOLDER_TOOL],
        // Same as the user's terminal Claude (defaultMode bypassPermissions).
        permissionMode: "bypassPermissions",
        allowDangerouslySkipPermissions: true,
        includePartialMessages: true,
        ...(this.settings.model ? { model: this.settings.model } : {}),
        ...(this.settings.effort ? { effort: this.settings.effort } : {}),
      },
      mcp: TOOLS,
      hooks: { PreToolUse: [{ matcher: EDIT_TOOLS }], PostToolUse: [{ matcher: EDIT_TOOLS }] },
    }, this);
    this.q.kv("account", acct.name);
    this.q.kv("tools", TOOLS_VERSION);
  }

  // The host asks: a tool call, a hook, or whether a tool may run.
  async onRpc(kind: RpcKind, args: any, signal: AbortSignal): Promise<any> {
    if (kind === "mcp") {
      if (args.server === "picture" && args.tool === "draw") return { content: [{ type: "text", text: this.draw(args.args as DrawInput) }] };
      if (args.server === "picture" && args.tool === "templates") return { content: [{ type: "text", text: templateList() }] };
      if (args.server === "picture" && args.tool === "save_template") {
        const a = (args.args ?? {}) as { name?: string; block?: string; html?: string; parts?: Record<string, string>; description?: string };
        return { content: [{ type: "text", text: this.saveTemplate(String(a.name ?? ""), a) }] };
      }
      if (args.server === "workspace" && args.tool === "set_folder") return { content: [{ type: "text", text: await this.folderTool(String(args.args?.path ?? "")) }] };
      throw new Error(`No tool ${args.server}/${args.tool}`);
    }
    if (kind === "hook") {
      if (args.event === "PreToolUse") return this.beforeEdit(args.input?.tool_input);
      if (args.event === "PostToolUse") return this.afterEdit(args.input?.tool_input);
      return { continue: true };
    }
    return this.ask(args.name, args.input ?? {}, { signal, toolUseID: args.toolUseID, title: args.title, description: args.description });
  }

  onMessage(m: SDKMessage) {
    this.handle(m);
  }

  // The session ended by itself (Claude Code exited, failed, or the host restarted).
  onEnd(error?: string) {
    if (error) this.emit({ t: "error", text: error });
    this.q = null;
    this.partial = "";
    this.setRunning(false);
  }

  // After a runner restart: the host still runs this chat's Claude Code. Take back what was kept
  // there, replay this turn's messages, and carry on.
  adopt(live: LiveSession, session: RemoteSession) {
    this.q = session;
    const kv = live.kv;
    this.account = String(kv.account ?? "");
    this.turn = String(kv.turn ?? "");
    this.drew = !!kv.drew;
    for (const [k, v] of Object.entries(kv)) {
      if (k.startsWith("before:")) this.before.set(k.slice(7), v as FileState);
      else if (k.startsWith("tree:")) this.trees.set(k.slice(5), Promise.resolve(v as string | null));
      else if (k.startsWith("unsaved:")) this.unsaved.set(k.slice(8), v as Item);
    }
    this.replaying = true;
    try {
      for (const m of live.buffer) this.handle(m);
    } finally {
      this.replaying = false;
    }
    this.running = false;
    this.setRunning(live.busy);
    // The runner's tools changed since this session started (e.g. a new card kind): hand it the new ones.
    if (kv.tools !== TOOLS_VERSION)
      session.setMcp(TOOLS).then(
        () => session.kv("tools", TOOLS_VERSION),
        (err) => {
          console.error(`[${this.id.slice(0, 8)}] tools not swapped (${err?.message ?? err}); restarting after this turn`);
          this.staleTools = true;
          this.restartIfStale();
        },
      );
    if (live.busy) this.since = Number(kv.since ?? Date.now());
    else if (!this.idle) this.idle = setTimeout(() => !this.running && this.close(), IDLE_MS);
    return this;
  }

  private ask(name: string, input: Record<string, unknown>, opts: { signal: AbortSignal; toolUseID: string; title?: string; description?: string }): Promise<PermissionResult> {
    return new Promise((resolve) => {
      const id = opts.toolUseID;
      const cancel = () => {
        if (this.asks.delete(id)) this.emit({ t: "ask_done", id });
        if (this.permits.delete(id)) this.emit({ t: "permit_done", id });
      };
      opts.signal.addEventListener("abort", cancel, { once: true });
      if (name === "AskUserQuestion") {
        const ask = { id, questions: (input.questions as Question[]) ?? [] };
        this.asks.set(id, { value: ask, resolve, input });
        this.emit({ t: "ask", ask });
      } else {
        const permit = {
          id,
          tool: toolLabel(name),
          title: opts.title ?? `Claude wants to use ${toolLabel(name)}`,
          description: opts.description ?? toolTitle(name, input),
          detail: short(JSON.stringify(input, null, 2), 2000),
        };
        this.permits.set(id, { value: permit, resolve, input });
        this.emit({ t: "permit", permit });
      }
    });
  }

  private sub(parent: string, items: Item[]) {
    if (!items.length) return;
    const list = this.subs.get(parent) ?? [];
    list.push(...items);
    this.subs.set(parent, list.slice(-300));
    this.emit({ t: "sub", parent, items });
  }

  private subResult(parent: string, id: string, error: boolean, text: string) {
    const list = this.subs.get(parent);
    const tool = list?.find((it) => it.k === "tool" && it.id === id) as Extract<Item, { k: "tool" }> | undefined;
    if (tool) tool.result = { error, text };
    this.emit({ t: "sub_result", parent, id, error, text });
  }

  private async readContext() {
    try {
      const c = await this.q?.getContextUsage({ detail: "summary" });
      if (!c) return;
      this.context = { used: c.totalTokens, max: c.maxTokens, percent: Math.round(c.percentage), model: c.model };
      this.emit({ t: "context", context: this.context });
    } catch {}
  }

  private system(m: any) {
    switch (m.subtype) {
      case "init":
        this.model = m.model;
        this.emit({ t: "settings", settings: this.settings, model: this.model });
        if (!this.replaying) this.readContext();
        return;
      case "api_retry":
        return this.emit({ t: "status", running: true, note: "Retrying…" });
      case "status":
        if (m.status === "compacting") this.emit({ t: "status", running: true, note: "Compacting" });
        return;
      case "compact_boundary":
        this.emit({ t: "items", items: [{ k: "note", id: m.uuid, text: `Context compacted (${Math.round((m.compact_metadata?.pre_tokens ?? 0) / 1000)}k tokens before).` }] });
        if (!this.replaying) void this.readContext();
        return;
      case "local_command_output":
        return this.emit({ t: "items", items: [{ k: "note", id: m.uuid, text: m.content }] });
      case "informational":
        if (m.level === "warning" || m.level === "notice") this.emit({ t: "items", items: [{ k: "note", id: m.uuid, text: m.content }] });
        return;
      case "task_started":
        this.tasks.set(m.task_id, {
          id: m.task_id,
          toolUseId: m.tool_use_id,
          description: m.description,
          kind: m.subagent_type ?? m.task_type ?? "task",
          status: "running",
          background: !!m.is_backgrounded,
          startedAt: Date.now(),
          parentId: m.parent_task_id,
          ambient: !!m.skip_transcript,
        });
        return this.emitTasks();
      case "background_tasks_changed": {
        // The SDK's whole list of live background tasks (replace, not patch):
        // a running one missing from it has ended, even if no notification
        // came (its agent was stopped first); a live one we never saw start
        // (after a restart of this service) is added.
        type Live = { task_id: string; description: string; task_type?: string; subagent_type?: string; parent_task_id?: string; ambient?: boolean };
        const live = new Map(((m.tasks ?? []) as Live[]).map((t) => [t.task_id, t] as const));
        for (const [id, t] of this.tasks)
          if (t.status === "running" && t.background && !live.has(id))
            this.tasks.set(id, { ...t, status: "stopped", endedAt: Date.now() });
        for (const [id, t] of live)
          if (!this.tasks.has(id))
            this.tasks.set(id, {
              id, description: t.description, kind: t.subagent_type ?? t.task_type ?? "task", status: "running",
              background: true, startedAt: Date.now(), parentId: t.parent_task_id, ambient: !!t.ambient,
            });
          else if (t.ambient) this.tasks.set(id, { ...this.tasks.get(id)!, ambient: true });
        return this.emitTasks();
      }
      case "task_progress":
        return this.patchTask(m.task_id, {
          status: "running",
          ...(m.description ? { description: m.description } : {}),
          tokens: m.usage?.total_tokens,
          toolUses: m.usage?.tool_uses,
          lastTool: m.last_tool_name,
          summary: m.summary,
        });
      case "task_updated": {
        const p = m.patch ?? {};
        const status = p.status === "completed" ? "completed" : p.status === "failed" ? "failed" : p.status === "killed" ? "stopped"
          : p.status === "running" || p.status === "pending" ? "running" : undefined;
        return this.patchTask(m.task_id, {
          ...(status ? { status, endedAt: Date.now() } : {}),
          ...(p.description ? { description: p.description } : {}),
          ...(p.is_backgrounded !== undefined ? { background: p.is_backgrounded } : {}),
        });
      }
      case "task_notification":
        return this.patchTask(m.task_id, {
          status: m.status,
          endedAt: Date.now(),
          summary: m.summary,
          outputFile: m.output_file,
          tokens: m.usage?.total_tokens,
          toolUses: m.usage?.tool_uses,
        });
    }
  }

  private handle(m: SDKMessage) {
    // A message sent mid-turn runs as its own turn after the result of the first.
    if ((m.type === "stream_event" || m.type === "assistant") && !this.running) this.setRunning(true);
    switch (m.type) {
      case "stream_event": {
        if (m.parent_tool_use_id) return;
        const e = m.event as any;
        if (e.type === "message_start") this.partial = "";
        if (e.type === "content_block_start" && e.content_block?.type === "thinking") this.emit({ t: "status", running: true, note: "Thinking" });
        if (e.type === "content_block_start" && e.content_block?.type === "text") this.emit({ t: "status", running: true });
        if (e.type === "content_block_delta" && e.delta?.type === "text_delta") {
          this.partial += e.delta.text;
          this.emit({ t: "delta", text: e.delta.text });
        }
        return;
      }
      case "assistant": {
        if (m.parent_tool_use_id) return this.sub(m.parent_tool_use_id, assistantItems(m.uuid, (m.message.content as any[]) ?? []));
        this.partial = "";
        const items = assistantItems(m.uuid, (m.message.content as any[]) ?? []);
        if (items.length) this.emit({ t: "items", items });
        return;
      }
      case "user": {
        const content = (m.message as any).content;
        if (!Array.isArray(content)) return;
        if (m.parent_tool_use_id) {
          for (const b of content)
            if (b.type === "tool_result") this.subResult(m.parent_tool_use_id, b.tool_use_id, !!b.is_error, short(resultText(b.content), 2000));
          return;
        }
        for (const b of content)
          if (b.type === "tool_result") this.emit({ t: "tool_result", id: b.tool_use_id, error: !!b.is_error, text: short(resultText(b.content), 4000) });
        return;
      }
      case "result": {
        const error = m.subtype === "success" ? (m.is_error ? String(m.result) : undefined) : (m as any).errors?.join("\n") || m.subtype;
        const stopped = this.stopping;
        this.stopping = false;
        this.emit({ t: "turn_end", turn: this.turn, drew: this.drew, error: stopped ? undefined : error, stopped });
        this.setRunning(false);
        if (this.replaying) return;
        this.readContext();
        refreshSoon(WORK);
        this.finishChanges(this.turn).finally(() => this.restartIfStale());
        return;
      }
      case "rate_limit_event":
        if (this.replaying) return;
        noteLimits(this.account, m.rate_limit_info);
        refreshSoon(WORK, 4000);
        return;
      case "system":
        return this.system(m as any);
      default:
        if ((m as any).type === "command_lifecycle") this.lifecycle(m as any);
        return;
    }
  }
}
