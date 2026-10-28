// Settings: this profile's preferences and GitHub login, and the Claude tokens every profile of
// this Claudia can run on (saved by name; anyone signed in can add, replace or switch; nobody can
// read one back).
import React, { useEffect, useState } from "react";
import { Logo } from "./logo";
import { GITHUB_TOKEN_URL } from "./github-prompt";
import type { Api } from "./api";

export type TokenNote = { by: string; at: number; name?: string | null };
export type SavedToken = { name: string; by: string; at: number };
// profiles = every profile here; viewing = whose chats the page shows; readOnly = someone else's;
// api = the API it speaks (src/api-version.ts; missing on servers from before the hub).
export type Me = { api?: number; name: string | null; version: string; commit: string | null; email: string | null; profile: string | null; profiles: string[]; viewing: string | null; readOnly: boolean; claudeToken: TokenNote | null; claudeTokens: SavedToken[] };

type GithubUser = { login: string; name: string | null } | null;
type Prefs = { chatColors: "each" | "none" };

const when = (at: number) => new Date(at).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });

// named = also asks for a name (the Claude tokens are saved by name).
function TokenForm({ placeholder, named, save }: { placeholder: string; named?: boolean; save: (token: string, name: string) => Promise<string | null> }) {
  const [value, setValue] = useState("");
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const ready = value.trim() && (!named || name.trim());
  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!ready) return;
    setBusy(true);
    const err = await save(value, name.trim());
    setBusy(false);
    setError(err);
    if (!err) {
      setValue("");
      setName("");
    }
  };
  return (
    <form className={`token-form ${named ? "named" : ""}`} onSubmit={submit}>
      {named && <input className="token-name" autoComplete="off" spellCheck={false} placeholder="Whose (Paul)" value={name} onChange={(e) => setName(e.target.value)} />}
      <input type="password" autoComplete="off" spellCheck={false} placeholder={placeholder} value={value} onChange={(e) => setValue(e.target.value)} />
      <button disabled={busy || !ready}>{busy ? "Saving…" : "Save"}</button>
      {error && <p className="token-error">{error}</p>}
    </form>
  );
}

async function call(api: Api, method: string, url: string, body: object): Promise<{ ok: any } | { error: string }> {
  const r = await api.fetch(url, { method, body: JSON.stringify(body) });
  const j = await r.json().catch(() => ({ error: `HTTP ${r.status}` }));
  return r.ok ? { ok: j } : { error: String(j.error ?? `HTTP ${r.status}`) };
}
const put = (api: Api, url: string, body: object) => call(api, "PUT", url, body);

// Switch every chat of this Claudia (api's server) to a saved Claude token; returns the new token fields of Me.
export async function useClaudeToken(api: Api, name: string): Promise<Pick<Me, "claudeToken" | "claudeTokens"> | null> {
  const r = await call(api, "POST", "/api/claude-token/use", { name });
  return "error" in r ? null : { claudeToken: r.ok.claudeToken, claudeTokens: r.ok.claudeTokens };
}

// The name of the token in use, if it is a saved one.
export const tokenInUse = (me: Me) => (me.claudeToken?.name ? me.claudeTokens.find((t) => t.name === me.claudeToken!.name)?.name ?? null : null);

// api: the server whose settings these are; top: in the hub, the switch between servers above them.
export function Settings({ api, me, onMe, close, top }: { api: Api; me: Me; onMe: (m: Me) => void; close: () => void; top?: React.ReactNode }) {
  const [github, setGithub] = useState<GithubUser | undefined>(undefined);
  const [prefs, setPrefs] = useState<Prefs | null>(null);
  useEffect(() => {
    setPrefs(null);
    setGithub(undefined);
    api.fetch("/api/prefs").then((r) => r.json()).then(setPrefs, () => {});
    api.fetch("/api/github").then((r) => r.json()).then((j) => setGithub(j.user ?? null), () => setGithub(null));
  }, [api]);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && close();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [close]);
  const inUse = tokenInUse(me);
  const savePrefs = async (next: Partial<Prefs>) => {
    const r = await api.fetch("/api/prefs", { method: "PATCH", body: JSON.stringify(next) });
    if (r.ok) setPrefs(await r.json());
  };

  return (
    <div className="settings-backdrop" onClick={close}>
      <div className="settings" role="dialog" aria-label="Settings" onClick={(e) => e.stopPropagation()}>
        <header>
          <h2>Settings</h2>
          <button className="icon" onClick={close} title="Close (Esc)">×</button>
        </header>
        {top}
        <p className="muted small">{me.email ? <>Signed in as {me.email} · </> : null}profile <b>{me.profile}</b></p>

        <section>
          <h3>Chat colours</h3>
          <div className="choice">
            <label>
              <input type="radio" name="chatColors" checked={prefs?.chatColors === "each"} onChange={() => savePrefs({ chatColors: "each" })} />
              A colour of its own for every new chat
            </label>
            <label>
              <input type="radio" name="chatColors" checked={prefs?.chatColors === "none"} onChange={() => savePrefs({ chatColors: "none" })} />
              Default look; colour a chat by hand from its header dot
            </label>
          </div>
        </section>

        <section>
          <h3>Claude subscription <span className="muted small">shared by every profile here</span></h3>
          <p className="small">
            {!me.claudeToken ? <>None in use yet: no chat can run.</> : !inUse ? <>In use: a token without a name, set by {me.claudeToken.by}, {when(me.claudeToken.at)}. Save it again under a name to keep it in the list.</> : <>Switched to <b>{inUse}</b> by {me.claudeToken.by}, {when(me.claudeToken.at)}.</>}{" "}
            A switch applies to every chat on its next message.
          </p>
          {me.claudeTokens.length > 0 && (
            <div className="choice">
              {me.claudeTokens.map((t) => (
                <label key={t.name}>
                  <input type="radio" name="claudeToken" checked={t.name === inUse} onChange={async () => {
                    const next = await useClaudeToken(api, t.name);
                    if (next) onMe({ ...me, ...next });
                  }} />
                  {t.name} <span className="muted small">saved by {t.by}, {when(t.at)}</span>
                </label>
              ))}
            </div>
          )}
          <p className="small">Add one, or replace one by its name: make it with <code>claude setup-token</code>. Saving switches to it.</p>
          <TokenForm
            named
            placeholder="Claude token (sk-ant-…)"
            save={async (token, name) => {
              const r = await put(api, "/api/claude-token", { name, token });
              if ("error" in r) return r.error;
              onMe({ ...me, claudeToken: r.ok.claudeToken, claudeTokens: r.ok.claudeTokens });
              return null;
            }}
          />
        </section>

        <section>
          <h3>GitHub <span className="muted small">yours only</span></h3>
          <p className="small">
            {github === undefined ? "Checking…" : github ? <>Pushes and pull requests come from <b>@{github.login}</b>.</> : <>Not connected: Claude can't push or open pull requests for you.</>}{" "}
            <a href={GITHUB_TOKEN_URL} target="_blank" rel="noreferrer">Create a token on GitHub</a> (the boxes come ticked) and paste it here.
          </p>
          <TokenForm
            placeholder={github ? "Replace with a new token" : "GitHub token (ghp_…)"}
            save={async (token) => {
              const r = await put(api, "/api/github", { token });
              if ("error" in r) return r.error;
              setGithub(r.ok.user);
              return null;
            }}
          />
        </section>
      </div>
    </div>
  );
}

// The logo: the wordmark, then a chip with this instance's name (CLAUDIA_NAME), whose Claude token
// is in use, and the commit (the version when there is none), so two Claudias tell themselves apart
// and show they run the same code.
export const brandName = (name: string | null | undefined) => (name ? `Claudia ${name}` : "Claudia");

// token = whose Claude subscription the chats run on right now (the saved token's name).
// hub = the page is served by the hub: its version and commit in the chip (each server's are in Settings).
export function Brand({ me, token, hub }: { me: Me | null; token?: string | null; hub?: { version: string; commit: string | null } }) {
  if (hub)
    return (
      <div className="brand">
        <Logo />
        <span className="wordmark">Claudia</span>
        <span className="brand-break" />
        <span className="instance" title={`Claudia hub ${hub.version}${hub.commit ? ` (${hub.commit})` : ""}${token ? `; the open chat runs on ${token}'s Claude subscription` : ""}`}>
          {token && <span className="instance-token">{token}</span>}
          <span className="instance-version">{hub.commit ?? `v${hub.version}`}</span>
        </span>
      </div>
    );
  return (
    <div className="brand">
      <Logo />
      <span className="wordmark">Claudia</span>
      {me && <span className="brand-break" />}
      {me && (
        <span className="instance" title={`Claudia ${me.version}${me.commit ? ` (${me.commit})` : ""}${me.name ? ` on ${me.name}` : ""}${token ? `, on ${token}'s Claude subscription` : ""}`}>
          {me.name && <span className="instance-name">{me.name}</span>}
          {token && <span className="instance-token">{token}</span>}
          <span className="instance-version">{me.commit ?? `v${me.version}`}</span>
        </span>
      )}
    </div>
  );
}

// Signed in, but this Claudia has no profile for the email: nothing to open.
export function NoProfile({ me }: { me: Me }) {
  return (
    <div className="no-profile">
      <Brand me={me} />
      <p>There is no profile here for {me.email ?? "this sign-in"} yet. Ask Paul to add one.</p>
    </div>
  );
}

// Whose chats the sidebar lists. Everyone can read every profile's chats; only your own can be
// written in (the page server enforces it). The choice is a cookie, so every request follows it.
export function ProfileTabs({ me }: { me: Me }) {
  if (me.profiles.length < 2) return null;
  const pick = (name: string) => {
    document.cookie = `claudia_view=${name}; path=/; max-age=31536000; samesite=lax`;
    location.href = "/";
  };
  const cap = (n: string) => n[0].toUpperCase() + n.slice(1);
  return (
    <div className="profile-tabs" role="tablist" aria-label="Whose chats">
      {me.profiles.map((p) => (
        <button key={p} role="tab" aria-selected={p === me.viewing} className={p === me.viewing ? "on" : ""} onClick={() => p !== me.viewing && pick(p)} title={p === me.profile ? "Your chats" : `${cap(p)}'s chats (read only)`}>
          {cap(p)}{p === me.profile && <span className="you">you</span>}
        </button>
      ))}
    </div>
  );
}
