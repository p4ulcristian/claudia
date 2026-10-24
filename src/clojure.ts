// The clojure form's reads: a project's namespaces, what each requires, its vars and one var's
// source, from clj-kondo's analysis (run with --cache false, so nothing is written into the
// project). One analysis per root is kept and redone when a source file changes (the fingerprint).
import { readFileSync, readdirSync, statSync, existsSync } from "node:fs";
import { join, resolve } from "node:path";

export type Ns = { name: string; file: string; vars: number; requires: string[] };
export type Var = { name: string; args: string[]; doc?: string; private?: boolean; kind: string; row: number; endRow: number };
// uses: from namespace → to namespace → var name → times used.
type Index = { fp: string; namespaces: Ns[]; docs: Map<string, string | undefined>; uses: Map<string, Map<string, Map<string, number>>>; vars: Map<string, Var[]>; external: Map<string, string[]>; files: Map<string, string> };

const SOURCE = /\.(clj|cljs|cljc|bb)$/;
const SKIP = new Set(["node_modules", "target", ".git", ".cpcache", ".shadow-cljs", "out", ".clj-kondo"]);

// The folders with code: deps.edn / bb.edn :paths, shadow-cljs.edn and project.clj :source-paths;
// a folder of projects (none of those at the top) gives each one's; else src/, else the root itself.
function configPaths(dir: string): string[] {
  const found = new Set<string>();
  const vec = (file: string, key: string) => {
    const f = join(dir, file);
    if (!existsSync(f)) return;
    const m = readFileSync(f, "utf8").match(new RegExp(`${key}\\s*\\[([^\\]]*)\\]`));
    for (const s of m?.[1].match(/"([^"]+)"/g) ?? []) found.add(s.slice(1, -1));
  };
  vec("deps.edn", ":paths");
  vec("bb.edn", ":paths");
  vec("shadow-cljs.edn", ":source-paths");
  vec("project.clj", ":source-paths");
  return [...found].map((p) => resolve(dir, p)).filter((p) => p.startsWith(dir) && existsSync(p) && statSync(p).isDirectory());
}
export function sourcePaths(root: string): string[] {
  const own = configPaths(root);
  if (own.length) return own;
  let subs: string[] = [];
  try { subs = readdirSync(root, { withFileTypes: true }).filter((e) => e.isDirectory() && !e.name.startsWith(".") && !SKIP.has(e.name)).map((e) => join(root, e.name)); } catch {}
  const family = subs.flatMap(configPaths);
  if (family.length) return family;
  return existsSync(join(root, "src")) ? [join(root, "src")] : [root];
}

function walk(dir: string, out: string[]) {
  let entries;
  try { entries = readdirSync(dir, { withFileTypes: true }); } catch { return; }
  for (const e of entries) {
    if (e.name.startsWith(".") || SKIP.has(e.name)) continue;
    const p = join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (SOURCE.test(e.name)) out.push(p);
  }
}

// Changes when a source file is added, removed or edited.
export function fingerprint(root: string): string {
  const files: string[] = [];
  for (const d of sourcePaths(root)) walk(d, files);
  let h = files.length.toString(36);
  for (const f of files) { try { h = Bun.hash(h + f + statSync(f).mtimeMs).toString(36); } catch {} }
  return h;
}

const KIND: Record<string, string> = { defn: "fn", "defn-": "fn", defmacro: "macro", def: "def", defonce: "def", defmulti: "multi", defprotocol: "protocol", defrecord: "record", deftype: "type", declare: "declare", "goog-define": "def" };

async function analyse(root: string, fp: string): Promise<Index> {
  const paths = sourcePaths(root);
  const config = "{:output {:format :json} :analysis {:arglists true} :linters ^:replace {}}";
  const p = Bun.spawn(["clj-kondo", "--lint", ...paths, "--parallel", "--cache", "false", "--config", config], { cwd: root, stdout: "pipe", stderr: "pipe" });
  const [out, err] = await Promise.all([new Response(p.stdout).text(), new Response(p.stderr).text(), p.exited]);
  let a: any;
  try { a = JSON.parse(out).analysis; } catch { throw new Error(err.trim() || "clj-kondo gave no analysis"); }
  const files = new Map<string, string>();
  const docs = new Map<string, string | undefined>();
  for (const n of a["namespace-definitions"] ?? []) {
    if (!files.has(n.name)) files.set(n.name, resolve(root, n.filename));
    if (n.doc && !docs.get(n.name)) docs.set(n.name, n.doc);
    else if (!docs.has(n.name)) docs.set(n.name, undefined);
  }
  // .cljc files come twice (clj and cljs): one entry per name.
  const req = new Map<string, Set<string>>(), ext = new Map<string, Set<string>>();
  for (const u of a["namespace-usages"] ?? []) {
    if (!files.has(u.from) || u.from === u.to) continue;
    const into = files.has(u.to) ? req : ext;
    if (!into.has(u.from)) into.set(u.from, new Set());
    into.get(u.from)!.add(u.to);
  }
  const uses = new Map<string, Map<string, Map<string, number>>>();
  for (const u of a["var-usages"] ?? []) {
    if (!files.has(u.from) || !files.has(u.to) || u.from === u.to) continue;
    const by = uses.get(u.from) ?? new Map<string, Map<string, number>>();
    uses.set(u.from, by);
    const names = by.get(u.to) ?? new Map<string, number>();
    by.set(u.to, names);
    names.set(u.name, (names.get(u.name) ?? 0) + 1);
  }
  const vars = new Map<string, Var[]>();
  for (const v of a["var-definitions"] ?? []) {
    if (!files.has(v.ns)) continue;
    const list = vars.get(v.ns) ?? [];
    if (!vars.has(v.ns)) vars.set(v.ns, list);
    if (list.some((x) => x.name === v.name)) continue;
    const by = String(v["defined-by->lint-as"] ?? v["defined-by"] ?? "").split("/").pop() ?? "";
    list.push({ name: v.name, args: v["arglist-strs"] ?? [], doc: v.doc, private: v.private || by === "defn-" || undefined, kind: KIND[by] ?? by ?? "def", row: v.row, endRow: v["end-row"] ?? v.row });
  }
  for (const l of vars.values()) l.sort((x, y) => x.row - y.row);
  const namespaces: Ns[] = [...files.keys()].sort().map((name) => ({
    name, file: files.get(name)!.slice(root.length + 1), vars: vars.get(name)?.length ?? 0, requires: [...(req.get(name) ?? [])].sort(),
  }));
  return { fp, namespaces, docs, uses, vars, external: new Map([...ext].map(([k, s]) => [k, [...s].sort()])), files };
}

// One clj-kondo run per root at a time (a big project takes half a minute on every core): while one
// runs, the last finished index answers; a change that lands meanwhile is read by the next run, which
// the next ask after this one finishes starts.
const roots = new Map<string, { done?: Index; running?: Promise<Index> }>();
async function index(root: string): Promise<Index> {
  const r = roots.get(root) ?? {};
  roots.set(root, r);
  if (r.running) return r.done ?? r.running;
  const fp = fingerprint(root);
  if (r.done?.fp === fp) return r.done;
  const run = analyse(root, fp);
  r.running = run;
  run.then((i) => { r.done = i; }, () => {}).finally(() => { r.running = undefined; });
  return r.done ?? run;
}

// Every namespace with what it requires inside the project ({ same } while the page has this index).
export async function namespaces(root: string, since?: string) {
  const i = await index(root);
  if (since && since === i.fp) return { same: true };
  return { fp: i.fp, paths: sourcePaths(root).map((p) => p.slice(root.length + 1) || "."), namespaces: i.namespaces };
}

// One namespace's doc, its vars in file order, and what it requires from outside the project.
export async function nsDetail(root: string, ns: string) {
  const i = await index(root);
  if (!i.files.has(ns)) throw new Error("no such namespace");
  return { doc: i.docs.get(ns), vars: i.vars.get(ns) ?? [], external: i.external.get(ns) ?? [] };
}

// One var's source: its lines from the file, as clj-kondo found them.
export async function varSource(root: string, ns: string, name: string) {
  const i = await index(root);
  const v = i.vars.get(ns)?.find((x) => x.name === name);
  const file = i.files.get(ns);
  if (!v || !file) throw new Error("no such var");
  const lines = readFileSync(file, "utf8").split("\n").slice(v.row - 1, v.endRow);
  return { code: lines.join("\n"), file: file.slice(root.length + 1), row: v.row, lang: file.split(".").pop() };
}

// What crosses one arrow: the vars namespaces under from use from namespaces under to. A side is a
// prefix (the namespace itself and everything under it) or, exact, one namespace only.
export async function edgeUses(root: string, from: string, fromExact: boolean, to: string, toExact: boolean) {
  const i = await index(root);
  const under = (n: string, p: string, exact: boolean) => n === p || (!exact && n.startsWith(p + "."));
  const found = new Map<string, { ns: string; name: string; count: number; from: Set<string> }>();
  for (const [f, by] of i.uses) {
    if (!under(f, from, fromExact)) continue;
    for (const [t, names] of by) {
      if (!under(t, to, toExact)) continue;
      for (const [name, count] of names) {
        const k = `${t}/${name}`;
        const e = found.get(k) ?? { ns: t, name, count: 0, from: new Set<string>() };
        found.set(k, e);
        e.count += count;
        e.from.add(f);
      }
    }
  }
  const used = [...found.values()].sort((a, b) => a.ns.localeCompare(b.ns) || b.count - a.count || a.name.localeCompare(b.name));
  return { used: used.map((u) => ({ ns: u.ns, name: u.name, count: u.count, from: [...u.from].sort() })) };
}
