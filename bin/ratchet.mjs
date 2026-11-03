#!/usr/bin/env node
// The code-shape ratchet (CLAUDE.md, "How the code is written"): per source file, how many local
// names shadow a var (clj-kondo's shadowed-var) and which functions run over 30 lines. Both may only
// go down. ratchet.json holds what is allowed; the check fails when a file has more than it allows,
// and also when it has less (so every gain is written down and stays).
//
//   node bin/ratchet.mjs          check (the PR check runs this)
//   node bin/ratchet.mjs --write  write what the code has now into ratchet.json
//
// A "function" is a top-level defn, defn-, defmethod or defmacro, a re-frame reg-* call, or a def
// whose value is an fn. Its length is from its first line to its last, docstring included.

import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync, readdirSync, statSync } from "node:fs";
import { join, relative } from "node:path";

const root = new URL("..", import.meta.url).pathname;
const baselineFile = join(root, "ratchet.json");
const maxLines = 30;

function sourceFiles(dir) {
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) return sourceFiles(p);
    return /\.clj[sc]?$/.test(name) ? [p] : [];
  });
}

// A form's head symbol, the name it defines (metadata such as ^:private skipped; a defmethod's name
// carries its dispatch value) and whether the value right after the name is an fn.
function formHeader(text) {
  const tokens = text.match(/\(fn\b|[^\s()\[\]{}",]+/g) ?? [];
  const head = tokens[0];
  const rest = tokens.slice(1).filter((t) => !t.startsWith("^"));
  const name = head === "defmethod" ? `${rest[0]} ${rest[1]}` : rest[0];
  return { head, name, fnValue: rest[1] === "(fn" };
}

// The top-level forms of a file as {head, name, start, end} (1-based lines). Skips strings, regexes,
// character literals and comments so their brackets do not count.
function topLevelForms(text) {
  const forms = [];
  let line = 1, depth = 0, current = null;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (c === "\n") { line++; continue; }
    if (c === ";") { while (i < text.length && text[i] !== "\n") i++; i--; continue; }
    if (c === "\\") { i++; if (text[i] === "\n") line++; continue; }
    if (c === '"') {
      for (i++; i < text.length && text[i] !== '"'; i++) {
        if (text[i] === "\\") i++;
        if (text[i] === "\n") line++;
      }
      continue;
    }
    if (c === "(" || c === "[" || c === "{") {
      if (depth === 0 && c === "(") {
        current = { ...formHeader(text.slice(i + 1, i + 400)), start: line };
      }
      depth++;
      continue;
    }
    if (c === ")" || c === "]" || c === "}") {
      depth--;
      if (depth === 0 && current) { current.end = line; forms.push(current); current = null; }
    }
  }
  return forms;
}

const defining = new Set(["defn", "defn-", "defmethod", "defmacro"]);

function isFunction({ head, fnValue }) {
  if (!head) return false;
  if (defining.has(head)) return true;
  if (/(^|\/)reg-(event-fx|event-db|event-ctx|sub|fx|cofx)$/.test(head)) return true;
  return (head === "def" || head === "defonce") && fnValue;
}

function longFunctions(file) {
  return topLevelForms(readFileSync(file, "utf8"))
    .filter((f) => isFunction(f) && f.end - f.start + 1 > maxLines)
    .map((f) => f.name ?? f.head)
    .sort();
}

function shadowedCounts() {
  let out;
  try {
    out = execFileSync("clj-kondo", ["--lint", "src", "--config", "{:output {:format :json}}"],
      { cwd: root, encoding: "utf8", maxBuffer: 64 << 20 });
  } catch (e) {
    out = e.stdout; // clj-kondo exits non-zero when it finds warnings
  }
  const counts = {};
  for (const f of JSON.parse(out).findings) {
    if (f.type === "shadowed-var") counts[f.filename] = (counts[f.filename] ?? 0) + 1;
  }
  return counts;
}

function measure() {
  const shadowed = shadowedCounts();
  const files = {};
  for (const p of sourceFiles(join(root, "src")).sort()) {
    const file = relative(root, p);
    const entry = { shadowed: shadowed[file] ?? 0, long: longFunctions(p) };
    if (entry.shadowed || entry.long.length) files[file] = entry;
  }
  return files;
}

function check(now, allowed) {
  const problems = [];
  for (const file of new Set([...Object.keys(now), ...Object.keys(allowed)])) {
    const n = now[file] ?? { shadowed: 0, long: [] };
    const a = allowed[file] ?? { shadowed: 0, long: [] };
    if (n.shadowed > a.shadowed) problems.push(`${file}: ${n.shadowed} shadowed names, ${a.shadowed} allowed`);
    if (n.shadowed < a.shadowed) problems.push(`${file}: ${n.shadowed} shadowed names now, ratchet.json still allows ${a.shadowed} (run --write)`);
    for (const f of n.long) if (!a.long.includes(f)) problems.push(`${file}: ${f} is over ${maxLines} lines`);
    for (const f of a.long) if (!n.long.includes(f)) problems.push(`${file}: ${f} is no longer over ${maxLines} lines (run --write)`);
  }
  return problems;
}

const now = measure();
const total = (files, k) => Object.values(files).reduce((s, e) => s + (k === "long" ? e.long.length : e.shadowed), 0);

if (process.argv.includes("--write")) {
  const lines = Object.entries(now).map(([file, e]) => `  ${JSON.stringify(file)}: ${JSON.stringify(e)}`);
  writeFileSync(baselineFile, `{\n${lines.join(",\n")}\n}\n`);
  console.log(`ratchet.json: ${total(now, "shadowed")} shadowed names, ${total(now, "long")} functions over ${maxLines} lines`);
} else {
  const allowed = JSON.parse(readFileSync(baselineFile, "utf8"));
  const problems = check(now, allowed);
  for (const p of problems) console.error(p);
  console.log(`${total(now, "shadowed")} shadowed names, ${total(now, "long")} functions over ${maxLines} lines`);
  process.exit(problems.length ? 1 : 0);
}
