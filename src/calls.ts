// The calls form's reads: one var's code with what it calls and what calls it, and the neighbourhood
// of calls around a var, from the clojure form's clj-kondo index (src/clojure.ts: one analysis per
// project serves both). A var is "ns/name". A keyword in a var that names a .sql file in its
// namespace's folder (Iron Rainbow: `(sql/q pool :payment-states …)` runs payment_states.sql) is a call
// to that file, linked only when the file really exists; a file is "file:<path from the root>".
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { index, sourcePaths, type Index, type Var } from "./clojure";

// Where a use stands in the caller's file: row, first and past-last column (1-based).
type At = { row: number; col: number; endCol: number };
type Def = { id: string; ns: string; name: string; v: Var; file: string };
// What the reads of one index share: the defined vars, calls both ways, file lines, .sql links.
type Derived = {
  defs: Map<string, Def>;
  out: Map<string, { to: string; at: At }[]>;
  inc: Map<string, { from: string; at: At }[]>;
  lines: Map<string, string[]>;
  sql: Map<string, { to: string; at: At }[]>; // var → the .sql files it names
  sqlIn?: Map<string, { from: string; at: At }[]>; // .sql file → the vars naming it (built on first ask)
};

const cache = new WeakMap<Index, Derived>();
function derive(i: Index): Derived {
  const had = cache.get(i);
  if (had) return had;
  const defs = new Map<string, Def>();
  for (const [ns, list] of i.vars) for (const v of list) {
    const id = `${ns}/${v.name}`;
    const file = i.varFiles.get(id) ?? i.files.get(ns);
    if (file) defs.set(id, { id, ns, name: v.name, v, file });
  }
  const out = new Map<string, { to: string; at: At }[]>(), inc = new Map<string, { from: string; at: At }[]>();
  for (const c of i.calls) {
    if (!defs.has(c.from) || !defs.has(c.to)) continue;
    const at = { row: c.row, col: c.col, endCol: c.endCol };
    (out.get(c.from) ?? out.set(c.from, []).get(c.from)!).push({ to: c.to, at });
    (inc.get(c.to) ?? inc.set(c.to, []).get(c.to)!).push({ from: c.from, at });
  }
  const order = (a: At, b: At) => a.row - b.row || a.col - b.col;
  for (const l of out.values()) l.sort((a, b) => order(a.at, b.at));
  const d: Derived = { defs, out, inc, lines: new Map(), sql: new Map() };
  cache.set(i, d);
  return d;
}

function fileLines(d: Derived, file: string): string[] {
  let l = d.lines.get(file);
  if (!l) {
    try { l = readFileSync(file, "utf8").split("\n"); } catch { l = []; }
    d.lines.set(file, l);
  }
  return l;
}

const rel = (root: string, f: string) => (f.startsWith(root + "/") ? f.slice(root.length + 1) : f);
// A file's folder below the source path holding it ("features/common/payments/charge/backend").
function folderOf(root: string, file: string): string {
  const from = sourcePaths(root).filter((p) => file.startsWith(p + "/")).sort((a, b) => b.length - a.length)[0] ?? root;
  return dirname(file.slice(from.length + 1)).replace(/^\.$/, "");
}

// Strings, comments and plain keywords in Clojure code (keywords only matter here).
const TOKENS = /("(?:\\.|[^"\\])*")|(;[^\n]*)|(?<![\w:])(:[A-Za-z_*!?<>=+\-][\w\-?!.*<>=+]*)(?![\w/:])/g;

// The .sql files a var names, by a plain keyword whose name with - → _ is a file in its namespace's folder.
function sqlOf(d: Derived, root: string, id: string): { to: string; at: At }[] {
  const had = d.sql.get(id);
  if (had) return had;
  const def = d.defs.get(id);
  const found: { to: string; at: At }[] = [];
  if (def) {
    const dir = dirname(def.file);
    const lines = fileLines(d, def.file).slice(def.v.row - 1, def.v.endRow);
    const code = lines.join("\n");
    const starts = [0];
    for (const l of lines) starts.push(starts[starts.length - 1] + l.length + 1);
    for (const m of code.matchAll(TOKENS)) {
      if (!m[3]) continue;
      const file = join(dir, `${m[3].slice(1).replace(/-/g, "_")}.sql`);
      if (!existsSync(file)) continue;
      let li = 0;
      while (starts[li + 1] <= m.index!) li++;
      const col = m.index! - starts[li] + 1;
      found.push({ to: `file:${rel(root, file)}`, at: { row: def.v.row + li, col, endCol: col + m[3].length } });
    }
  }
  d.sql.set(id, found);
  return found;
}

// Who names each .sql file: the vars of every namespace whose folder holds .sql files.
function sqlCallers(d: Derived, root: string): Map<string, { from: string; at: At }[]> {
  if (d.sqlIn) return d.sqlIn;
  const m = new Map<string, { from: string; at: At }[]>();
  const hasSql = new Map<string, boolean>();
  for (const def of d.defs.values()) {
    const dir = dirname(def.file);
    if (!hasSql.has(dir)) { try { hasSql.set(dir, readdirSync(dir).some((f) => f.endsWith(".sql"))); } catch { hasSql.set(dir, false); } }
    if (!hasSql.get(dir)) continue;
    for (const s of sqlOf(d, root, def.id)) (m.get(s.to) ?? m.set(s.to, []).get(s.to)!).push({ from: def.id, at: s.at });
  }
  d.sqlIn = m;
  return m;
}

// A var as "ns/name", or the end of one ("state-gate/check" when one namespace ends in .state-gate).
function resolveId(d: Derived, root: string, s: string): string {
  s = s.trim();
  if (s.startsWith("file:")) {
    if (!s.endsWith(".sql") || s.includes("..") || !existsSync(join(root, s.slice(5)))) throw new Error(`no such .sql file: ${s.slice(5)}`);
    return s;
  }
  if (d.defs.has(s)) return s;
  const slash = s.lastIndexOf("/");
  if (slash <= 0) throw new Error(`"${s}" is not a var: give it as ns/name`);
  const ns = s.slice(0, slash), name = s.slice(slash + 1);
  const hits = [...d.defs.values()].filter((x) => x.name === name && x.ns.endsWith(`.${ns}`));
  if (hits.length === 1) return hits[0].id;
  if (hits.length > 1) throw new Error(`"${s}" is more than one var: ${hits.slice(0, 5).map((h) => h.id).join(", ")}`);
  throw new Error(`no var ${s} in this project`);
}

// A var's or .sql file's place, for lists and the map.
type Brief = { id: string; kind: "var" | "sql"; ns?: string; name: string; file: string; folder: string; row: number; private?: boolean };
function brief(d: Derived, root: string, id: string): Brief {
  if (id.startsWith("file:")) {
    const f = id.slice(5);
    return { id, kind: "sql", name: f.split("/").pop()!, file: f, folder: folderOf(root, join(root, f)), row: 1 };
  }
  const def = d.defs.get(id)!;
  return { id, kind: "var", ns: def.ns, name: def.name, file: rel(root, def.file), folder: folderOf(root, def.file), row: def.v.row, private: def.v.private };
}

// The first line of a var's doc, or of a .sql file's leading comment.
function firstDoc(d: Derived, root: string, id: string): string | undefined {
  if (id.startsWith("file:")) {
    const first = fileLines(d, join(root, id.slice(5)))[0] ?? "";
    return first.startsWith("--") ? first.replace(/^--\s*/, "") : undefined;
  }
  return d.defs.get(id)?.v.doc?.split("\n")[0].trim() || undefined;
}

const callsOf = (d: Derived, root: string, id: string) => (id.startsWith("file:") ? [] : [...(d.out.get(id) ?? []), ...sqlOf(d, root, id)]);
const callersOf = (d: Derived, root: string, id: string) => (id.startsWith("file:") ? sqlCallers(d, root).get(id) ?? [] : d.inc.get(id) ?? []);

// The project's fingerprint ({ same } while the page has this one): the page asks again for what it shows when it changes.
export async function callsFp(root: string, since?: string) {
  const i = await index(root);
  return since === i.fp ? { same: true } : { fp: i.fp };
}

// One var (or .sql file): its code, doc, what it calls (with where each call stands in its code) and
// who calls it (with the calling line's text).
export async function callsVar(root: string, s: string) {
  const i = await index(root);
  const d = derive(i);
  const id = resolveId(d, root, s);
  const me = brief(d, root, id);
  const def = d.defs.get(id);
  const abs = def?.file ?? join(root, me.file);
  const all = fileLines(d, abs);
  const lines = def ? all.slice(def.v.row - 1, def.v.endRow) : all.at(-1) === "" ? all.slice(0, -1) : all;
  const callees = new Map<string, Brief & { doc?: string; at: At[] }>();
  for (const c of callsOf(d, root, id)) {
    const e = callees.get(c.to) ?? { ...brief(d, root, c.to), doc: firstDoc(d, root, c.to), at: [] };
    callees.set(c.to, e);
    e.at.push(c.at);
  }
  const callers = new Map<string, Brief & { lines: { row: number; text: string }[] }>();
  for (const c of callersOf(d, root, id)) {
    const from = d.defs.get(c.from);
    if (!from) continue;
    const e = callers.get(c.from) ?? { ...brief(d, root, c.from), lines: [] };
    callers.set(c.from, e);
    if (!e.lines.some((l) => l.row === c.at.row)) e.lines.push({ row: c.at.row, text: (fileLines(d, from.file)[c.at.row - 1] ?? "").trim() });
  }
  return {
    fp: i.fp, ...me, endRow: def ? def.v.endRow : lines.length, lang: abs.split(".").pop(),
    varKind: def?.v.kind, args: def?.v.args ?? [], doc: def ? def.v.doc : firstDoc(d, root, id),
    code: lines.join("\n"),
    callees: [...callees.values()],
    callers: [...callers.values()].sort((a, b) => a.id.localeCompare(b.id)),
  };
}

// Columns of the map: no more than this many boxes at one step away (the rest are counted, not drawn).
const PER_STEP = 30;

// The neighbourhood of a var: what it reaches in down steps of calls and what reaches it in up steps
// of callers, each with its distance (callers negative), and every call between two of them.
export async function callsMap(root: string, s: string, up: number, down: number) {
  const i = await index(root);
  const d = derive(i);
  const start = resolveId(d, root, s);
  const dist = new Map<string, number>([[start, 0]]);
  const cut = new Map<number, number>();
  const walk = (steps: number, sign: 1 | -1, next: (id: string) => string[]) => {
    let front = [start];
    for (let k = 1; k <= steps && front.length; k++) {
      const found: string[] = [];
      for (const id of front) for (const n of next(id)) if (!dist.has(n) && !found.includes(n)) found.push(n);
      const kept = found.slice(0, PER_STEP);
      if (found.length > kept.length) cut.set(sign * k, found.length - kept.length);
      for (const n of kept) dist.set(n, sign * k);
      front = kept;
    }
  };
  walk(Math.max(0, Math.min(8, down)), 1, (id) => callsOf(d, root, id).map((c) => c.to));
  walk(Math.max(0, Math.min(8, up)), -1, (id) => callersOf(d, root, id).map((c) => c.from));
  const edges = new Set<string>();
  for (const id of dist.keys()) for (const c of callsOf(d, root, id)) if (dist.has(c.to)) edges.add(`${id}\u0000${c.to}`);
  return {
    fp: i.fp, start,
    nodes: [...dist].map(([id, n]) => ({ ...brief(d, root, id), dist: n })),
    edges: [...edges].map((e) => e.split("\u0000")),
    cut: Object.fromEntries(cut),
  };
}
