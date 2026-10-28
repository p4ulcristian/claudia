// Asks a profile without a GitHub login to connect one, once per browser until it is set ("Later"
// hides it here). Without it, Claude can't push or open pull requests for that person.
import React, { useEffect, useState } from "react";
import { useApi } from "./api";

// A classic token: fine-grained ones can't reach a repo owned by another person's account, which is
// where a collaborator's work goes. gh needs read:org; workflow lets pushes touch CI files.
export const GITHUB_TOKEN_URL = "https://github.com/settings/tokens/new?scopes=repo,read:org,workflow&description=Claudia";

// Asks the open chat's server (in the hub each server keeps its own GitHub login per profile).
export function GithubPrompt({ profile }: { profile: string }) {
  const api = useApi();
  const key = api.server ? `claudia.github-later.${api.server}.${profile}` : `claudia.github-later.${profile}`;
  const [show, setShow] = useState(false);
  const [value, setValue] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);

  useEffect(() => {
    if (localStorage.getItem(key)) return;
    setShow(false);
    api.fetch("/api/github").then((r) => r.json()).then((j) => !j.user && setShow(true), () => {});
  }, [api, key]);

  if (!show) return null;
  const later = () => { localStorage.setItem(key, "1"); setShow(false); };
  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    const r = await api.fetch("/api/github", { method: "PUT", body: JSON.stringify({ token: value }) });
    const j = await r.json().catch(() => ({ error: `HTTP ${r.status}` }));
    setBusy(false);
    if (!r.ok) return setError(String(j.error ?? `HTTP ${r.status}`));
    setDone(j.user?.login ?? "");
  };

  return (
    <div className="settings-backdrop">
      <div className="settings github-prompt" role="dialog" aria-label="Connect GitHub">
        <header><h2>Connect GitHub</h2></header>
        {done !== null ? (
          <>
            <p>Done: Claude now pushes and opens pull requests as <b>@{done}</b>.</p>
            <div className="prompt-actions"><button className="primary" onClick={() => setShow(false)}>Start</button></div>
          </>
        ) : (
          <>
            <p>So Claude can push your branches and open pull requests as you, it needs a GitHub token of yours. It stays in your profile only.</p>
            <ol className="prompt-steps">
              <li><a href={GITHUB_TOKEN_URL} target="_blank" rel="noreferrer">Create a token on GitHub</a>: the boxes are already ticked; pick an expiry, then <b>Generate token</b>.</li>
              <li>Copy it and paste it here.</li>
            </ol>
            <form className="token-form" onSubmit={save}>
              <input type="password" autoComplete="off" spellCheck={false} placeholder="ghp_…" value={value} onChange={(e) => setValue(e.target.value)} autoFocus />
              <button disabled={busy || !value.trim()}>{busy ? "Saving…" : "Save"}</button>
              {error && <p className="token-error">{error}</p>}
            </form>
            <div className="prompt-actions"><button onClick={later}>Later</button><span className="muted small">You can add it any time in Settings.</span></div>
          </>
        )}
      </div>
    </div>
  );
}
