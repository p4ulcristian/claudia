// The work Claude started beside a chat (decision 25): every subagent and background shell, for the
// agent chips under the chat's title, the list of them, and one agent's view (its steps, files,
// prompt and report). Read from the session files (the chat's own and each subagent's, through
// src/timeline.ts) and the runner's live tasks, so it survives restarts. Git only reads.
import { homedir } from "node:os";
import { WORK, assistantItems, resultText, type Item, type Task } from "./chat";
import { readLines, readLoc, sessionParts, type AgentFile, type Call, type Loc, type Parsed, type Tail } from "./timeline";
import { isShell, topOf } from "./working";
import { outputFileOf, readProgress, type Progress } from "./progress";
import { branchWork, commitDetail, filePatch, rangeDetail, rangePatch, workingDetail, type BranchCommit, type Detail } from "./git";

export type AgentStatus = "running" | "completed" | "failed" | "stopped";
export type AgentInfo = {
  // Its place in the page's address: the subagent's file id, else the task's id, else its call's.
  id: string;
  kind: "agent" | "shell";
  name: string;
  status: AgentStatus;
  start: number;
  end: number | null;
  tools: number;
  tokens: number | null;
  // What it is on now (an agent's newest tool call; a shell's command): "Edit src/hub/push.ts".
  step: string | null;
  // When it last did something, for which chips show first.
  active: number;
  // The live task (for Stop), while the runner knows it.
  task: string | null;
  lane: string | null;
  type: string;
  model: string | null;
  effort: string | null;
  background: boolean;
  worktree: { path: string; branch: string } | null;
  // Its own commands and agents still running (they are part of it, not chips of their own).
  children: number;
  // A shell's last `##progress done/total label` line of its output (src/progress.ts), else null.
  progress: Progress | null;
};
export type AgentList = { v: string; now: number; agents: AgentInfo[] };
export type Live = { running: boolean; tasks: Task[]; folder: string | null };

const FILE_TOOLS = /^(Read|Write|Edit|MultiEdit|NotebookEdit)$/;
const EDIT_TOOLS = /^(Write|Edit|MultiEdit|NotebookEdit)$/;
const ID = /^[\w-]{1,80}$/;
const BRANCH = /^(?!-)(?!.*\.\.)[\w./-]{1,200}$/;

function rel(path: string, bases: (string | null)[]) {
  for (const b of bases) if (b && path.startsWith(b + "/")) return path.slice(b.length + 1);
  const home = homedir();
  return path.startsWith(home + "/") ? `~${path.slice(home.length)}` : path;
}

// A tool call as a step: its name and what it works on, e.g. "Edit src/hub/push.ts", "$ bun test".
function stepOf(p: Parsed, id: string | null, bases: (string | null)[]): string | null {
  const ev = id ? p.tools.get(id) : undefined;
  if (!ev) return null;
  const target = p.targets.get(ev.id);
  if (ev.name === "Bash") return `$ ${target || ev.title}`;
  if (target && FILE_TOOLS.test(ev.name)) return `${ev.name} ${rel(target, bases)}`;
  if (target) return `${ev.name} ${target}`;
  return ev.title ? `${ev.name} ${ev.title}` : ev.name;
}

function newestTool(p: Parsed): string | null {
  for (let i = p.events.length - 1; i >= 0; i--) if (p.events[i]!.k === "tool") return p.events[i]!.id;
  return null;
}

const asStatus = (s: string | undefined): AgentStatus => (s === "running" ? "running" : s === "failed" ? "failed" : s === "completed" ? "completed" : "stopped");
const toolCount = (p: Parsed | null | undefined) => (p ? p.tools.size : 0);
const maxOf = (...n: (number | null | undefined)[]) => Math.max(0, ...n.filter((x): x is number => typeof x === "number"));

type Entry = { info: AgentInfo; use: string | null; call: Call | null; lane: AgentFile | null };

// Every agent and background shell of the chat, newest work first.
async function entries(sessionId: string, live: Live) {
  const s = await sessionParts(sessionId);
  if (!s) return null;
  const tasks = live.tasks.filter((t) => !t.ambient);
  const byId = new Map(tasks.map((t) => [t.id, t]));
  const byUse = new Map(tasks.filter((t) => t.toolUseId).map((t) => [t.toolUseId!, t]));
  const laneById = new Map(s.lanes.map((l) => [l.id, l]));
  const laneByUse = new Map(s.lanes.filter((l) => l.meta.toolUseId).map((l) => [l.meta.toolUseId!, l]));
  const kids = (top: Task | undefined) => (top ? tasks.filter((t) => t !== top && t.status === "running" && topOf(t, byId) === top).length : 0);
  const placed = new Set<string>();
  const out: Entry[] = [];

  // Claude's own calls in the chat's file: subagents, and shells it put in the background.
  for (const [use, call] of s.main.calls) {
    const t = byUse.get(use);
    if (t) placed.add(t.id);
    const ev = s.main.tools.get(use);
    const notice = s.main.notices.get(use);
    if (call.kind === "shell") {
      const status: AgentStatus = t ? t.status : notice ? asStatus(notice.status) : "stopped";
      const start = t?.startedAt ?? ev?.start ?? 0;
      out.push({
        use, call, lane: null,
        info: {
          id: t?.id ?? notice?.task ?? use, kind: "shell", name: call.description || t?.description || "Shell", status, start,
          end: status === "running" ? null : t?.endedAt ?? notice?.at ?? null,
          tools: 0, tokens: null, step: `$ ${call.command.split("\n")[0]!.slice(0, 200)}`, active: start,
          task: t?.id ?? null, lane: null, type: "shell", model: null, effort: null, background: true, worktree: null, children: 0, progress: await readProgress(t?.outputFile ?? await outputFileOf(sessionId, t?.id ?? notice?.task)),
        },
      });
      continue;
    }
    const outcome = s.main.outcomes.get(use);
    const lane = laneByUse.get(use) ?? (outcome?.agent ? laneById.get(outcome.agent) : undefined) ?? (t ? laneById.get(t.id) : undefined) ?? null;
    const p = lane?.p ?? null;
    // Running: the runner's task says so; without one (just restarted), a foreground call still open
    // while the chat works.
    const status: AgentStatus = t ? t.status
      : !call.background && live.running && ev && ev.end === undefined ? "running"
      : notice ? asStatus(notice.status)
      : outcome && outcome.status !== "async_launched" ? asStatus(outcome.status)
      : lane?.meta.finished ? "completed" : "stopped";
    const running = status === "running";
    const start = t?.startedAt ?? ev?.start ?? p?.first ?? 0;
    const wt = lane?.meta.worktreePath && lane.meta.worktreeBranch ? { path: lane.meta.worktreePath, branch: lane.meta.worktreeBranch } : null;
    const bases = [wt?.path ?? null, live.folder, WORK];
    out.push({
      use, call, lane,
      info: {
        id: lane?.id ?? t?.id ?? use, kind: "agent", name: call.description || lane?.meta.description || t?.description || "Agent", status, start,
        end: running ? null : t?.endedAt ?? notice?.at ?? (call.background ? p?.last : ev?.end) ?? p?.last ?? null,
        tools: maxOf(t?.toolUses, toolCount(p), notice?.tools, outcome?.tools),
        tokens: running ? t?.tokens ?? p?.tokens ?? null : notice?.tokens ?? outcome?.tokens ?? t?.tokens ?? p?.tokens ?? null,
        step: p ? stepOf(p, newestTool(p), bases) : null,
        active: maxOf(p?.last, t?.startedAt, start),
        task: t?.id ?? null, lane: lane?.id ?? null, type: call.type || lane?.meta.agentType || t?.kind || "agent",
        model: p?.model ?? outcome?.model ?? call.model, effort: call.effort, background: call.background, worktree: wt, children: kids(t), progress: null,
      },
    });
  }

  // Tasks Claude started that no call in the file names yet (just started), or of a kind it doesn't
  // mark (a monitor, a workflow); not a foreground command, which is part of a turn's tool call.
  for (const t of tasks) {
    if (placed.has(t.id) || topOf(t, byId) !== t || (isShell(t) && !t.background)) continue;
    const lane = laneById.get(t.id) ?? (t.toolUseId ? laneByUse.get(t.toolUseId) : undefined) ?? null;
    const p = lane?.p ?? null;
    const wt = lane?.meta.worktreePath && lane.meta.worktreeBranch ? { path: lane.meta.worktreePath, branch: lane.meta.worktreeBranch } : null;
    out.push({
      use: t.toolUseId ?? null, call: null, lane,
      info: {
        id: lane?.id ?? t.id, kind: lane || !isShell(t) ? "agent" : "shell", name: t.description || "Task", status: t.status, start: t.startedAt,
        end: t.status === "running" ? null : t.endedAt ?? null, tools: maxOf(t.toolUses, toolCount(p)), tokens: t.tokens ?? p?.tokens ?? null,
        step: p ? stepOf(p, newestTool(p), [wt?.path ?? null, live.folder, WORK]) : t.lastTool ?? null,
        active: maxOf(p?.last, t.startedAt), task: t.id, lane: lane?.id ?? null, type: lane?.meta.agentType || t.kind,
        model: p?.model ?? null, effort: null, background: t.background, worktree: wt, children: kids(t),
        progress: lane || !isShell(t) ? null : await readProgress(t.outputFile ?? await outputFileOf(sessionId, t.id)),
      },
    });
  }

  out.sort((a, b) => {
    const ra = a.info.status === "running", rb = b.info.status === "running";
    if (ra !== rb) return ra ? -1 : 1;
    return ra ? b.info.active - a.info.active : (b.info.end ?? b.info.active) - (a.info.end ?? a.info.active);
  });
  const sig = [s.main.offset, ...s.lanes.map((l) => l.p?.offset ?? 0), live.running ? 1 : 0, ...tasks.map((t) => `${t.id}:${t.status}:${t.toolUses ?? 0}:${t.tokens ?? 0}`), ...out.map((x) => x.info.progress ? `${x.info.progress.done}/${x.info.progress.total}` : "")].join(".");
  return { s, out, v: Bun.hash(sig).toString(36) };
}

export async function agentList(sessionId: string, live: Live): Promise<AgentList | null> {
  const e = await entries(sessionId, live);
  return e ? { v: e.v, now: Date.now(), agents: e.out.map((x) => x.info) } : null;
}

// ---- one agent, in full ----

export type FileEdit = { event: string; name: string; added: number; removed: number };
export type FileChange = { path: string; rel: string; added: number; removed: number; edits: FileEdit[] };
export type AgentGit = { branch: string; base: string | null; commits: BranchCommit[]; worktree: boolean };
export type AgentDetail = AgentInfo & { prompt: string | null; report: string | null; files: FileChange[]; git: AgentGit | null; error?: string };

// Every string inside a parsed line (a notification can sit in a message's text, a reminder, or a
// queue entry's content).
function strings(v: unknown, out: string[] = []): string[] {
  if (typeof v === "string") out.push(v);
  else if (Array.isArray(v)) for (const x of v) strings(x, out);
  else if (v && typeof v === "object") for (const x of Object.values(v)) strings(x, out);
  return out;
}

async function lineAt(path: string, loc: Loc | undefined): Promise<any> {
  return loc ? readLoc(path, loc).catch(() => null) : null;
}

// What the Agent call asked (its prompt), from where Claude made the call.
async function promptOf(path: string, p: Parsed, use: string): Promise<string | null> {
  const line = await lineAt(path, p.locs.get(use)?.use);
  const b = (line?.message?.content ?? []).find?.((x: any) => x?.type === "tool_use" && x.id === use);
  return typeof b?.input?.prompt === "string" ? b.input.prompt : null;
}

// What it answered: its last notification's result (a background agent), or its tool result (a
// foreground one), else the last thing it said in its own file.
async function reportOf(e: Entry, mainPath: string, main: Parsed): Promise<string | null> {
  if (e.use) {
    const notice = main.notices.get(e.use);
    if (notice) {
      const line = await lineAt(mainPath, notice.loc);
      const text = strings(line).find((x) => x.includes(`<tool-use-id>${e.use}</tool-use-id>`));
      const result = text?.match(/<result>([\s\S]*?)<\/result>/)?.[1]?.trim();
      if (result) return result;
    }
    const outcome = main.outcomes.get(e.use);
    if (outcome && outcome.status !== "async_launched") {
      const line = await lineAt(mainPath, main.locs.get(e.use)?.result);
      const b = (line?.message?.content ?? []).find?.((x: any) => x?.type === "tool_result" && x.tool_use_id === e.use);
      const text = b ? resultText(b.content).trim() : "";
      if (text) return text;
    }
  }
  const p = e.lane?.p;
  const last = p ? [...p.events].reverse().find((x) => x.k === "text") : undefined;
  if (!p || !last || !e.lane) return null;
  const line = await lineAt(e.lane.path, p.locs.get(last.id)?.use);
  const n = Number(last.id.split(":").pop());
  const text = line?.message?.content?.[n]?.text;
  return typeof text === "string" ? text.trim() : null;
}

// The first thing its own file says: the prompt it was given (when no call in the chat names it).
async function firstAsk(lane: AgentFile): Promise<string | null> {
  const first = lane.p?.events.find((x) => x.k === "user");
  if (!first || !lane.p) return null;
  const line = await lineAt(lane.path, lane.p.locs.get(first.id)?.use);
  const c = line?.message?.content;
  return typeof c === "string" ? c : Array.isArray(c) ? c.filter((b: any) => b?.type === "text").map((b: any) => b.text).join("\n") : null;
}

// Its Edit and Write calls, by file in the order first touched, each with its lines added and removed
// (from the patch Claude Code saved with the result; the timeline's detail opens each one's diff).
function filesOf(lane: AgentFile | null, bases: (string | null)[]): FileChange[] {
  const p = lane?.p;
  if (!p) return [];
  const by = new Map<string, FileChange>();
  for (const ev of p.events) {
    if (ev.k !== "tool" || !EDIT_TOOLS.test(ev.name) || !ev.diff) continue;
    const path = p.targets.get(ev.id);
    if (!path) continue;
    const f = by.get(path) ?? { path, rel: rel(path, bases), added: 0, removed: 0, edits: [] };
    f.added += ev.diff.added;
    f.removed += ev.diff.removed;
    f.edits.push({ event: ev.id, name: ev.name, added: ev.diff.added, removed: ev.diff.removed });
    by.set(path, f);
  }
  return [...by.values()];
}

const worktreeOf = (e: Entry) => (e.info.worktree && BRANCH.test(e.info.worktree.branch) ? e.info.worktree : null);

export async function agentDetail(sessionId: string, id: string, live: Live): Promise<AgentDetail | null> {
  if (!ID.test(id)) return null;
  const all = await entries(sessionId, live);
  const e = all?.out.find((x) => x.info.id === id);
  if (!all || !e) return null;
  const { mainPath, main } = all.s;
  const wt = worktreeOf(e);
  const prompt = e.call?.kind === "shell" ? e.call.command : (e.use ? await promptOf(mainPath, main, e.use) : null) ?? (e.lane ? await firstAsk(e.lane) : null);
  const report = e.info.status === "running" || e.info.kind === "shell" ? null : await reportOf(e, mainPath, main);
  let git: AgentGit | null = null;
  let error: string | undefined;
  if (wt) {
    try {
      const w = await branchWork(wt.path, wt.branch);
      if (w) git = { branch: w.branch, base: w.base, commits: w.commits, worktree: w.worktree };
    } catch (err) {
      error = String((err as Error)?.message ?? err);
    }
  }
  return { ...e.info, prompt, report, files: filesOf(e.lane, [wt?.path ?? null, live.folder, WORK]), git, ...(error ? { error } : {}) };
}

// The worktree's commits and its whole change since the branch was made, for the git form's commit
// view: rev = one of its commits, or "BRANCHED" (the worktree's files against the base, uncommitted
// work too; only the branch once the folder is gone). file: one file's patch.
export async function agentGit(sessionId: string, id: string, live: Live, rev: string, file: string | null): Promise<Detail | { diff: string } | null> {
  if (!ID.test(id)) return null;
  const all = await entries(sessionId, live);
  const e = all?.out.find((x) => x.info.id === id);
  const wt = e && worktreeOf(e);
  if (!wt) return null;
  const w = await branchWork(wt.path, wt.branch);
  if (!w?.base) return null;
  if (file !== null && (file.includes("\0") || file.startsWith("-"))) return null;
  if (rev === "BRANCHED") {
    const subject = w.worktree ? "Since it branched (with uncommitted work)" : "Since it branched";
    if (w.worktree) return file === null ? workingDetail(w.root, w.base, subject) : { diff: await filePatch(w.root, "WORKING", file, w.base) };
    const tip = `refs/heads/${w.branch}`;
    return file === null ? rangeDetail(w.root, w.base, tip, subject) : { diff: await rangePatch(w.root, w.base, tip, file) };
  }
  // Only the branch's own commits.
  if (!w.commits.some((c) => c.hash === rev)) return null;
  return file === null ? commitDetail(w.root, rev) : { diff: await filePatch(w.root, rev, file) };
}

// ---- its steps, as the chat shows a turn's (web/chat.tsx Transcript) ----

type StepState = Tail & { items: Item[]; tools: Map<string, Extract<Item, { k: "tool" }>> };
const stepCache = new Map<string, { parsed: StepState; used: number }>();
const freshSteps = (): StepState => ({ offset: 0, rest: Buffer.alloc(0), restAt: 0, items: [], tools: new Map() });
const cutText = (s: string, n: number) => (s.length > n ? s.slice(0, n - 1) + "…" : s);

function takeStep(s: StepState, line: string) {
  let d: any;
  try {
    d = JSON.parse(line);
  } catch {
    return;
  }
  const content = d?.message?.content;
  if (!Array.isArray(content)) return;
  if (d.type === "assistant") {
    for (const it of assistantItems(String(d.uuid), content)) {
      if (it.k === "tool") {
        if (s.tools.has(it.id)) continue;
        s.tools.set(it.id, it);
      }
      s.items.push(it);
    }
  } else if (d.type === "user") {
    for (const b of content)
      if (b?.type === "tool_result") {
        const t = s.tools.get(b.tool_use_id);
        if (t) t.result = { error: !!b.is_error, text: cutText(resultText(b.content), 4000) };
      }
  }
}

// from: how many items the page already has whole (every tool among them has its result): only
// the ones after come again. v: the file's length, unchanged = nothing new.
export async function agentSteps(sessionId: string, id: string, from: number, v: string | null): Promise<{ v: string; total: number; from: number; items: Item[] } | { same: true; v: string } | null> {
  if (!ID.test(id)) return null;
  const s = await sessionParts(sessionId);
  const lane = s?.lanes.find((l) => l.id === id);
  if (!lane) return null;
  const st = await readLines(lane.path, stepCache, freshSteps, takeStep, 16);
  if (!st) return null;
  const ver = String(st.offset);
  if (v === ver) return { same: true, v: ver };
  const at = Math.max(0, Math.min(from, st.items.length));
  return { v: ver, total: st.items.length, from: at, items: st.items.slice(at) };
}
