// Settings: this profile's preferences and GitHub login, and the Claude token every profile of
// this Claudia runs on (anyone signed in can replace it; nobody can read it back).
import React, { useEffect, useState } from "react";

export type Me = { name: string | null; email: string | null; profile: string | null; claudeToken: { by: string; at: number } | null };

type GithubUser = { login: string; name: string | null } | null;
type Prefs = { chatColors: "each" | "none" };

const when = (at: number) => new Date(at).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });

function TokenForm({ placeholder, save }: { placeholder: string; save: (token: string) => Promise<string | null> }) {
  const [value, setValue] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!value.trim()) return;
    setBusy(true);
    const err = await save(value);
    setBusy(false);
    setError(err);
    if (!err) setValue("");
  };
  return (
    <form className="token-form" onSubmit={submit}>
      <input type="password" autoComplete="off" spellCheck={false} placeholder={placeholder} value={value} onChange={(e) => setValue(e.target.value)} />
      <button disabled={busy || !value.trim()}>{busy ? "Saving…" : "Save"}</button>
      {error && <p className="token-error">{error}</p>}
    </form>
  );
}

async function put(url: string, token: string): Promise<{ ok: any } | { error: string }> {
  const r = await fetch(url, { method: "PUT", body: JSON.stringify({ token }) });
  const j = await r.json().catch(() => ({ error: `HTTP ${r.status}` }));
  return r.ok ? { ok: j } : { error: String(j.error ?? `HTTP ${r.status}`) };
}

export function Settings({ me, onMe, close }: { me: Me; onMe: (m: Me) => void; close: () => void }) {
  const [github, setGithub] = useState<GithubUser | undefined>(undefined);
  const [prefs, setPrefs] = useState<Prefs | null>(null);
  useEffect(() => {
    fetch("/api/prefs").then((r) => r.json()).then(setPrefs, () => {});
    fetch("/api/github").then((r) => r.json()).then((j) => setGithub(j.user ?? null), () => setGithub(null));
  }, []);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && close();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [close]);
  const savePrefs = async (next: Partial<Prefs>) => {
    const r = await fetch("/api/prefs", { method: "PATCH", body: JSON.stringify(next) });
    if (r.ok) setPrefs(await r.json());
  };

  return (
    <div className="settings-backdrop" onClick={close}>
      <div className="settings" role="dialog" aria-label="Settings" onClick={(e) => e.stopPropagation()}>
        <header>
          <h2>Settings</h2>
          <button className="icon" onClick={close} title="Close (Esc)">×</button>
        </header>
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
            {me.claudeToken ? <>Set by {me.claudeToken.by}, {when(me.claudeToken.at)}.</> : <>Not set yet: no chat can run.</>}{" "}
            A new one applies to every chat on its next message. Make one with <code>claude setup-token</code>.
          </p>
          <TokenForm
            placeholder={me.claudeToken ? "Replace with a new token (sk-ant-…)" : "Claude token (sk-ant-…)"}
            save={async (token) => {
              const r = await put("/api/claude-token", token);
              if ("error" in r) return r.error;
              onMe({ ...me, claudeToken: r.ok.claudeToken });
              return null;
            }}
          />
        </section>

        <section>
          <h3>GitHub <span className="muted small">yours only</span></h3>
          <p className="small">
            {github === undefined ? "Checking…" : github ? <>Pushes and pull requests come from <b>@{github.login}</b>.</> : <>Not connected: Claude can't push or open pull requests for you.</>}{" "}
            Use a fine-grained token for the Iron Rainbow repo with Contents and Pull requests set to read and write.
          </p>
          <TokenForm
            placeholder={github ? "Replace with a new token" : "GitHub token (github_pat_…)"}
            save={async (token) => {
              const r = await put("/api/github", token);
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

// The instance's name (CLAUDIA_NAME) as a subtitle under the logo, so two Claudias don't look alike.
export const brandName = (name: string | null | undefined) => (name ? `Claudia ${name}` : "Claudia");

export function Brand({ name }: { name: string | null | undefined }) {
  return (
    <div className="brand">
      <span className="logo" />
      <span className="brand-text">Claudia{name && <small className="brand-sub">{name}</small>}</span>
    </div>
  );
}

// Signed in, but this Claudia has no profile for the email: nothing to open.
export function NoProfile({ email, name }: { email: string | null; name: string | null }) {
  return (
    <div className="no-profile">
      <Brand name={name} />
      <p>There is no profile here for {email ?? "this sign-in"} yet. Ask Paul to add one.</p>
    </div>
  );
}
