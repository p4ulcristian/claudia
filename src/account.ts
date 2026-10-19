// Which Claude subscription Claudia runs on: the one claude-account has selected
// (the Claude section of the desktop widget), the same as new terminal sessions.
// The token comes from the kernel keyring through claude-account, which reads it
// from 1Password when it isn't loaded yet; it is held in memory only.
export type Account = { name: string; env: Record<string, string> };

// The service's environment with the account's token, or without any token (= the `claude /login`).
function envWith(name: string, token: string) {
  const env: Record<string, string> = {};
  for (const [k, v] of Object.entries(process.env)) if (v !== undefined && k !== "CLAUDE_CODE_OAUTH_TOKEN") env[k] = v;
  env.CLAUDE_ACCOUNT = name;
  if (token) env.CLAUDE_CODE_OAUTH_TOKEN = token;
  return env;
}

// Without claude-account the chats run on the `claude /login`.
const LOGIN: Account = { name: "me", env: envWith("me", "") };

export async function account(): Promise<Account> {
  let proc;
  try {
    proc = Bun.spawn(["claude-account", "token"], { stdout: "pipe", stderr: "pipe" });
  } catch {
    return LOGIN;
  }
  const [out, err, code] = await Promise.all([new Response(proc.stdout).text(), new Response(proc.stderr).text(), proc.exited]);
  if (code !== 0) throw new Error(`claude-account: ${err.trim() || `exit ${code}`}`);
  const { name, token } = JSON.parse(out) as { name: string; token: string };
  return { name, env: envWith(name, token) };
}
