// The git form (src/forms.ts): a repo's commit graph as GitGud draws it, in chalk. Read live through
// the runner (src/git.ts), asked again every few seconds with the graph's fingerprint, so it redraws
// when a commit, checkout, fetch or edit happens. A click on a commit opens its files and diff below
// the graph. Read only: nothing here changes the repo.
import React, { useEffect, useMemo, useRef, useState } from "react";
import { useApi } from "./api";
import { layoutGraph, type Commit, type Ref } from "../src/git-layout";
import { codeTokens, type Token } from "./shiki";

type Data = Record<string, any>;
type Branch = { name: string; kind: "both" | "local" | "remote"; local: boolean; current?: boolean; upstream?: string | null; tracked?: boolean; gone?: boolean; ahead?: number; behind?: number };
type Dirty = { total: number; staged: number; unstaged: number; untracked: number; files: { path: string; status: string }[] };
type Log = { fp?: string; commits: Commit[]; head: string; currentBranch: string; detached?: boolean; branches: Branch[]; dirty: Dirty; fetched?: string | null; more?: boolean };
type FileChange = { path: string; oldPath: string | null; status: string; binary: boolean; additions: number; deletions: number; omitted?: boolean };
type Detail = { meta: { hash: string; author: string; date: string; when: string; parents: string[]; subject: string; body: string }; files: FileChange[]; diff: string; truncated?: boolean; stats: { files: number; additions: number; deletions: number } };

const list = (v: unknown): any[] => (Array.isArray(v) ? v : []);
const POLL_MS = 2500;
const ROW_H = 50;
const LANE_W = 18;
const PAD = 8;
const GUTTER = 34; // the time between rows is written here, left of the lanes

// Lane colours: the board's chalk colours, then the chat colours. Merged-in history is grey.
const LANES = ["var(--green)", "var(--blue)", "var(--violet)", "var(--amber)", "var(--red)", "var(--c-teal)", "var(--c-pink)", "var(--c-orange)", "var(--c-lime)", "var(--c-sky)", "var(--c-purple)", "var(--c-yellow)", "var(--c-cyan)", "var(--c-magenta)"];
const GREY = "color-mix(in srgb, var(--muted) 55%, transparent)";
const laneColor = (k?: number | null) => (k == null ? GREY : LANES[k % LANES.length]);
const WORKING = "var(--amber)";

// Rows are pushed apart by the time between them, on a log scale: 1 h ≈ 31 px, 1 day ≈ 86, capped.
const GAP_MIN = 10 * 60;
const gapPx = (s: number) => (s > GAP_MIN ? Math.min(110, 12 * Math.log2(s / GAP_MIN)) : 0);
function ago(s: number): string {
  const m = s / 60, h = m / 60, d = h / 24;
  return m < 60 ? `${Math.round(m)}m` : h < 24 ? `${Math.round(h)}h` : d < 14 ? `${Math.round(d)}d` : d < 63 ? `${Math.round(d / 7)}w` : d < 548 ? `${Math.round(d / 30.4)}mo` : `${(d / 365).toFixed(1)}y`;
}

// Does this commit answer to rev (a hash or its start, a branch or tag name, HEAD)?
const matches = (c: Commit, rev: string, head: string) =>
  rev === "HEAD" ? c.hash === head : (rev.length >= 4 && c.hash.startsWith(rev)) || c.refs.some((r) => r.name === rev);

export function GitForm({ data, chat }: { data: Data; chat?: string }) {
  const demo: Log | null = data.demo && typeof data.demo === "object" ? data.demo : null;
  const repo: string = typeof data.repo === "string" ? data.repo : "";
  const live = !demo && !!repo && !!chat;
  const [log, setLog] = useState<Log | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [only, setOnly] = useState<string[] | null>(Array.isArray(data.branches) ? data.branches.map(String) : null);
  const [limit, setLimit] = useState(Number(data.limit) > 0 ? Number(data.limit) : 100);
  const [open, setOpen] = useState<string | null>(typeof data.commit === "string" ? data.commit : null);
  useEffect(() => { if (Array.isArray(data.branches)) setOnly(data.branches.map(String)); }, [JSON.stringify(data.branches)]);
  useEffect(() => { if (Number(data.limit) > 0) setLimit(Number(data.limit)); }, [data.limit]);
  useEffect(() => { if (typeof data.commit === "string") setOpen(data.commit); }, [data.commit]);

  const api = useApi();
  const base = `/api/chats/${chat}/git?repo=${encodeURIComponent(repo)}`;
  // The graph, asked again every POLL_MS (only while the page is seen); the runner answers
  // { same: true } until the repo changes. A new branch filter or limit starts over.
  const fp = useRef<string | undefined>(undefined);
  useEffect(() => {
    if (!live) return;
    fp.current = undefined;
    let gone = false;
    let timer: ReturnType<typeof setTimeout>;
    const ask = async () => {
      if (!document.hidden) {
        try {
          const j = await api.fetch(`${base}&limit=${limit}${only ? `&branches=${encodeURIComponent(only.join(","))}` : ""}${fp.current ? `&fp=${fp.current}` : ""}`).then((r) => r.json());
          if (gone) return;
          if (j.error) setError(j.error);
          else if (!j.same) { fp.current = j.fp; setLog(j); setError(null); }
        } catch {}
      }
      if (!gone) timer = setTimeout(ask, POLL_MS);
    };
    ask();
    return () => { gone = true; clearTimeout(timer); };
  }, [api, live, base, limit, only?.join(",")]);

  const shown: Log | null = useMemo(() => {
    if (!demo) return log;
    return { ...demo, commits: layoutGraph(list(demo.commits).map((c) => ({ ...c, refs: list(c.refs) }))) };
  }, [demo, log]);

  const marks = list(data.marks);
  const markOf = (c: Commit | "WORKING") =>
    marks.find((m) => typeof m.commit === "string" && (c === "WORKING" ? m.commit === "WORKING" : matches(c, m.commit, shown?.head ?? "")));
  const toggleBranch = (name: string) => {
    const all = shown?.branches.map((b) => b.name) ?? [];
    const cur = only ?? all;
    const next = cur.includes(name) ? cur.filter((n) => n !== name) : [...cur, name];
    setOnly(next.length === all.length || !next.length ? null : next);
  };
  const openHash = open && shown ? (open === "WORKING" ? "WORKING" : shown.commits.find((c) => matches(c, open, shown.head))?.hash ?? open) : null;

  const home = repo.replace(/^\/home\/[^/]+/, "~");
  const cut = home.lastIndexOf("/") + 1;
  return (
    <div className="g-git">
      <div className="f-code-head">
        <span className="f-file" title={repo}>
          <span className="dir">{demo ? "~/Work/apps/" : home.slice(0, cut)}</span><b>{demo ? "shop" : home.slice(cut)}</b>
        </span>
        {shown && <span className="g-on" title={shown.detached ? "HEAD is not on a branch" : "the checked-out branch"}>{shown.detached ? "detached" : `⎇ ${shown.currentBranch}`}</span>}
        {shown?.fetched && <span className="g-fetched" title={`last fetch: ${new Date(shown.fetched).toLocaleString()}`}>fetched {ago((Date.now() - Date.parse(shown.fetched)) / 1000)} ago</span>}
        {live && <span className="f-kind real" title="Read live from the repo; redraws when it changes">live</span>}
        {demo && <span className="f-kind made">example</span>}
      </div>
      {error && <p className="f-note f-code-err">{error}</p>}
      {!live && !demo ? <p className="f-note">Open this chat to see its repo.</p> : !shown ? (!error && <p className="f-note">…</p>) : (
        <>
          <BranchBar branches={shown.branches} commits={shown.commits} only={only} onToggle={toggleBranch} onAll={() => setOnly(null)} />
          <div className={`g-main ${open ? "split" : ""}`}>
          <div className="g-scroll">
            {shown.commits.length ? (
              <Graph log={shown} openHash={openHash} markOf={markOf} onOpen={(h) => setOpen(openHash === h ? null : h)} />
            ) : <p className="f-note">No commits yet.</p>}
            {shown.more && <button type="button" className="f-fold" onClick={() => setLimit((n) => n + 100)}>⋯ more commits</button>}
          </div>
          {open && (demo
            ? <p className="f-note g-detail-note">A commit's files and diff open here.</p>
            : <CommitView key={open} base={base} rev={open} fp={open === "WORKING" ? log?.fp : undefined} close={() => setOpen(null)} />)}
          </div>
        </>
      )}
    </div>
  );
}

// Every branch with its lane colour, where it lives (⇅ local + remote, ⌂ local only, ☁ remote only)
// and how it stands against its upstream. A click hides or shows it in the graph.
const KIND = { both: ["⇅", "local + remote"], local: ["⌂", "local only"], remote: ["☁", "remote only"] } as const;
function BranchBar({ branches, commits, only, onToggle, onAll }: { branches: Branch[]; commits: Commit[]; only: string[] | null; onToggle: (n: string) => void; onAll: () => void }) {
  const colors = new Map<string, string>();
  for (const c of commits) for (const r of c.refs) if (r.type !== "tag" && r.type !== "detached") colors.set(r.name, laneColor(c.colorKey));
  if (!branches.length) return null;
  return (
    <div className="g-branches">
      {branches.map((b, i) => {
        const on = !only || only.includes(b.name);
        const color = colors.get(b.name);
        const kind = KIND[b.kind] ?? KIND.local;
        return (
          <React.Fragment key={b.name}>
            {!b.local && i > 0 && branches[i - 1].local && <span className="g-sep" />}
            <button
              type="button"
              className={`g-branch ${b.current ? "current" : ""} ${on ? "" : "off"}`}
              title={`${b.name} · ${kind[1]}${b.upstream ? ` (tracks ${b.upstream})` : ""} · click to ${on ? "hide" : "show"}`}
              onClick={() => onToggle(b.name)}
            >
              <span className="k">{kind[0]}</span>
              <i style={{ background: on && color ? color : "transparent", borderColor: on && color ? color : "var(--muted)" }} />
              {b.name}
              <Sync r={b} />
            </button>
          </React.Fragment>
        );
      })}
      {only && <button type="button" className="f-copy" onClick={onAll}>show all</button>}
    </div>
  );
}

function Sync({ r }: { r: { gone?: boolean; tracked?: boolean; ahead?: number; behind?: number } }) {
  if (r.gone) return <span className="g-sync gone" title="its upstream was deleted">gone</span>;
  if (!r.tracked) return null;
  if (!r.ahead && !r.behind) return <span className="g-sync ok" title="same as its upstream">✓</span>;
  return <span className="g-sync drift" title="commits ahead / behind its upstream">{r.ahead ? `↑${r.ahead}` : ""}{r.behind ? `↓${r.behind}` : ""}</span>;
}

function RefChip({ r, color }: { r: Ref; color: string }) {
  const local = r.type === "head" || r.type === "branch";
  return (
    <span className={`g-ref ${r.type}`} style={local ? ({ "--rc": color } as React.CSSProperties) : undefined}>
      {r.type === "head" ? "⎇ " : r.type === "tag" ? "◆ " : ""}{r.name}
      {local ? (r.tracked || r.gone ? <Sync r={r} /> : <span className="g-sync only" title="not on any remote">⌂</span>) : r.type === "remote" ? <span className="g-sync only" title="only on the remote">☁</span> : null}
    </span>
  );
}

// The graph: chalk lanes and nodes in an SVG beside one row per commit, the uncommitted changes as a
// dashed node above HEAD in HEAD's lane, rows spaced by the time between them.
function Graph({ log, openHash, markOf, onOpen }: { log: Log; openHash: string | null; markOf: (c: Commit | "WORKING") => any; onOpen: (h: string) => void }) {
  const { commits, head, dirty } = log;
  const headCommit = commits.find((c) => c.hash === head);
  const working = !!(dirty?.total && headCommit);
  const off = working ? 1 : 0;
  const rowOf = new Map(commits.map((c, i) => [c.hash, i + off]));
  const n = commits.length + off;
  const times = [...(working ? [Date.now() / 1000] : []), ...commits.map((c) => c.time)];
  const gap = times.map((t, r) => (r ? Math.max(0, times[r - 1] - t) : 0)); // topo order interleaves: only forward gaps
  const top: number[] = [];
  for (let r = 0; r < n; r++) top[r] = r ? top[r - 1] + ROW_H + gapPx(gap[r]) : 0;
  const height = n ? top[n - 1] + ROW_H : 0;
  const maxCol = commits.reduce((m, c) => Math.max(m, c.col ?? 0), 0);
  const width = (maxCol + 1) * LANE_W + PAD;
  const x = (col = 0) => PAD / 2 + col * LANE_W + LANE_W / 2;
  const y = (r: number) => top[r] + ROW_H / 2;
  const wMark = markOf("WORKING");

  return (
    <div className="g-graph" style={{ height, "--lanes": `${GUTTER + width}px` } as React.CSSProperties}>
      {gap.map((g, r) => (gapPx(g) >= 18 ? <span key={`g${r}`} className="g-gap" style={{ top: top[r] - gapPx(g) / 2 - 8 }} title={`${ago(g)} between these commits`}>{ago(g)}</span> : null))}
      <svg className="g-lanes" width={width} height={height} style={{ left: GUTTER }}>
        {/* The chalk filter is sized by what it covers: a straight line alone has no width and vanished, so the
            lines share one group with a rect as big as the graph. */}
        <g className="g-paths">
        <rect width={width} height={height} fill="none" />
        {working && <path d={`M${x(headCommit!.col)} ${y(0)}V${y(1)}`} style={{ stroke: WORKING }} strokeDasharray="3 4" />}
        {commits.map((c) => c.parents.map((p) => {
          const j = rowOf.get(p);
          if (j === undefined) return null;
          const parent = commits[j - off];
          const x1 = x(c.col), y1 = y(rowOf.get(c.hash)!), x2 = x(parent.col), y2 = y(j), mid = (y1 + y2) / 2;
          return <path key={c.hash + p} d={`M${x1} ${y1}C${x1} ${mid},${x2} ${mid},${x2} ${y2}`} style={{ stroke: laneColor(parent.colorKey) }} />;
        }))}
        </g>
        {working && <circle className="node working" cx={x(headCommit!.col)} cy={y(0)} r={5.5} style={{ stroke: WORKING }} />}
        {commits.map((c, i) => (
          <circle key={c.hash} className={`node ${c.hash === head ? "head" : ""}`} cx={x(c.col)} cy={y(i + off)} r={c.hash === head ? 6.5 : 4.5} style={{ stroke: laneColor(c.colorKey) }} />
        ))}
      </svg>
      {working && (
        <button type="button" className={`g-row working ${openHash === "WORKING" ? "open" : ""} ${wMark ? "marked" : ""}`} style={{ top: 0, "--mark": wMark?.color ? `var(--${wMark.color})` : "var(--amber)" } as React.CSSProperties} onClick={() => onOpen("WORKING")} data-item={wMark?.id}>
          <span className="g-subj">
            <span className="g-ref working">uncommitted</span>
            {dirty.total} change{dirty.total === 1 ? "" : "s"}
            <span className="g-meta-in">{[dirty.staged && `${dirty.staged} staged`, dirty.unstaged && `${dirty.unstaged} unstaged`, dirty.untracked && `${dirty.untracked} untracked`].filter(Boolean).join(" · ")}</span>
          </span>
          <span className="g-meta">
            {wMark?.note ? <span className="g-note">{wMark.note}</span> : dirty.files.slice(0, 4).map((f) => <span key={f.path} className={`g-wf st-${f.status === "?" ? "U" : f.status === "D" ? "D" : f.status === "A" ? "A" : "M"}`}>{f.path.split("/").pop()}</span>)}
            {!wMark?.note && dirty.files.length > 4 && <span>+{dirty.files.length - 4}</span>}
          </span>
        </button>
      )}
      {commits.map((c, i) => {
        const m = markOf(c);
        const color = laneColor(c.colorKey);
        return (
          <button
            key={c.hash}
            type="button"
            className={`g-row ${c.hash === head ? "is-head" : ""} ${openHash === c.hash ? "open" : ""} ${m ? "marked" : ""}`}
            style={{ top: top[i + off], "--mark": m?.color ? `var(--${m.color})` : "var(--amber)" } as React.CSSProperties}
            onClick={() => onOpen(c.hash)}
            data-item={m?.id}
            title={c.subject}
          >
            <span className="g-subj">
              {c.refs.map((r) => <RefChip key={r.type + r.name} r={r} color={color} />)}
              {c.subject}
            </span>
            <span className="g-meta">
              <span className="h">{c.hash.slice(0, 7)}</span> · {c.author} · {c.when}
              {m?.note && <span className="g-note">{m.note}</span>}
            </span>
          </button>
        );
      })}
    </div>
  );
}

// One commit (or the uncommitted changes), beside the graph: what it says, its files with +/−, and each file's diff.
// The uncommitted view is read again whenever the graph's fingerprint changes.
function CommitView({ base, rev, fp, close }: { base: string; rev: string; fp?: string; close: () => void }) {
  const api = useApi();
  const [d, setD] = useState<Detail | { error: string } | null>(null);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => { ref.current?.scrollTo({ top: 0 }); }, [rev]);
  useEffect(() => {
    let gone = false;
    api.fetch(`${base}&commit=${encodeURIComponent(rev)}`).then((r) => r.json()).then((j) => { if (!gone) setD(j); }).catch(() => !gone && setD({ error: "can't read it" }));
    return () => { gone = true; };
  }, [api, base, rev, fp]);
  const patches = useMemo(() => {
    const out = new Map<string, string>();
    if (!d || !("diff" in d)) return out;
    for (const sec of d.diff.split(/^(?=diff --git )/m)) {
      const m = sec.match(/^\+\+\+ (.+)$/m);
      const p = m && m[1] !== "/dev/null" ? m[1] : sec.match(/^--- (.+)$/m)?.[1];
      if (p) out.set(p.replace(/\t.*$/, ""), sec);
    }
    return out;
  }, [d]);
  const [shut, setShut] = useState<Set<string> | null>(null); // files folded by hand (null: the default)
  if (!d) return <div className="g-detail" ref={ref}><p className="f-note">…</p></div>;
  if ("error" in d) return <div className="g-detail" ref={ref}><p className="f-note f-code-err">{d.error}</p></div>;
  const { meta, files, stats } = d;
  // Small changes open whole; big ones start folded.
  const small = files.length <= 8 && stats.additions + stats.deletions <= 600;
  const isOpen = (p: string) => (shut ? !shut.has(p) : small);
  const toggle = (p: string) => setShut((s) => { const n = new Set(s ?? (small ? [] : files.map((f) => f.path))); n.has(p) ? n.delete(p) : n.add(p); return n; });
  return (
    <div className="g-detail" ref={ref}>
      <div className="g-detail-head">
        <div>
          <b className="g-detail-subj">{meta.subject}</b>
          <div className="g-meta">
            {meta.hash !== "WORKING" && <><span className="h">{meta.hash.slice(0, 10)}</span> · {meta.author} · {new Date(meta.date).toLocaleString()} · </>}
            {stats.files} file{stats.files === 1 ? "" : "s"} <span className="st-A">+{stats.additions}</span> <span className="st-D">−{stats.deletions}</span>
            {meta.parents.length > 1 && <> · merge (diff against its first parent)</>}
          </div>
        </div>
        <button type="button" className="f-copy" onClick={close}>close</button>
      </div>
      {meta.body && <pre className="g-body">{meta.body}</pre>}
      {!files.length && <p className="f-note">No file changes.</p>}
      {files.map((f) => (
        <div key={f.path} className="g-file">
          <button type="button" className={`g-file-row ${isOpen(f.path) ? "open" : ""}`} onClick={() => toggle(f.path)}>
            <span className={`st st-${f.status === "?" ? "U" : f.status === "D" ? "D" : f.status === "A" ? "A" : "M"}`}>{f.status === "?" ? "U" : f.status}</span>
            <span className="nm">{f.oldPath ? `${f.oldPath} → ` : ""}{f.path}</span>
            {f.binary ? <span className="g-meta-in">binary</span> : <><span className="st-A">+{f.additions}</span><span className="st-D">−{f.deletions}</span></>}
          </button>
          {isOpen(f.path) && !f.binary && <FilePatch base={base} rev={rev} file={f} patch={patches.get(f.path)} />}
        </div>
      ))}
    </div>
  );
}

function FilePatch({ base, rev, file, patch }: { base: string; rev: string; file: FileChange; patch?: string }) {
  const api = useApi();
  const [text, setText] = useState<string | undefined>(patch);
  useEffect(() => {
    if (patch !== undefined) return setText(patch);
    if (!file.omitted) return setText("");
    api.fetch(`${base}&commit=${encodeURIComponent(rev)}&file=${encodeURIComponent(file.path)}`).then((r) => r.json()).then((j) => setText(j.diff ?? "")).catch(() => setText(""));
  }, [patch, file.path]);
  if (text === undefined) return <p className="f-note">…</p>;
  return text ? <Diff patch={text} path={file.path} /> : <p className="f-note">No text changes.</p>;
}

// ---- a unified diff, coloured like the code form, with the changed words marked ----

type Row = { kind: "hunk"; text: string } | { kind: "line"; sign: "+" | "-" | ""; n: number | null; text: string; words?: [number, number][] };

function parsePatch(patch: string): Row[] {
  const rows: Row[] = [];
  let newN = 0, inHunk = false;
  for (const l of patch.split("\n")) {
    const h = l.match(/^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@(.*)$/);
    if (h) { newN = +h[2]; inHunk = true; rows.push({ kind: "hunk", text: `line ${newN}${h[3] ? ` ·${h[3]}` : ""}` }); continue; }
    if (!inHunk || l.startsWith("\\")) continue;
    if (l[0] === "+") rows.push({ kind: "line", sign: "+", n: newN++, text: l.slice(1) });
    else if (l[0] === "-") { rows.push({ kind: "line", sign: "-", n: null, text: l.slice(1) }); }
    else if (l[0] === " ") { rows.push({ kind: "line", sign: "", n: newN++, text: l.slice(1) }); }
  }
  markWords(rows);
  return rows;
}

// Each run of removed lines followed by added lines is paired line by line, and the words that differ
// in each pair are marked (a word diff by longest common subsequence; long lines are left whole).
function markWords(rows: Row[]) {
  for (let i = 0; i < rows.length; ) {
    const del: number[] = [], add: number[] = [];
    while (i < rows.length && rows[i].kind === "line" && (rows[i] as any).sign === "-") del.push(i++);
    while (i < rows.length && rows[i].kind === "line" && (rows[i] as any).sign === "+") add.push(i++);
    if (!del.length && !add.length) { i++; continue; }
    for (let k = 0; k < Math.min(del.length, add.length); k++) {
      const a = rows[del[k]] as Extract<Row, { kind: "line" }>, b = rows[add[k]] as Extract<Row, { kind: "line" }>;
      const [wa, wb] = wordDiff(a.text, b.text);
      if (wa) a.words = wa;
      if (wb) b.words = wb;
    }
  }
}

function wordDiff(a: string, b: string): [[number, number][] | null, [number, number][] | null] {
  const split = (s: string) => s.match(/\s+|\w+|[^\w\s]/g) ?? [];
  const x = split(a), y = split(b);
  if (x.length * y.length > 40_000) return [null, null];
  const L = Array.from({ length: x.length + 1 }, () => new Uint16Array(y.length + 1));
  for (let i = x.length - 1; i >= 0; i--) for (let j = y.length - 1; j >= 0; j--) L[i][j] = x[i] === y[j] ? L[i + 1][j + 1] + 1 : Math.max(L[i + 1][j], L[i][j + 1]);
  const ra: [number, number][] = [], rb: [number, number][] = [];
  const push = (r: [number, number][], s: number, e: number) => { const last = r[r.length - 1]; if (last && last[1] === s) last[1] = e; else r.push([s, e]); };
  let i = 0, j = 0, pa = 0, pb = 0;
  while (i < x.length || j < y.length) {
    if (i < x.length && j < y.length && x[i] === y[j]) { pa += x[i++].length; pb += y[j++].length; }
    else if (j < y.length && (i >= x.length || L[i][j + 1] >= L[i + 1][j])) { push(rb, pb, pb + y[j].length); pb += y[j++].length; }
    else { push(ra, pa, pa + x[i].length); pa += x[i++].length; }
  }
  // A line that changed almost whole reads better without marks.
  const whole = (r: [number, number][], s: string) => r.reduce((n, [p, q]) => n + q - p, 0) > s.trim().length * 0.7;
  return [ra.length && !whole(ra, a) ? ra : null, rb.length && !whole(rb, b) ? rb : null];
}

// The shiki tokens of a line, cut where the marked words start and end.
function pieces(tokens: Token[] | undefined, text: string, words?: [number, number][]) {
  const toks = tokens?.length ? tokens : [{ content: text } as Token];
  if (!words) return toks.map((t) => ({ ...t, hot: false }));
  const out: (Token & { hot: boolean })[] = [];
  let at = 0;
  for (const t of toks) {
    let s = 0;
    while (s < t.content.length) {
      const pos = at + s;
      const w = words.find(([p, q]) => pos >= p && pos < q);
      const next = w ? w[1] : Math.min(...words.map(([p]) => p).filter((p) => p > pos), Infinity);
      const len = Math.min(t.content.length - s, next - pos);
      out.push({ ...t, content: t.content.slice(s, s + len), hot: !!w });
      s += len;
    }
    at += t.content.length;
  }
  return out;
}

const FONT = (f?: number): React.CSSProperties | undefined =>
  f ? { fontStyle: f & 1 ? "italic" : undefined, fontWeight: f & 2 ? 700 : undefined, textDecoration: f & 4 ? "underline" : undefined } : undefined;

function Diff({ patch, path }: { patch: string; path: string }) {
  const rows = useMemo(() => parsePatch(patch), [patch]);
  const lines = rows.filter((r): r is Extract<Row, { kind: "line" }> => r.kind === "line");
  const lang = path.includes(".") ? path.split(".").pop() : undefined;
  const [tokens, setTokens] = useState<Token[][] | null>(null);
  useEffect(() => {
    let gone = false;
    setTokens(null);
    codeTokens(lines.map((l) => l.text).join("\n"), lang).then((t) => { if (!gone) setTokens(t); }).catch(() => {});
    return () => { gone = true; };
  }, [rows, lang]);
  let li = 0;
  return (
    <div className="f-lines g-diff">
      {rows.map((r, i) => {
        if (r.kind === "hunk") return <div key={i} className="f-fold g-hunk">⋯ {r.text}</div>;
        const k = li++;
        return (
          <div key={i} className={`f-ln ${r.sign === "+" ? "add" : r.sign === "-" ? "del" : ""}`}>
            <span className="num">{r.n ?? ""}</span>
            <span className="sign">{r.sign === "-" ? "−" : r.sign}</span>
            <code>
              {r.text ? pieces(tokens?.[k], r.text, r.words).map((t, j) => (
                <span key={j} className={t.hot ? "w" : undefined} style={{ color: t.color, ...FONT(t.fontStyle) }}>{t.content}</span>
              )) : " "}
            </code>
          </div>
        );
      })}
    </div>
  );
}
