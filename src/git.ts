// The git form's reads (from GitGud's lib/git.js and api/commit): a repo's commit graph with its
// branches and uncommitted work, and one commit's files and patch. Read only: nothing here writes to
// a repo. The runner serves these inside a repo the chat drew a git block for (runner.ts).
import { readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { layoutGraph, type Commit, type Ref } from "./git-layout";

async function git(root: string, args: string[], allowFail = false): Promise<string> {
  const p = Bun.spawn(["git", "-C", root, ...args], { stdout: "pipe", stderr: "pipe", env: { ...process.env, GIT_OPTIONAL_LOCKS: "0" } });
  const [out, err, code] = await Promise.all([new Response(p.stdout).text(), new Response(p.stderr).text(), p.exited]);
  if (code !== 0 && !allowFail) throw new Error(err.trim() || `git ${args[0]} failed`);
  return out;
}

const F = "\x1f"; // between fields
const R = "\x1e"; // between commits

export type Branch = { name: string; local: boolean; remote: boolean; kind: "both" | "local" | "remote"; upstream: string | null; tracked: boolean; gone: boolean; ahead: number; behind: number; current?: boolean };
export type Dirty = { staged: number; unstaged: number; untracked: number; total: number; files: { path: string; status: string }[] };
export type Smartlog = { commits: Commit[]; head: string; currentBranch: string; detached: boolean; branches: Branch[]; dirty: Dirty; fetched: string | null; more: boolean };

// The graph: every branch, tag and remote plus HEAD (or only the named branches), newest first.
export async function smartlog(root: string, { limit = 100, branches = null as string[] | null } = {}): Promise<Smartlog> {
  await git(root, ["rev-parse", "--git-dir"]).catch(() => { throw new Error("not a git repository"); });
  const head = (await git(root, ["rev-parse", "HEAD"], true)).trim();
  const currentBranch = (await git(root, ["symbolic-ref", "--quiet", "--short", "HEAD"], true)).trim();
  const [tracking, dirty, fetched] = await Promise.all([branchTracking(root), workingCopy(root), lastFetched(root)]);
  const roster = await branchRoster(root, tracking);
  const list = Object.values(roster)
    .map((b) => ({ ...b, current: b.local && b.name === currentBranch }))
    .sort((a, b) => Number(b.local) - Number(a.local) || a.name.localeCompare(b.name));
  if (!head) return { commits: [], head: "", currentBranch, detached: false, branches: list, dirty, fetched, more: false };
  // A stale branch filter can't break the log: only names that still exist count.
  const wanted = branches?.filter((b) => roster[b]) ?? [];
  const revs = wanted.length ? wanted : ["--branches", "--tags", "--remotes", "HEAD"];
  const fmt = ["%H", "%P", "%an", "%ar", "%D", "%s", "%at"].join(F) + R;
  const out = await git(root, ["log", ...revs, "--topo-order", `--max-count=${limit + 1}`, `--pretty=format:${fmt}`, "--"]);
  const commits: Commit[] = out.split(R).map((r) => r.trim()).filter(Boolean).map((rec) => {
    const [hash, parents, author, when, refs, subject, at] = rec.split(F);
    return { hash, parents: parents ? parents.split(" ").filter(Boolean) : [], author, when, time: Number(at) || 0, refs: parseRefs(refs, tracking), subject };
  });
  const more = commits.length > limit;
  if (more) commits.length = limit;
  layoutGraph(commits);
  return { commits, head, currentBranch, detached: !currentBranch, branches: list, dirty, fetched, more };
}

// Uncommitted work: staged / unstaged / untracked counts and the files (one git status).
async function workingCopy(root: string): Promise<Dirty> {
  const out = await git(root, ["status", "--porcelain=v1", "--untracked-files=all"], true);
  let staged = 0, unstaged = 0, untracked = 0;
  const files: Dirty["files"] = [];
  for (const line of out.split("\n")) {
    if (!line) continue;
    const x = line[0], y = line[1];
    const path = line.slice(3).split(" -> ").pop()!;
    if (x === "?" && y === "?") { untracked++; files.push({ path, status: "?" }); continue; }
    if (x !== " ") staged++;
    if (y !== " ") unstaged++;
    files.push({ path, status: (y !== " " ? y : x).trim() || "M" });
  }
  return { staged, unstaged, untracked, total: staged + unstaged + untracked, files };
}

type Tracking = Record<string, { upstream: string; tip: string; gone?: boolean; ahead?: number; behind?: number }>;

// "HEAD -> main, origin/main, tag: v1" as refs. origin/HEAD is dropped, and a remote twin sitting on the
// same commit as its local branch folds into the local chip (one chip when they are in sync).
function parseRefs(s: string, tracking: Tracking): Ref[] {
  if (!s) return [];
  const parsed: Ref[] = [];
  for (const r of s.split(",").map((x) => x.trim()).filter(Boolean)) {
    if (r.startsWith("HEAD -> ")) parsed.push({ type: "head", name: r.slice(8) });
    else if (r === "HEAD") parsed.push({ type: "detached", name: "HEAD" });
    else if (r.startsWith("tag: ")) parsed.push({ type: "tag", name: r.slice(5) });
    else if (r.endsWith("/HEAD")) continue;
    else if (r.includes("/") && !tracking[r]) parsed.push({ type: "remote", name: r });
    else parsed.push({ type: "branch", name: r });
  }
  const localHere = new Set(parsed.filter((r) => r.type === "branch" || r.type === "head").map((r) => r.name));
  const out: Ref[] = [];
  for (const r of parsed) {
    if (r.type === "remote") {
      if (!localHere.has(r.name.slice(r.name.indexOf("/") + 1))) out.push(r);
    } else if (r.type === "branch" || r.type === "head") {
      const t = tracking[r.name];
      out.push({ ...r, tracked: !!t?.upstream, gone: !!t?.gone, ahead: t?.ahead ?? 0, behind: t?.behind ?? 0 });
    } else out.push(r);
  }
  return out;
}

// Every branch once: a local one ("both" when it tracks a live upstream, else "local"), plus each
// remote branch no local branch tracks ("remote").
async function branchRoster(root: string, tracking: Tracking): Promise<Record<string, Branch>> {
  const roster: Record<string, Branch> = {};
  const upstreams = new Set<string>();
  for (const [name, t] of Object.entries(tracking)) {
    const remote = !!t.upstream && !t.gone;
    if (t.upstream) upstreams.add(t.upstream);
    roster[name] = { name, local: true, remote, kind: remote ? "both" : "local", upstream: t.upstream || null, tracked: !!t.upstream, gone: !!t.gone, ahead: t.ahead ?? 0, behind: t.behind ?? 0 };
  }
  const out = await git(root, ["for-each-ref", "--format=%(refname)", "refs/remotes"], true);
  for (const ref of out.split("\n")) {
    const name = ref.trim().replace(/^refs\/remotes\//, "");
    if (!name || name.endsWith("/HEAD") || upstreams.has(name)) continue;
    roster[name] = { name, local: false, remote: true, kind: "remote", upstream: null, tracked: false, gone: false, ahead: 0, behind: 0 };
  }
  return roster;
}

// Each local branch: its upstream and how far ahead/behind it is (one git call).
async function branchTracking(root: string): Promise<Tracking> {
  const map: Tracking = {};
  const out = await git(root, ["for-each-ref", "--format=%(refname:short)\t%(upstream:short)\t%(upstream:track)\t%(objectname)", "refs/heads"], true);
  for (const line of out.split("\n")) {
    if (!line.trim()) continue;
    const [name, upstream = "", track = "", tip = ""] = line.split("\t");
    const m: Tracking[string] = { upstream, tip };
    if (track === "[gone]") m.gone = true;
    else {
      m.ahead = Number(track.match(/ahead (\d+)/)?.[1] ?? 0);
      m.behind = Number(track.match(/behind (\d+)/)?.[1] ?? 0);
    }
    map[name] = m;
  }
  return map;
}

// When the repo last fetched (FETCH_HEAD's time), or null.
async function lastFetched(root: string): Promise<string | null> {
  const dir = (await git(root, ["rev-parse", "--absolute-git-dir"], true)).trim();
  try {
    return dir ? statSync(join(dir, "FETCH_HEAD")).mtime.toISOString() : null;
  } catch {
    return null;
  }
}

// A cheap digest of everything the graph shows: refs and their targets, HEAD, the working tree. The
// page asks with the one it has and gets the whole graph only when it changed.
export async function fingerprint(root: string): Promise<string> {
  const parts = await Promise.all([
    git(root, ["for-each-ref", "--format=%(refname)%(objectname)"], true),
    git(root, ["rev-parse", "HEAD"], true),
    git(root, ["symbolic-ref", "--quiet", "HEAD"], true),
    git(root, ["status", "--porcelain=v1", "--untracked-files=all"], true),
  ]);
  return Bun.hash(parts.join("\0")).toString(36);
}

// ---- one commit (or "WORKING", the uncommitted changes) ----

export type FileChange = { path: string; oldPath: string | null; status: string; binary: boolean; additions: number; deletions: number; omitted?: boolean };
export type Detail = { meta: { hash: string; author: string; email: string; date: string; when: string; parents: string[]; subject: string; body: string }; files: FileChange[]; diff: string; truncated?: boolean; stats: { files: number; additions: number; deletions: number } };

export const REV = /^[\w./@^~-]+$/; // anything git resolves: hash, branch, tag, HEAD~3

export async function commitDetail(root: string, rev: string): Promise<Detail> {
  if (rev === "WORKING") return workingDetail(root);
  const full = (await git(root, ["rev-parse", "--verify", "--end-of-options", `${rev}^{commit}`])).trim();
  const fmt = ["%H", "%an", "%ae", "%aI", "%ar", "%P", "%s", "%b"].join(F);
  // Merges diff against their first parent (the combined diff git show prints is usually empty).
  const [metaRaw, numstat, nameStatus] = await Promise.all([
    git(root, ["show", "-s", `--format=${fmt}`, full]),
    git(root, ["show", "--format=", "--diff-merges=first-parent", "--numstat", "-M", full]),
    git(root, ["show", "--format=", "--diff-merges=first-parent", "--name-status", "-M", full]),
  ]);
  const files = mergeFiles(parseNumstat(numstat), parseNameStatus(nameStatus));
  const diff = tooBig(files) ? null : await git(root, ["show", "--format=", "--diff-merges=first-parent", "--no-color", "--no-prefix", "--patch", "-M", full]);
  const [hash, author, email, date, when, parents, subject, body] = metaRaw.split(F);
  return finish({ hash, author, email, date, when, parents: parents ? parents.split(" ").filter(Boolean) : [], subject, body: (body ?? "").trim() }, files, diff);
}

// Everything not committed: tracked changes against HEAD (staged and not) plus untracked files as additions.
async function workingDetail(root: string): Promise<Detail> {
  const [numstat, nameStatus, untrackedRaw, head] = await Promise.all([
    git(root, ["diff", "HEAD", "--no-color", "--numstat", "-M"], true),
    git(root, ["diff", "HEAD", "--no-color", "--name-status", "-M"], true),
    git(root, ["ls-files", "--others", "--exclude-standard"], true),
    git(root, ["rev-parse", "HEAD"], true),
  ]);
  const files = mergeFiles(parseNumstat(numstat), parseNameStatus(nameStatus));
  const untracked = untrackedRaw.split("\n").filter(Boolean);
  for (const f of untracked) {
    const n = lineCount(join(root, f));
    files.push({ path: f, oldPath: null, status: "?", binary: n === null, additions: n ?? 0, deletions: 0 });
  }
  let diff: string | null = null;
  if (!tooBig(files)) {
    diff = await git(root, ["diff", "HEAD", "--no-color", "--no-prefix", "--patch", "-M"], true);
    for (const f of untracked) {
      const d = await git(root, ["diff", "--no-index", "--no-color", "--no-prefix", "/dev/null", f], true); // exits 1 on any difference
      if (d) diff += (diff && !diff.endsWith("\n") ? "\n" : "") + d;
    }
  }
  const meta = { hash: "WORKING", author: "", email: "", date: new Date().toISOString(), when: "now", parents: head.trim() ? [head.trim()] : [], subject: "Uncommitted changes", body: "" };
  return finish(meta, files, diff);
}

// One file's patch, for a file left out of a big commit's patch.
export async function filePatch(root: string, rev: string, file: string): Promise<string> {
  if (rev === "WORKING") {
    const tracked = await git(root, ["diff", "HEAD", "--no-color", "--no-prefix", "--patch", "-M", "--", file], true);
    return tracked || git(root, ["diff", "--no-index", "--no-color", "--no-prefix", "/dev/null", file], true);
  }
  return git(root, ["show", "--format=", "--diff-merges=first-parent", "--no-color", "--no-prefix", "--patch", "-M", "--end-of-options", rev, "--", file]);
}

// Beyond MAX_LINES changed lines the patch isn't made at all (it can be hundreds of MB); beyond
// BUDGET bytes only the first files' patches come. The rest load one file at a time.
const MAX_LINES = 20_000;
const BUDGET = 1.5 * 1024 * 1024;
const tooBig = (files: FileChange[]) => files.reduce((n, f) => n + f.additions + f.deletions, 0) > MAX_LINES;

function finish(meta: Detail["meta"], files: FileChange[], diff: string | null): Detail {
  const stats = files.reduce((s, f) => ({ files: s.files + 1, additions: s.additions + f.additions, deletions: s.deletions + f.deletions }), { files: 0, additions: 0, deletions: 0 });
  if (diff === null) return { meta, files: files.map((f) => ({ ...f, omitted: true })), diff: "", truncated: true, stats };
  if (diff.length <= BUDGET) return { meta, files, diff, stats };
  const kept: string[] = [];
  const keptPaths = new Set<string>();
  let size = 0;
  for (const sec of diff.split(/^(?=diff --git )/m)) {
    if (size + sec.length > BUDGET) continue;
    size += sec.length;
    kept.push(sec);
    const m = sec.match(/^\+\+\+ (.+)$/m) ?? sec.match(/^--- (.+)$/m);
    if (m && m[1] !== "/dev/null") keptPaths.add(m[1]);
  }
  return { meta, files: files.map((f) => (keptPaths.has(f.path) ? f : { ...f, omitted: true })), diff: kept.join(""), truncated: true, stats };
}

function lineCount(abs: string): number | null {
  try {
    const buf = readFileSync(abs);
    if (buf.subarray(0, 8000).includes(0)) return null;
    let n = 0;
    for (const b of buf) if (b === 10) n++;
    if (buf.length && buf[buf.length - 1] !== 10) n++;
    return n;
  } catch {
    return 0;
  }
}

// "12\t3\tpath" per file ("-\t-\tpath" when binary; renames as "old => new" or "dir/{a => b}/f").
function parseNumstat(out: string) {
  const map = new Map<string, { binary: boolean; additions: number; deletions: number }>();
  for (const line of out.split("\n")) {
    if (!line) continue;
    const [add, del, ...rest] = line.split("\t");
    const raw = rest.join("\t");
    const m = raw.match(/^(.*)\{(.*) => (.*)\}(.*)$/);
    const path = m ? (m[1] + m[3] + m[4]).replace(/\/\//g, "/") : (raw.match(/^.* => (.*)$/)?.[1] ?? raw);
    map.set(path, { binary: add === "-", additions: add === "-" ? 0 : Number(add), deletions: del === "-" ? 0 : Number(del) });
  }
  return map;
}

// "M\tpath", "A\tpath", "D\tpath", "R100\told\tnew"
function parseNameStatus(out: string) {
  const list: { path: string; oldPath: string | null; status: string }[] = [];
  for (const line of out.split("\n")) {
    if (!line) continue;
    const [st, a, b] = line.split("\t");
    if (st[0] === "R" || st[0] === "C") list.push({ path: b, oldPath: a, status: "R" });
    else list.push({ path: a, oldPath: null, status: st[0] });
  }
  return list;
}

function mergeFiles(numstat: ReturnType<typeof parseNumstat>, nameStatus: ReturnType<typeof parseNameStatus>): FileChange[] {
  return nameStatus.map((f) => ({ ...f, ...(numstat.get(f.path) ?? { binary: false, additions: 0, deletions: 0 }) }));
}
