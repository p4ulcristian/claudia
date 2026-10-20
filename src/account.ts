// Which Claude subscription Claudia runs on: the long-term token (`claude setup-token`) picked in
// Settings, kept in a file (src/mode.ts TOKEN_FILE) and read again on every turn. Every profile of
// an instance runs on it.
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { TOKEN_FILE, TOKEN_NOTE } from "./mode";

// label = the saved token's name from the note ("Paul"), or null.
export type Account = { name: string; label: string | null; env: Record<string, string> };

// The service's environment with the token.
function envWith(token: string) {
  const env: Record<string, string> = {};
  for (const [k, v] of Object.entries(process.env)) if (v !== undefined) env[k] = v;
  env.CLAUDE_CODE_OAUTH_TOKEN = token;
  return env;
}

// The name carries a hash of the token, so replacing it restarts each chat's Claude Code on its next
// turn, and the usage meters keep each token's limits apart.
export async function account(): Promise<Account> {
  const token = (await readFile(TOKEN_FILE, "utf8").catch(() => "")).trim();
  if (!token) throw new Error("No Claude token yet: add one in Settings (bottom left).");
  const label = await readFile(TOKEN_NOTE, "utf8").then((t) => JSON.parse(t).name ?? null, () => null);
  return { name: `token-${createHash("sha256").update(token).digest("hex").slice(0, 8)}`, label, env: envWith(token) };
}
