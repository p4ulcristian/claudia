// Page side: which profile a request belongs to, where that profile's runner listens, and the
// Claude tokens saved in Settings (one of them in use).
//
// Two ways in. The gate's listener (GATE_HOST) believes the login header, because only the gate
// can reach it (a firewall rule) and the gate's login sets it. The local listener (127.0.0.1, the
// desktop window) ignores any header and is the LOCAL_PROFILE, or nobody.
import { readdirSync, readFileSync, statSync } from "node:fs";
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname } from "node:path";
import { TOKEN_FILE, TOKEN_NOTE, TOKENS_DIR } from "./mode";
import type { Target } from "./proxy";

// One profile per line: `<name> <email> [runner]` (# comments). runner = host:port, or a unix
// socket path; default <RUN_DIR>/claudia-<name>/runner.sock (the runner unit's RuntimeDirectory).
const PROFILES_FILE = process.env.CLAUDIA_PROFILES ?? `${process.env.HOME}/.config/claudia/profiles`;
const RUN_DIR = process.env.CLAUDIA_RUN_DIR ?? "/run";
export const LOCAL_PROFILE = process.env.CLAUDIA_LOCAL_PROFILE || null;

export type Profile = { name: string; email: string; runner: Target };

let cache: { mtime: number; list: Profile[] } | null = null;

function profiles(): Profile[] {
  const mtime = statSync(PROFILES_FILE).mtimeMs;
  if (cache?.mtime === mtime) return cache.list;
  const list: Profile[] = [];
  for (const line of readFileSync(PROFILES_FILE, "utf8").split("\n")) {
    const [name, email, runner] = line.replace(/#.*/, "").trim().split(/\s+/);
    if (!name || !email || !/^[a-z][a-z0-9-]{0,30}$/.test(name)) continue;
    const target: Target = !runner ? { unix: `${RUN_DIR}/claudia-${name}/runner.sock` } : runner.startsWith("/") ? { unix: runner } : { tcp: runner };
    list.push({ name, email: email.toLowerCase(), runner: target });
  }
  cache = { mtime, list };
  return list;
}

function find(match: (p: Profile) => boolean): Profile | null {
  try {
    return profiles().find(match) ?? null;
  } catch (err) {
    console.error(`profiles: ${(err as Error).message}`);
    return null;
  }
}

export function profileNames(): string[] {
  try {
    return profiles().map((p) => p.name);
  } catch {
    return [];
  }
}

// Whose chats the page shows: the claudia_view cookie names a profile; anyone signed in may read
// any profile's chats, only the profile's own person may write (readOnly otherwise).
export function viewOf(req: Request, own: Profile): { profile: Profile; readOnly: boolean } {
  const name = req.headers.get("cookie")?.match(/(?:^|;\s*)claudia_view=([a-z][a-z0-9-]{0,30})/)?.[1];
  const other = name && name !== own.name ? find((p) => p.name === name) : null;
  return other ? { profile: other, readOnly: true } : { profile: own, readOnly: false };
}

export type Who = { email: string | null; profile: Profile | null };

// gate = the request came in on the gate's listener.
export function who(req: Request, gate: boolean): Who {
  if (!gate) {
    const profile = LOCAL_PROFILE ? find((p) => p.name === LOCAL_PROFILE) : null;
    return { email: profile?.email ?? null, profile };
  }
  const email = req.headers.get("x-auth-request-email")?.trim().toLowerCase() || null;
  return { email, profile: email ? find((p) => p.email === email) : null };
}

// name = whose subscription it is (the saved token's name); null for a token pasted before tokens had names.
export type TokenNote = { by: string; at: number; name?: string | null };
// A token saved in Settings: its name, who saved it and when.
export type SavedToken = { name: string; by: string; at: number };

export function tokenNote(): TokenNote | null {
  try {
    statSync(TOKEN_FILE);
    return JSON.parse(readFileSync(TOKEN_NOTE, "utf8"));
  } catch {
    return null;
  }
}

export function savedTokens(): SavedToken[] {
  let files: string[];
  try {
    files = readdirSync(TOKENS_DIR);
  } catch {
    return [];
  }
  const list: SavedToken[] = [];
  for (const f of files.filter((f) => f.endsWith(".json")))
    try {
      list.push(JSON.parse(readFileSync(`${TOKENS_DIR}/${f}`, "utf8")));
    } catch {}
  return list.sort((a, b) => a.name.localeCompare(b.name));
}

// Long-term tokens from `claude setup-token`. Pasting a wrapped line can add spaces: drop them.
export function cleanToken(raw: unknown) {
  const token = String(raw ?? "").replace(/\s+/g, "");
  return /^sk-ant-[A-Za-z0-9_-]{20,}$/.test(token) ? token : null;
}

// A name for a saved token ("Paul", "Ananda"); its file is the lower-case name.
export function cleanName(raw: unknown) {
  const name = String(raw ?? "").trim();
  return /^[A-Za-z0-9][A-Za-z0-9 ._-]{0,23}$/.test(name) ? name : null;
}
const keyOf = (name: string) => name.toLowerCase().replace(/[^a-z0-9]+/g, "-");

// Saved under its name (same name = replaced), readable by the page only, and switched to.
export async function saveToken(name: string, token: string, by: string) {
  await mkdir(TOKENS_DIR, { recursive: true, mode: 0o700 });
  const file = `${TOKENS_DIR}/${keyOf(name)}`;
  await writeFile(`${file}.new`, token + "\n", { mode: 0o600 });
  await rename(`${file}.new`, file);
  await writeFile(`${file}.json`, JSON.stringify({ name, by, at: Date.now() } satisfies SavedToken), { mode: 0o600 });
  await useToken(name, by);
}

// Copies a saved token over the one in use, in one step (write beside, then rename), so a runner
// never reads half a token. The note goes first: the runners re-read it when the token file changes.
// On the box the folder is setgid to the runners' group, so the new file is theirs to read (0640).
export async function useToken(name: string, by: string) {
  const token = (await readFile(`${TOKENS_DIR}/${keyOf(name)}`, "utf8")).trim();
  const saved = savedTokens().find((t) => keyOf(t.name) === keyOf(name));
  await mkdir(dirname(TOKEN_FILE), { recursive: true });
  await writeFile(TOKEN_NOTE, JSON.stringify({ by, at: Date.now(), name: saved?.name ?? name } satisfies TokenNote), { mode: 0o644 });
  await writeFile(`${TOKEN_FILE}.new`, token + "\n", { mode: 0o640 });
  await rename(`${TOKEN_FILE}.new`, TOKEN_FILE);
}
