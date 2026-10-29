// The page served by the hub: one chat list across the servers a person may use. Here: what the hub
// says (/hub/me), each server's mark and state, the new-chat server choice, and the admins' Servers
// and Access pages (/hub/admin). Whose chats and which servers' show: the list's title menu (web/sidebar.tsx).
import React, { useEffect, useState } from "react";
import { Brand, type Me } from "./settings";
import { tipProps } from "./tip";

export type HubServer = { name: string; label: string };
export type HubMe = { email: string; admin: boolean; version: string; commit: string | null; servers: HubServer[] };

// A server as the page sees it: loading, up, off (its /api/me or socket fails), or no profile there
// for this person (403). update = it speaks another API than this page.
export type ServerState = "loading" | "up" | "off" | "noProfile";
export type ServerView = HubServer & { state: ServerState; update: boolean };

// Short marks for the servers, unique among them: the label's first letter, more when two share it.
export function serverMarks(servers: HubServer[]): Map<string, string> {
  const word = (s: HubServer) => (s.label || s.name).replace(/[^\p{L}\p{N}]/gu, "");
  const out = new Map<string, string>();
  for (const s of servers) {
    let n = 1;
    while (n < 3 && servers.some((o) => o !== s && word(o).slice(0, n).toLowerCase() === word(s).slice(0, n).toLowerCase())) n++;
    const w = word(s).slice(0, n) || s.name.slice(0, n);
    // Nothing to mark: the page's own server ("") when it isn't served by the hub.
    if (w) out.set(s.name, w[0].toUpperCase() + w.slice(1).toLowerCase());
  }
  return out;
}

// What to say about a server that isn't plainly up.
export const serverNote = (s: ServerView) =>
  s.state === "off" ? `${s.label} is off` : s.state === "noProfile" ? `needs a profile on ${s.label}` : s.update ? `${s.label} needs an update` : null;

// The small chalk chip a chat wears for its server.
export function ServerMark({ mark, label, off }: { mark: string; label: string; off?: boolean }) {
  return <span className={`server-mark ${off ? "off" : ""}`} title={off ? `${label} is off` : label} aria-label={label}>{mark}</span>;
}

// Where a new chat starts: by the composer of an empty chat. Only servers with a profile can be picked.
export function NewChatServer({ servers, marks, pick, onPick }: { servers: ServerView[]; marks: Map<string, string>; pick: string; onPick: (s: string) => void }) {
  return (
    <div className="new-on" role="radiogroup" aria-label="Start this chat on">
      <span className="muted">New chat on</span>
      {servers.map((s) => {
        const note = serverNote(s);
        return (
          <button
            key={s.name}
            role="radio"
            aria-checked={pick === s.name}
            className={`${pick === s.name ? "on" : ""} ${s.state}`}
            disabled={s.state === "noProfile"}
            onClick={() => onPick(s.name)}
            {...tipProps(note ?? `Start it on ${s.label}`)}
          >
            <span className="server-mark" aria-hidden="true">{marks.get(s.name)}</span>
            {s.label}
            {note && <span className={`sf-note ${s.state === "up" || s.state === "loading" ? "warn" : ""}`}>{s.state === "off" ? "off" : s.state === "noProfile" ? "needs a profile" : "needs an update"}</span>}
          </button>
        );
      })}
    </div>
  );
}

// Settings are each server's own (Claude token, GitHub, chat colours): which server's, its name,
// version and commit, and whether it needs an update.
export function SettingsServers({ servers, pick, onPick }: { servers: (ServerView & { me: Me | null })[]; pick: string; onPick: (s: string) => void }) {
  const cur = servers.find((s) => s.name === pick);
  return (
    <div className="settings-servers">
      {servers.length > 1 && (
        <div className="profile-tabs" role="tablist" aria-label="Which server's settings">
          {servers.map((s) => (
            <button key={s.name} role="tab" aria-selected={s.name === pick} className={s.name === pick ? "on" : ""} disabled={s.state !== "up"} onClick={() => onPick(s.name)} title={serverNote(s) ?? `${s.label}'s settings`}>
              {s.label}
              {s.state !== "up" && <span className="sf-note">{s.state === "off" ? "off" : s.state === "noProfile" ? "needs a profile" : "…"}</span>}
              {s.state === "up" && s.update && <span className="sf-note warn">needs an update</span>}
            </button>
          ))}
        </div>
      )}
      {cur?.me && (
        <p className="muted small">
          <b>{cur.label}</b>{cur.me.name ? ` (${cur.me.name})` : ""} · Claudia {cur.me.version} <span className="mono">{cur.me.commit ?? ""}</span>
          {cur.update && <span className="needs-update"> · needs an update</span>}
        </p>
      )}
    </div>
  );
}

// Signed in to the hub, but no server to work on.
export function HubEmpty({ hub, servers, onAdmin }: { hub: HubMe; servers: ServerView[]; onAdmin: (tab: AdminTab) => void }) {
  const waiting = servers.filter((s) => s.state === "noProfile");
  return (
    <div className="no-profile">
      <Brand me={null} hub={hub} />
      <p>
        {!servers.length ? <>No Claudia server is open to {hub.email} yet. Ask an admin for access.</>
          : <>{waiting.map((s) => `Needs a profile on ${s.label}`).join(". ")}. Someone with root there creates it with one command.</>}
      </p>
      {hub.admin && (
        <p className="hub-admin-links">
          <button className="archived-toggle" onClick={() => onAdmin("servers")}>Servers</button>
          <button className="archived-toggle" onClick={() => onAdmin("access")}>Access</button>
        </p>
      )}
    </div>
  );
}

// ---- The admins' pages ----

export type AdminTab = "servers" | "access";
type Pairing = { server: string; publicKey: string; ip: string; createdAt: number; fetchedAt: number | null };
type AdminServer = { name: string; label: string; address: string; pairing: Pairing | null; peerUp: boolean };
type AdminState = { servers: AdminServer[]; people: string[]; access: { email: string; server: string }[] };

// Every admin call answers the new state, or an error.
async function adminCall(method: string, path: string, body?: object): Promise<AdminState | { error: string }> {
  try {
    const r = await fetch(path, { method, headers: body ? { "Content-Type": "application/json" } : undefined, body: body ? JSON.stringify(body) : undefined });
    const j = await r.json().catch(() => ({ error: `HTTP ${r.status}` }));
    return r.ok ? j : { error: String(j.error ?? `HTTP ${r.status}`) };
  } catch (err) {
    return { error: (err as Error).message };
  }
}

const when = (at: number) => new Date(at).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });

function pairingNote(s: AdminServer) {
  if (!s.pairing) return { text: "on WireGuard", tip: "Added by its address (already a WireGuard peer)" };
  if (s.pairing.fetchedAt) return { text: "fetched", tip: `Paired ${when(s.pairing.createdAt)}; the server fetched its address ${when(s.pairing.fetchedAt)} (${s.pairing.ip})` };
  if (s.peerUp) return { text: "peer up", tip: `The WireGuard peer is up (${s.pairing.ip}); waiting for the server to fetch its address` };
  return { text: "waiting for peer", tip: `Paired ${when(s.pairing.createdAt)}; the WireGuard peer isn't added yet` };
}

export function AdminDialog({ tab: first, close }: { tab: AdminTab; close: () => void }) {
  const [tab, setTab] = useState<AdminTab>(first);
  const [state, setState] = useState<AdminState | null>(null);
  const [error, setError] = useState<string | null>(null);
  const run = async (p: Promise<AdminState | { error: string }>) => {
    const r = await p;
    if ("error" in r) { setError(r.error); return false; }
    setError(null);
    setState(r);
    return true;
  };
  useEffect(() => { void run(adminCall("GET", "/hub/admin")); }, []);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && close();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [close]);

  return (
    <div className="settings-backdrop" onClick={close}>
      <div className="settings hub-admin" role="dialog" aria-label={tab === "servers" ? "Servers" : "Access"} onClick={(e) => e.stopPropagation()}>
        <header>
          <div className="profile-tabs" role="tablist">
            <button role="tab" aria-selected={tab === "servers"} className={tab === "servers" ? "on" : ""} onClick={() => setTab("servers")}>Servers</button>
            <button role="tab" aria-selected={tab === "access"} className={tab === "access" ? "on" : ""} onClick={() => setTab("access")}>Access</button>
          </div>
          <button className="icon" onClick={close} title="Close (Esc)">×</button>
        </header>
        {error && <p className="token-error">{error}</p>}
        {!state ? <p className="muted small">Loading…</p> : tab === "servers" ? <ServersPage state={state} run={run} /> : <AccessPage state={state} run={run} />}
      </div>
    </div>
  );
}

type Run = (p: Promise<AdminState | { error: string }>) => Promise<boolean>;

function ServerRow({ s, run }: { s: AdminServer; run: Run }) {
  const [label, setLabel] = useState(s.label);
  const [address, setAddress] = useState(s.address);
  const [removing, setRemoving] = useState(false);
  useEffect(() => { setLabel(s.label); setAddress(s.address); }, [s.label, s.address]);
  const changed = label.trim() !== s.label || address.trim() !== s.address;
  const p = pairingNote(s);
  return (
    <tr>
      <td><input value={label} onChange={(e) => setLabel(e.target.value)} aria-label={`${s.name}'s label`} /></td>
      <td className="mono">{s.name}</td>
      <td><input className="mono" value={address} onChange={(e) => setAddress(e.target.value)} aria-label={`${s.name}'s address`} /></td>
      <td><span className={`pair-state ${p.text.replace(/ /g, "-")}`} {...tipProps(p.tip)}>{p.text}</span></td>
      <td className="row-actions">
        {changed && <button onClick={() => void run(adminCall("PUT", `/hub/admin/servers/${s.name}`, { label: label.trim(), address: address.trim() }))}>Save</button>}
        {removing ? (
          <>
            <button className="danger" onClick={() => void run(adminCall("DELETE", `/hub/admin/servers/${s.name}`))}>Remove</button>
            <button onClick={() => setRemoving(false)}>Keep</button>
          </>
        ) : <button onClick={() => setRemoving(true)} title="Remove this server (its WireGuard peer too, if paired here)">×</button>}
      </td>
    </tr>
  );
}

function ServersPage({ state, run }: { state: AdminState; run: Run }) {
  const [name, setName] = useState("");
  const [label, setLabel] = useState("");
  const [address, setAddress] = useState("");
  const [key, setKey] = useState("");
  const [pName, setPName] = useState("");
  const [pLabel, setPLabel] = useState("");
  const add = async (e: React.FormEvent) => {
    e.preventDefault();
    if (await run(adminCall("PUT", `/hub/admin/servers/${encodeURIComponent(name.trim())}`, { label: label.trim(), address: address.trim() }))) { setName(""); setLabel(""); setAddress(""); }
  };
  const pair = async (e: React.FormEvent) => {
    e.preventDefault();
    if (await run(adminCall("POST", "/hub/admin/pair", { key: key.trim(), name: pName.trim() || undefined, label: pLabel.trim() || undefined }))) { setKey(""); setPName(""); setPLabel(""); }
  };
  return (
    <>
      <section>
        <h3>Servers <span className="muted small">{state.servers.length}</span></h3>
        {state.servers.length ? (
          <table className="admin-table">
            <thead><tr><th>Label</th><th>Name</th><th>Address</th><th>Pairing</th><th /></tr></thead>
            <tbody>{state.servers.map((s) => <ServerRow key={s.name} s={s} run={run} />)}</tbody>
          </table>
        ) : <p className="muted small">None yet.</p>}
      </section>
      <section>
        <h3>Add a server <span className="muted small">already on WireGuard</span></h3>
        <form className="admin-form" onSubmit={add}>
          <input placeholder="name (team)" value={name} onChange={(e) => setName(e.target.value)} spellCheck={false} />
          <input placeholder="Label (Team server)" value={label} onChange={(e) => setLabel(e.target.value)} />
          <input className="mono" placeholder="host:port" value={address} onChange={(e) => setAddress(e.target.value)} spellCheck={false} />
          <button disabled={!name.trim() || !address.trim()}>Add</button>
        </form>
      </section>
      <section>
        <h3>Pair a new server <span className="muted small">the key its pair script printed</span></h3>
        <form className="admin-form pair" onSubmit={pair}>
          <input className="mono key" placeholder="claudia-pair:…" value={key} onChange={(e) => setKey(e.target.value)} spellCheck={false} autoComplete="off" />
          <input placeholder="name (optional)" value={pName} onChange={(e) => setPName(e.target.value)} spellCheck={false} />
          <input placeholder="Label (optional)" value={pLabel} onChange={(e) => setPLabel(e.target.value)} />
          <button disabled={!key.trim()}>Pair</button>
        </form>
      </section>
    </>
  );
}

function AccessPage({ state, run }: { state: AdminState; run: Run }) {
  const [email, setEmail] = useState("");
  const [removing, setRemoving] = useState<string | null>(null);
  const granted = new Set(state.access.map((a) => `${a.email}\n${a.server}`));
  const add = async (e: React.FormEvent) => {
    e.preventDefault();
    if (await run(adminCall("POST", "/hub/admin/people", { email: email.trim() }))) setEmail("");
  };
  return (
    <>
      <section>
        <h3>Who works where <span className="muted small">a tick = their own profile on that server</span></h3>
        {state.people.length ? (
          <div className="admin-grid-wrap">
            <table className="admin-table grid">
              <thead><tr><th>Person</th>{state.servers.map((s) => <th key={s.name} title={s.name}>{s.label}</th>)}<th /></tr></thead>
              <tbody>
                {state.people.map((p) => (
                  <tr key={p}>
                    <td className="person">{p}</td>
                    {state.servers.map((s) => {
                      const on = granted.has(`${p}\n${s.name}`);
                      return (
                        <td key={s.name} className="cell">
                          <input type="checkbox" checked={on} aria-label={`${p} on ${s.label}`} onChange={() => void run(adminCall("PUT", "/hub/admin/access", { email: p, server: s.name, granted: !on }))} />
                        </td>
                      );
                    })}
                    <td className="row-actions">
                      {removing === p ? (
                        <>
                          <button className="danger" onClick={() => { setRemoving(null); void run(adminCall("DELETE", `/hub/admin/people/${encodeURIComponent(p)}`)); }}>Remove</button>
                          <button onClick={() => setRemoving(null)}>Keep</button>
                        </>
                      ) : <button onClick={() => setRemoving(p)} title="Remove this person (and every tick)">×</button>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : <p className="muted small">Nobody yet.</p>}
      </section>
      <section>
        <h3>Add a person <span className="muted small">by their sign-in email</span></h3>
        <form className="admin-form person" onSubmit={add}>
          <input type="email" placeholder="email" value={email} onChange={(e) => setEmail(e.target.value)} spellCheck={false} />
          <button disabled={!email.trim()}>Add</button>
        </form>
      </section>
    </>
  );
}
