// The Changes panel: files Claude changed in a turn, the diff on click.
import React, { useEffect, useMemo, useState } from "react";
import { structuredPatch, diffWordsWithSpace } from "diff";
import type { ChangeMeta } from "./types";

const HOME = /^\/home\/[^/]+/;
export const tilde = (p: string) => p.replace(HOME, "~");

// Long paths keep their end, where the file and project names are.
export function clip(p: string, max = 44) {
  if (p.length <= max) return p;
  const parts = p.split("/");
  let out = parts.pop()!;
  while (parts.length && out.length + parts[parts.length - 1].length + 1 <= max - 2) out = `${parts.pop()}/${out}`;
  return `…/${out}`;
}

function shortPath(path: string, folder: string | null) {
  if (folder && path.startsWith(folder + "/")) return path.slice(folder.length + 1);
  return tilde(path);
}

const store = {
  get: (k: string) => { try { return localStorage.getItem(k); } catch { return null; } },
  set: (k: string, v: string) => { try { localStorage.setItem(k, v); } catch {} },
};

type Line = { kind: " " | "-" | "+"; text: string; old?: number; new?: number };
type Hunk = { lines: Line[]; header: string };

function hunksOf(before: string, after: string, whole: boolean): Hunk[] {
  return toHunks(structuredPatch("a", "b", before, after, "", "", { context: whole ? Number.MAX_SAFE_INTEGER : 3 }).hunks);
}

// Unified-diff hunks (lines starting with " ", "-" or "+") into numbered lines.
function toHunks(raw: { oldStart: number; oldLines: number; newStart: number; newLines: number; lines: string[] }[]): Hunk[] {
  return raw.map((h) => {
    let o = h.oldStart, n = h.newStart;
    const lines: Line[] = [];
    for (const raw of h.lines) {
      if (raw.startsWith("\\")) continue; // "\ No newline at end of file"
      const kind = raw[0] as Line["kind"];
      const text = raw.slice(1);
      if (kind === " ") lines.push({ kind, text, old: o++, new: n++ });
      else if (kind === "-") lines.push({ kind, text, old: o++ });
      else lines.push({ kind, text, new: n++ });
    }
    return { lines, header: `@@ ${h.oldStart},${h.oldLines} → ${h.newStart},${h.newLines}` };
  });
}

// Inside a changed line, mark the words that differ from its counterpart.
function Words({ text, other, side }: { text: string; other?: string; side: "-" | "+" }) {
  if (other === undefined) return <>{text}</>;
  const parts = side === "-" ? diffWordsWithSpace(text, other) : diffWordsWithSpace(other, text);
  return (
    <>
      {parts.map((p, i) => {
        if (side === "-" && p.added) return null;
        if (side === "+" && p.removed) return null;
        const hot = side === "-" ? p.removed : p.added;
        return hot ? <mark key={i}>{p.value}</mark> : <React.Fragment key={i}>{p.value}</React.Fragment>;
      })}
    </>
  );
}

// Pair each run of removed lines with the added run right after it, for word marks and side by side.
function pairs(lines: Line[]) {
  const out: ({ kind: " "; line: Line } | { kind: "change"; del: Line[]; add: Line[] })[] = [];
  let i = 0;
  while (i < lines.length) {
    if (lines[i].kind === " ") { out.push({ kind: " ", line: lines[i++] }); continue; }
    const del: Line[] = [], add: Line[] = [];
    while (i < lines.length && lines[i].kind === "-") del.push(lines[i++]);
    while (i < lines.length && lines[i].kind === "+") add.push(lines[i++]);
    out.push({ kind: "change", del, add });
  }
  return out;
}

function Unified({ hunks }: { hunks: Hunk[] }) {
  return (
    <table className="diff unified">
      <colgroup><col className="ln-col" /><col className="ln-col" /><col /></colgroup>
      <tbody>
        {hunks.map((h, hi) => (
          <React.Fragment key={hi}>
            <tr className="hunk"><td colSpan={3}>{h.header}</td></tr>
            {pairs(h.lines).map((p, pi) =>
              p.kind === " " ? (
                <tr key={pi} className="ctx"><td className="ln">{p.line.old}</td><td className="ln">{p.line.new}</td><td className="code">{p.line.text}</td></tr>
              ) : (
                <React.Fragment key={pi}>
                  {p.del.map((l, k) => (
                    <tr key={`d${k}`} className="del"><td className="ln">{l.old}</td><td className="ln" /><td className="code"><Words text={l.text} other={p.add[k]?.text} side="-" /></td></tr>
                  ))}
                  {p.add.map((l, k) => (
                    <tr key={`a${k}`} className="add"><td className="ln" /><td className="ln">{l.new}</td><td className="code"><Words text={l.text} other={p.del[k]?.text} side="+" /></td></tr>
                  ))}
                </React.Fragment>
              ),
            )}
          </React.Fragment>
        ))}
      </tbody>
    </table>
  );
}

function Split({ hunks }: { hunks: Hunk[] }) {
  return (
    <table className="diff split">
      <colgroup><col className="ln-col" /><col /><col className="ln-col" /><col /></colgroup>
      <tbody>
        {hunks.map((h, hi) => (
          <React.Fragment key={hi}>
            <tr className="hunk"><td colSpan={4}>{h.header}</td></tr>
            {pairs(h.lines).flatMap((p, pi) => {
              if (p.kind === " ")
                return [<tr key={pi} className="ctx"><td className="ln">{p.line.old}</td><td className="code">{p.line.text}</td><td className="ln">{p.line.new}</td><td className="code">{p.line.text}</td></tr>];
              const rows = Math.max(p.del.length, p.add.length);
              return Array.from({ length: rows }, (_, k) => {
                const d = p.del[k], a = p.add[k];
                return (
                  <tr key={`${pi}-${k}`}>
                    <td className={`ln ${d ? "del" : "empty"}`}>{d?.old}</td>
                    <td className={`code ${d ? "del" : "empty"}`}>{d && <Words text={d.text} other={a?.text} side="-" />}</td>
                    <td className={`ln ${a ? "add" : "empty"}`}>{a?.new}</td>
                    <td className={`code ${a ? "add" : "empty"}`}>{a && <Words text={a.text} other={d?.text} side="+" />}</td>
                  </tr>
                );
              });
            })}
          </React.Fragment>
        ))}
      </tbody>
    </table>
  );
}

// A patch as Claude Code saved it with an edit (the timeline's steps).
export function PatchDiff({ hunks }: { hunks: { oldStart: number; oldLines: number; newStart: number; newLines: number; lines: string[] }[] }) {
  const h = useMemo(() => toHunks(hunks), [hunks]);
  return <Unified hunks={h} />;
}

function DiffView({ chat, change, split }: { chat: string; change: ChangeMeta; split: boolean }) {
  const [data, setData] = useState<{ before: string | null; after: string | null } | null>(null);
  const [whole, setWhole] = useState(false);
  useEffect(() => {
    let dead = false;
    fetch(`/api/chats/${chat}/changes/${change.id}`).then((r) => r.json()).then((j) => !dead && setData(j));
    return () => { dead = true; };
  }, [chat, change.id]);
  const hunks = useMemo(() => (data ? hunksOf(data.before ?? "", data.after ?? "", whole) : []), [data, whole]);
  if (change.skipped) return <p className="muted small">Not shown: {change.skipped}.</p>;
  if (!data) return <p className="muted small">Loading…</p>;
  return (
    <div className="diff-wrap">
      {split ? <Split hunks={hunks} /> : <Unified hunks={hunks} />}
      <button className="link" onClick={() => setWhole(!whole)}>{whole ? "Show only the changes" : "Show the whole file"}</button>
    </div>
  );
}

// alwaysOpen: shown in the activity pill's dropdown, where opening it is the click on the pill.
export function ChangesPanel({ chat, changes, folder, label, alwaysOpen }: { chat: string; changes: ChangeMeta[]; folder: string | null; label: string; alwaysOpen?: boolean }) {
  // Folded until opened; a new chat or a new turn's changes fold it again.
  const [folded, setOpen] = useState(false);
  const open = alwaysOpen || folded;
  const newest = changes.at(-1)?.id;
  useEffect(() => setOpen(false), [chat, newest]);
  const [split, setSplit] = useState(() => store.get("diff-split") === "1");
  const [shown, setShown] = useState<number | null>(null);
  const added = changes.reduce((n, c) => n + c.added, 0);
  const removed = changes.reduce((n, c) => n + c.removed, 0);
  return (
    <section className="panel changes">
      <div className="changes-head">
        <button className="tasks-head" onClick={() => setOpen(!open)} disabled={alwaysOpen}>
          {!alwaysOpen && <span className={`chev ${open ? "open" : ""}`}>›</span>}
          <b>Changes</b>
          <span className="muted small">{label} · {changes.length} {changes.length === 1 ? "file" : "files"} · <span className="plus">+{added}</span> <span className="minus">−{removed}</span></span>
        </button>
        {open && (
          <button className="link toggle-split" onClick={() => { setSplit(!split); store.set("diff-split", split ? "0" : "1"); }}>
            {split ? "One column" : "Side by side"}
          </button>
        )}
      </div>
      {open && (
        <ul>
          {changes.map((c) => (
            <li key={c.id} className={`change ${shown === c.id ? "open" : ""}`}>
              <button className="change-row" onClick={() => setShown(shown === c.id ? null : c.id)}>
                <span className={`chev ${shown === c.id ? "open" : ""}`}>›</span>
                <span className="change-path" title={c.path}>{clip(shortPath(c.path, folder))}</span>
                {c.status !== "modified" && <span className={`chip ${c.status === "added" ? "green" : "red"}`}>{c.status === "added" ? "new" : "deleted"}</span>}
                <span className="counts"><span className="plus">+{c.added}</span> <span className="minus">−{c.removed}</span></span>
              </button>
              {shown === c.id && <DiffView chat={chat} change={c} split={split} />}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
