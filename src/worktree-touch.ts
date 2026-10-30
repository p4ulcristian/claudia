// Tell Eye on Rainbow when a chat works inside one of its worktrees: an edit, a git command or any
// other command. Only on the box that has the worktrees (their folder and the profile's EOR token
// exist); every other server sends nothing. The link is a log, not an owner: a chat may hop between
// worktrees and a worktree has many chats.
import { existsSync, readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join, resolve, sep } from "node:path";
import { editedPath } from "./changes";

const DIR = process.env.CLAUDIA_WORKTREES_DIR ?? "/srv/ironrainbow/worktrees";
const EOR = (process.env.CLAUDIA_EOR_URL ?? "http://127.0.0.1:4010").replace(/\/$/, "");
const TOKEN_FILE = process.env.CLAUDIA_EOR_TOKEN_FILE ?? join(homedir(), ".config", "eor", "token");
const SAME_AGAIN_MS = 30_000;

export type TouchKind = "edit" | "git" | "command";
export type Touch = { kind: TouchKind; path: string };

const inside = (p: string) => resolve(p) === DIR || resolve(p).startsWith(DIR + sep);

// What a finished tool call touched, or null when it was not in a worktree.
export function touchOf(tool: string, input: any, cwd: string | undefined): Touch | null {
  if (tool === "Bash") {
    const command = String(input?.command ?? "");
    const kind: TouchKind = /(^|[;&|(\s])git\s/.test(command) ? "git" : "command";
    if (cwd && inside(cwd)) return { kind, path: cwd };
    const named = command.match(new RegExp(`${DIR.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}/[^\\s'"]+`));
    return named ? { kind, path: named[0] } : null;
  }
  const file = editedPath(input);
  return file && inside(file) ? { kind: "edit", path: file } : null;
}

const last = new Map<string, number>();

export function reportTouch(chat: { id: string; title: string; url: string }, tool: string, input: any, cwd: string | undefined) {
  if (!existsSync(DIR) || !existsSync(TOKEN_FILE)) return;
  const t = touchOf(tool, input, cwd);
  if (!t) return;
  const key = `${chat.id} ${t.path.slice(DIR.length).split(sep)[1]} ${t.kind}`;
  const now = Date.now();
  if (now - (last.get(key) ?? 0) < SAME_AGAIN_MS) return;
  last.set(key, now);
  fetch(`${EOR}/api/worktrees/touches`, {
    method: "POST",
    headers: { "content-type": "application/json", "x-eor-token": readFileSync(TOKEN_FILE, "utf8").trim() },
    body: JSON.stringify({ chat: chat.id, title: chat.title, url: chat.url, ...t }),
    signal: AbortSignal.timeout(5000),
  }).catch(() => {});
}
