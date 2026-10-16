// One chat = one Claude Code session, driven through the Agent SDK.
import { query, getSessionMessages, getSessionInfo, type Query, type SDKMessage, type SDKUserMessage, type PermissionResult } from "@anthropic-ai/claude-agent-sdk";
import { homedir } from "node:os";
import { join } from "node:path";
import { store } from "./db";
import { refreshSoon } from "./meta";
import { pictureServer, SYSTEM_APPEND } from "./prompt";
import { applyDraw, emptyScene, type DrawInput, type Scene } from "./scene";

// Decision 6: every chat runs in ~/Work, where the user's full memory lives.
export const WORK = process.env.CLAUDIA_CWD ?? join(homedir(), "Work");
const IDLE_MS = 15 * 60_000;
const DRAW_TOOL = "mcp__picture__draw";

export type Item =
  | { k: "user"; uuid: string; text: string; images: number }
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
};
export type Context = { used: number; max: number; percent: number; model: string };
export type Settings = { model: string | null; effort: string | null };

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
  | { t: "settings"; settings: Settings; model?: string };

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
    case "AskUserQuestion": return (i.questions ?? []).map((q: Question) => q.header).join(", ");
    default: return "";
  }
}

export function toolLabel(name: string) {
  if (name === DRAW_TOOL) return "Draw";
  if (name === "AskUserQuestion") return "Ask";
  return name.startsWith("mcp__") ? name.split("__").slice(1).join(" ") : name;
}

const stripReminders = (s: string) => s.replace(/<system-reminder>[\s\S]*?<\/system-reminder>/g, "").trim();

// Slash commands are stored as tagged user text; show the command and its output plainly.
// Claude Code stores "background task finished" notices as user messages; show them as a note.
function taskNotice(text: string): string | null {
  if (!text.startsWith("<task-notification>")) return null;
  const summary = text.match(/<summary>([\s\S]*?)<\/summary>/)?.[1]?.trim();
  return summary ?? "A background task finished.";
}

function commandParts(text: string): { command?: string; output?: string } | null {
  const name = text.match(/<command-name>([\s\S]*?)<\/command-name>/)?.[1]?.trim();
  const args = text.match(/<command-args>([\s\S]*?)<\/command-args>/)?.[1]?.trim();
  const out = text.match(/<local-command-std(?:out|err)>([\s\S]*?)<\/local-command-std(?:out|err)>/)?.[1]?.trim();
  if (!name && out === undefined && !/<command-message>/.test(text)) return null;
  return { command: name ? `${name.startsWith("/") ? name : "/" + name}${args ? " " + args : ""}` : undefined, output: out };
}

function resultText(content: unknown): string {
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
  for (const m of msgs) {
    if (m.parent_tool_use_id) continue;
    const msg = m.message as any;
    if (m.type === "assistant") {
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

// A queue the SDK reads user messages from, for as long as the chat is open.
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

type Waiting<T> = { value: T; resolve: (r: PermissionResult) => void; input: Record<string, unknown> };

export class Chat {
  private q: Query | null = null;
  private inbox: Inbox | null = null;
  private idle: Timer | null = null;
  private listeners = new Set<(e: ChatEvent) => void>();
  private turn = "";
  private drew = false;
  private stopping = false;
  running = false;
  since = 0;
  partial = "";
  scene: Scene;
  asks = new Map<string, Waiting<Ask>>();
  permits = new Map<string, Waiting<Permit>>();
  tasks = new Map<string, Task>();
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
    store.saveSettings(this.id, this.settings.model, this.settings.effort);
    if (this.q) {
      if ("model" in next) await this.q.setModel(next.model ?? undefined).catch(() => {});
      if ("effort" in next) await this.q.applyFlagSettings({ effortLevel: (next.effort ?? null) as any }).catch(() => {});
    }
    this.emit({ t: "settings", settings: this.settings, model: this.model });
  }

  async stopTask(taskId: string) {
    await this.q?.stopTask(taskId).catch(() => {});
  }

  private emitTasks() {
    this.emit({ t: "tasks", tasks: [...this.tasks.values()] });
    this.onChange();
  }

  private patchTask(id: string, patch: Partial<Task>) {
    const t = this.tasks.get(id);
    if (t) this.tasks.set(id, { ...t, ...patch });
    this.emitTasks();
  }

  on(fn: (e: ChatEvent) => void) {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  private emit(e: ChatEvent) {
    for (const fn of this.listeners) fn(e);
  }

  async send(text: string, images: { media_type: string; data: string }[] = []) {
    const uuid = crypto.randomUUID();
    const content: any[] = images.map((im) => ({ type: "image", source: { type: "base64", media_type: im.media_type, data: im.data } }));
    if (text) content.push({ type: "text", text });
    if (!this.q) await this.start();
    this.turn = uuid;
    this.drew = false;
    this.setRunning(true);
    this.emit({ t: "user", item: { k: "user", uuid, text, images: images.length } });
    this.inbox!.push({ type: "user", uuid: uuid as any, session_id: this.id, parent_tool_use_id: null, message: { role: "user", content } });
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
  }

  permit(id: string, allow: boolean) {
    const w = this.permits.get(id);
    if (!w) return;
    this.permits.delete(id);
    w.resolve(allow ? { behavior: "allow", updatedInput: w.input } : { behavior: "deny", message: "The user said no." });
    this.emit({ t: "permit_done", id });
  }

  close() {
    this.inbox?.end();
    this.q?.close();
    this.q = null;
    this.inbox = null;
  }

  private setRunning(r: boolean) {
    if (this.running === r) return;
    this.running = r;
    if (r) this.since = Date.now();
    this.emit({ t: "status", running: r });
    this.onChange();
    if (this.idle) clearTimeout(this.idle);
    if (!r) this.idle = setTimeout(() => !this.running && this.close(), IDLE_MS);
  }

  private draw(input: DrawInput): string {
    const { scene, changed } = applyDraw(this.scene, input);
    this.scene = scene;
    this.drew = true;
    store.savePicture(this.id, this.turn, scene, changed);
    this.emit({ t: "scene", scene, changed, turn: this.turn });
    return `Picture updated: ${scene.nodes.length} nodes, ${scene.edges.length} arrows, ${scene.cards.length} cards.`;
  }

  private async start() {
    const exists = !!(await getSessionInfo(this.id, { dir: WORK }).catch(() => undefined));
    if (!exists) store.addChat(this.id);
    this.inbox = new Inbox();
    this.q = query({
      prompt: this.inbox,
      options: {
        cwd: WORK,
        ...(exists ? { resume: this.id } : { sessionId: this.id }),
        systemPrompt: { type: "preset", preset: "claude_code", append: SYSTEM_APPEND },
        mcpServers: { picture: pictureServer((i) => this.draw(i)) },
        allowedTools: [DRAW_TOOL],
        // Same as the user's terminal Claude (defaultMode bypassPermissions).
        permissionMode: "bypassPermissions",
        allowDangerouslySkipPermissions: true,
        includePartialMessages: true,
        ...(this.settings.model ? { model: this.settings.model } : {}),
        ...(this.settings.effort ? { effort: this.settings.effort as any } : {}),
        canUseTool: (name, input, opts) => this.ask(name, input, opts),
        stderr: (s) => console.error(`[${this.id.slice(0, 8)}] ${s.trimEnd()}`),
      },
    });
    this.pump(this.q);
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

  private async pump(q: Query) {
    try {
      for await (const m of q) this.handle(m);
    } catch (err) {
      this.emit({ t: "error", text: String((err as Error)?.message ?? err) });
    } finally {
      if (this.q === q) { this.q = null; this.inbox = null; }
      this.partial = "";
      this.setRunning(false);
    }
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
        this.readContext();
        return;
      case "api_retry":
        return this.emit({ t: "status", running: true, note: "Retrying…" });
      case "status":
        if (m.status === "compacting") this.emit({ t: "status", running: true, note: "Compacting" });
        return;
      case "compact_boundary":
        this.emit({ t: "items", items: [{ k: "note", id: m.uuid, text: `Context compacted (${Math.round((m.compact_metadata?.pre_tokens ?? 0) / 1000)}k tokens before).` }] });
        return void this.readContext();
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
        });
        return this.emitTasks();
      case "task_progress":
        return this.patchTask(m.task_id, {
          tokens: m.usage?.total_tokens,
          toolUses: m.usage?.tool_uses,
          lastTool: m.last_tool_name,
          summary: m.summary,
        });
      case "task_updated": {
        const p = m.patch ?? {};
        const status = p.status === "completed" ? "completed" : p.status === "failed" ? "failed" : p.status === "killed" ? "stopped" : undefined;
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
        this.readContext();
        refreshSoon(WORK);
        return;
      }
      case "rate_limit_event":
        refreshSoon(WORK, 4000);
        return;
      case "system":
        return this.system(m as any);
    }
  }
}
