// The profile's own GitHub login, so its pushes and pull requests come from its
// person. The token goes to gh (gh keeps it in this Linux user's ~/.config/gh), git uses gh as
// its credential helper, and commits carry the GitHub account's name and no-reply address.

async function run(cmd: string[], stdin?: string) {
  const proc = Bun.spawn(cmd, { stdin: stdin === undefined ? "ignore" : new Blob([stdin]), stdout: "pipe", stderr: "pipe" });
  const [out, err, code] = await Promise.all([new Response(proc.stdout).text(), new Response(proc.stderr).text(), proc.exited]);
  return { out: out.trim(), err: err.trim(), code };
}

export type GithubUser = { login: string; name: string | null };

let known: GithubUser | null | undefined;

export async function githubUser(): Promise<GithubUser | null> {
  if (known !== undefined) return known;
  const r = await run(["gh", "api", "user", "--hostname", "github.com"]);
  if (r.code !== 0) return (known = null);
  const u = JSON.parse(r.out) as { login: string; name: string | null };
  return (known = { login: u.login, name: u.name });
}

export async function setGithubToken(raw: unknown): Promise<GithubUser> {
  const token = String(raw ?? "").replace(/\s+/g, "");
  if (!/^(github_pat_|gh[pousr]_)[A-Za-z0-9_]{20,}$/.test(token)) throw new Error("That doesn't look like a GitHub token (github_pat_… or ghp_…).");
  const login = await run(["gh", "auth", "login", "--hostname", "github.com", "--git-protocol", "https", "--with-token"], token);
  if (login.code !== 0) throw new Error(`GitHub refused the token: ${login.err || login.out}`);
  await run(["gh", "auth", "setup-git", "--hostname", "github.com"]);
  known = undefined;
  const r = await run(["gh", "api", "user", "--hostname", "github.com"]);
  if (r.code !== 0) throw new Error(`GitHub: ${r.err}`);
  const u = JSON.parse(r.out) as { id: number; login: string; name: string | null };
  await run(["git", "config", "--global", "user.name", u.name || u.login]);
  await run(["git", "config", "--global", "user.email", `${u.id}+${u.login}@users.noreply.github.com`]);
  return (known = { login: u.login, name: u.name });
}
