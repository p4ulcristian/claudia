// Page side: which profile a request belongs to, where that profile's runner listens, and the
// Claude token pasted in Settings.
//
// Two ways in. The gate's listener (GATE_HOST) believes the login header, because only the gate
// can reach it (a firewall rule) and the gate's login sets it. The local listener (127.0.0.1, the
// desktop window) ignores any header and is the LOCAL_PROFILE, or nobody.
import { readFileSync, statSync } from "node:fs";
import { mkdir, rename, writeFile } from "node:fs/promises";
import { dirname } from "node:path";
import { TOKEN_FILE, TOKEN_NOTE } from "./mode";
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

export type TokenNote = { by: string; at: number };

export function tokenNote(): TokenNote | null {
  try {
    statSync(TOKEN_FILE);
    return JSON.parse(readFileSync(TOKEN_NOTE, "utf8"));
  } catch {
    return null;
  }
}

// Long-term tokens from `claude setup-token`. Pasting a wrapped line can add spaces: drop them.
export function cleanToken(raw: unknown) {
  const token = String(raw ?? "").replace(/\s+/g, "");
  return /^sk-ant-[A-Za-z0-9_-]{20,}$/.test(token) ? token : null;
}

// Replaced in one step (write beside, then rename), so a runner never reads half a token. On the
// box the folder is setgid to the runners' group, so the new file is theirs to read (0640).
export async function setToken(token: string, by: string) {
  await mkdir(dirname(TOKEN_FILE), { recursive: true });
  await writeFile(`${TOKEN_FILE}.new`, token + "\n", { mode: 0o640 });
  await rename(`${TOKEN_FILE}.new`, TOKEN_FILE);
  await writeFile(TOKEN_NOTE, JSON.stringify({ by, at: Date.now() } satisfies TokenNote), { mode: 0o644 });
}
