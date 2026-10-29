// The chat list (the phone's list screen and the desktop's opened sidebar) and the Settings screen
// its gear opens (decision 26). The list holds only chats: a header (whose chats as a title menu,
// the 5-hour and week rings, the gear, + on a desktop), the search, the rows; + floats bottom right
// on a phone. Everything else (archived chats, sounds, popups, all sessions, forms, the instance
// chip, the plan bars, each server's settings) is on the Settings screen.
import React, { useEffect, useRef, useState } from "react";
import { ChatDot, ago } from "./chatdot";
import { chatColorVar } from "./colors";
import { LimitRings } from "./usage";
import { tipProps } from "./tip";
import { serverNote, type ServerView } from "./hub";
import type { Api } from "./api";
import type { Me } from "./settings";
import type { ChatRow, Meta, SearchFound } from "./types";

// A chat in the merged list: which server it is on, its key, and whether that server is off.
export type ListRow = ChatRow & { server: string; key: string; off: boolean };

const cap = (n: string) => n[0]!.toUpperCase() + n.slice(1);
const oneLine = (s: string) => s.replace(/\s+/g, " ").trim();

// ---- the title menu: whose chats, and which servers' ----

// key null = your own chats.
export type Whose = { key: string | null; label: string; on: boolean };
export type Tick = { name: string; label: string; mark: string; note: string | null; on: boolean; disabled: boolean };
export type TitleMenu = { title: string; hint: string | null; people: Whose[]; onPerson: (key: string | null) => void; servers: Tick[]; onServer: (name: string) => void };

// What the title says and offers. people: the profile names (the hub: over every server; a server's
// own page: its profiles); own: the ones that are you; viewing: whose chats show (null = yours).
// servers (hub only, with ticks when there are two or more, or one isn't plainly up): hidden = the
// unticked ones; shown(name) = it has a profile in this view.
export function titleMenu(o: {
  people: string[]; own: Set<string>; viewing: string | null; onPerson: (key: string | null) => void;
  servers: ServerView[] | null; marks: Map<string, string>; hidden: string[]; shown: (name: string) => boolean; onServers: (hidden: string[]) => void;
  archived: boolean;
}): TitleMenu {
  const mine = o.viewing === null || o.own.has(o.viewing);
  const others = o.people.filter((p) => !o.own.has(p));
  const people: Whose[] = others.length ? [{ key: null, label: "My chats", on: mine }, ...others.map((p) => ({ key: p, label: `${cap(p)}'s chats`, on: o.viewing === p }))] : [];
  const usable = (o.servers ?? []).filter((s) => o.shown(s.name));
  const ticked = usable.filter((s) => !o.hidden.includes(s.name));
  const servers: Tick[] = (o.servers ?? []).map((s) => {
    const inView = o.shown(s.name);
    const on = inView && !o.hidden.includes(s.name);
    return {
      name: s.name, label: s.label, mark: o.marks.get(s.name) ?? "", on,
      note: serverNote(s) ?? (inView ? null : o.viewing ? `no ${cap(o.viewing)} here` : null),
      // The last ticked server stays: a list of nothing helps nobody.
      disabled: !inView || (on && ticked.length === 1),
    };
  });
  const filtered = ticked.length > 0 && ticked.length < usable.length;
  return {
    title: o.archived ? "Archived chats" : mine ? "My chats" : `${cap(o.viewing!)}'s chats`,
    hint: filtered ? (ticked.length === 1 ? ticked[0]!.label : `${ticked.length} servers`) : null,
    people,
    onPerson: o.onPerson,
    servers,
    onServer: (name) => o.onServers(o.hidden.includes(name) ? o.hidden.filter((h) => h !== name) : [...o.hidden, name]),
  };
}

// On a server's own page whose chats show is a cookie, so every request follows it; the page starts over.
export function viewProfile(name: string) {
  document.cookie = `claudia_view=${name}; path=/; max-age=31536000; samesite=lax`;
  location.href = "/";
}

// A server's own page: its profiles; yours is me.profile.
export function serverPeople(me: Me | null): { people: string[]; own: Set<string>; viewing: string | null } {
  if (!me || me.profiles.length < 2) return { people: [], own: new Set(), viewing: null };
  return { people: me.profiles, own: new Set(me.profile ? [me.profile] : []), viewing: me.viewing && me.viewing !== me.profile ? me.viewing : null };
}

function Title({ menu }: { menu: TitleMenu }) {
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const away = (e: PointerEvent) => { if (!box.current?.contains(e.target as Node)) setOpen(false); };
    const esc = (e: KeyboardEvent) => { if (e.key === "Escape") { e.stopPropagation(); setOpen(false); } };
    window.addEventListener("pointerdown", away, true);
    window.addEventListener("keydown", esc, true);
    return () => {
      window.removeEventListener("pointerdown", away, true);
      window.removeEventListener("keydown", esc, true);
    };
  }, [open]);
  const has = menu.people.length > 1 || menu.servers.length > 0;
  const text = <><span className="cs-title-t">{menu.title}</span>{menu.hint && <span className="cs-title-hint">· {menu.hint}</span>}</>;
  if (!has) return <h2 className="cs-title">{text}</h2>;
  return (
    <div className="cs-title-box" ref={box}>
      <button className="cs-title" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
        {text}
        <svg className="cs-caret" viewBox="0 0 12 12" aria-hidden="true"><path d="M3 4.5l3 3 3-3" /></svg>
      </button>
      {open && (
        <div className="cs-menu" role="menu">
          {menu.people.length > 1 && <>
            <div className="cs-menu-head">Whose chats</div>
            {menu.people.map((p) => (
              <button key={p.key ?? ""} role="menuitemradio" aria-checked={p.on} className={`cs-menu-item ${p.on ? "on" : ""}`}
                onClick={() => { setOpen(false); if (!p.on) menu.onPerson(p.key); }}>
                <span className="cs-tick" aria-hidden="true">{p.on ? "✓" : ""}</span>
                <span className="cs-menu-label">{p.label}</span>
                {p.key !== null && <span className="cs-menu-note">read only</span>}
              </button>
            ))}
          </>}
          {menu.servers.length > 0 && <>
            <div className="cs-menu-head">Servers</div>
            {menu.servers.map((s) => (
              <button key={s.name} role="menuitemcheckbox" aria-checked={s.on} disabled={s.disabled} className={`cs-menu-item ${s.on ? "on" : ""}`}
                onClick={() => menu.onServer(s.name)}>
                <span className="cs-tick box" aria-hidden="true">{s.on ? "✓" : ""}</span>
                <span className="server-mark" aria-hidden="true">{s.mark}</span>
                <span className="cs-menu-label">{s.label}</span>
                {s.note && <span className="cs-menu-note warn">{s.note}</span>}
              </button>
            ))}
          </>}
        </div>
      )}
    </div>
  );
}

// ---- the rows ----

// The second line: what the chat does while it works or waits on you, else the start of Claude's last reply.
function secondLine(c: ChatRow): { kind: "waiting" | "working" | "agents" | "reply"; text: string } {
  const n = c.agents ?? 0;
  if (c.waiting) {
    const ask = c.asks?.[0], permit = c.permits?.[0];
    if (ask) return { kind: "waiting", text: `Asks you: ${oneLine(ask.question)}` };
    if (permit) return { kind: "waiting", text: `Needs your permission: ${permit.tool}` };
    return { kind: "waiting", text: "Waiting for you" };
  }
  if (c.running) return { kind: "working", text: n > 0 ? `Claude working · ${n} agent${n === 1 ? "" : "s"}` : "Claude working" };
  if (n > 0) return { kind: "agents", text: `Agents working · ${n}` };
  if (c.settling) return { kind: "agents", text: "Agents finished" };
  const t = c.lastTurn;
  return { kind: "reply", text: t?.reply ? oneLine(t.reply) : t?.stopped ? "Stopped" : t?.failed ? "Ended with an error" : "" };
}

type RowProps = {
  c: ListRow; active: boolean; done: boolean; readOnly: boolean; tag: string; api: Api; mark: React.ReactNode;
  title?: React.ReactNode; when?: string; line2?: boolean; tip: string;
  onClick: (e: React.MouseEvent<HTMLElement>) => void; onMenu: (e: React.MouseEvent) => void;
};

function Row({ c, active, done, readOnly, tag, api, mark, title, when, line2 = true, tip, onClick, onMenu }: RowProps) {
  const l2 = line2 ? secondLine(c) : null;
  return (
    <button
      className={`chat-row cs-row ${active ? "active" : ""} ${c.off ? "off" : ""} ${done ? "unseen" : ""}`}
      style={{ "--chat": chatColorVar(c.color) } as React.CSSProperties}
      onClick={onClick}
      onContextMenu={onMenu}
      {...tipProps(tip, chatColorVar(c.color), true)}
    >
      <ChatDot c={c} tag={tag} api={api} done={done} />
      <span className="cs-text">
        <span className="cs-l1">
          <span className="t">{title ?? c.title}</span>
          {readOnly && <span className="cs-ro" title="Read only" aria-label="read only"><svg viewBox="0 0 16 16" aria-hidden="true"><rect x="3.5" y="7" width="9" height="6.5" rx="1.5" /><path d="M5.5 7V5.2a2.5 2.5 0 0 1 5 0V7" /></svg></span>}
          {mark}
          <span className="when">{when ?? ago(c.at)}</span>
        </span>
        {l2 && <span className={`cs-l2 ${l2.kind}`}>{l2.text || " "}</span>}
      </span>
    </button>
  );
}

// The words of a search marked in a title.
function Marked({ text, words }: { text: string; words: string }) {
  const ws = words.toLowerCase().match(/[\p{L}\p{N}_]+/gu) ?? [];
  if (!ws.length) return <>{text}</>;
  const re = new RegExp(`(${ws.map((w) => w.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")).join("|")})`, "gi");
  return <>{text.split(re).map((p, i) => (i % 2 ? <mark key={i}>{p}</mark> : p))}</>;
}

// ---- the list ----

export type ChatListProps = {
  phone: boolean;
  // Another profile's chats: every row is read only, and there is no +.
  readOnly: boolean;
  menu: TitleMenu;
  meta: Meta;
  onSettings: () => void;
  onNew: () => void;
  archived: boolean;
  onLeaveArchived: () => void;
  search: string;
  onSearch: (text: string) => void;
  searchRef: React.Ref<HTMLInputElement>;
  onSearchKey: (e: React.KeyboardEvent<HTMLInputElement>) => void;
  rows: ListRow[];
  // Search hits across what was said (two letters or more), with the rows they belong to.
  found: (SearchFound & { server: string })[] | null;
  rowOf: (server: string, id: string) => ListRow | undefined;
  curKey: string;
  // The open chat is new and not listed yet.
  pending: boolean;
  empty: string;
  notes: string[];
  tags: Map<string, string>;
  apiOf: (server: string) => Api;
  done: (c: ListRow) => boolean;
  markOf: (c: ListRow) => React.ReactNode;
  tipOf: (c: ListRow) => string;
  onPick: (c: ListRow, e: React.MouseEvent<HTMLElement>) => void;
  onHit: (c: ListRow, uuid: string | null) => void;
  onMenu: (c: ListRow) => (e: React.MouseEvent) => void;
};

export function ChatList(p: ChatListProps) {
  const tagOf = (c: ListRow) => (c.project ? p.tags.get(c.project) ?? "" : "");
  const rowBits = (c: ListRow) => ({ c, active: c.key === p.curKey, done: p.done(c), readOnly: p.readOnly, tag: tagOf(c), api: p.apiOf(c.server), mark: p.markOf(c), tip: p.tipOf(c), onMenu: p.onMenu(c) });
  return (
    <div className="cs-pane">
      <header className="cs-head">
        <Title menu={p.menu} />
        <LimitRings meta={p.meta} onOpen={p.onSettings} />
        <button className="cs-icon cs-gear" onClick={p.onSettings} aria-label="Settings" {...tipProps("Settings")}>
          <svg viewBox="0 0 20 20" aria-hidden="true"><circle cx="10" cy="10" r="2.6" /><path d="M10 2.5v2.2M10 15.3v2.2M2.5 10h2.2M15.3 10h2.2M4.7 4.7l1.6 1.6M13.7 13.7l1.6 1.6M4.7 15.3l1.6-1.6M13.7 6.3l1.6-1.6" /><circle cx="10" cy="10" r="5.4" /></svg>
        </button>
        {!p.readOnly && !p.phone && (
          <button className="cs-icon cs-add" onClick={p.onNew} aria-label="New chat" {...tipProps("New chat (Alt+N)")}>
            <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M10 4.5v11M4.5 10h11" /></svg>
          </button>
        )}
      </header>
      {p.archived && (
        <button className="cs-archived" onClick={p.onLeaveArchived}>
          <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M12.5 4L6.5 10l6 6" /></svg>
          Back to chats
        </button>
      )}
      <label className="search-box cs-search">
        <input ref={p.searchRef} className="search" placeholder="Search chats" value={p.search} onChange={(e) => p.onSearch(e.target.value)} onKeyDown={p.onSearchKey} />
      </label>
      <nav className="cs-list" aria-label="Chats">
        {p.notes.map((n) => <p key={n} className="server-off-note">{n}</p>)}
        {p.found && (
          <>
            {p.found.map((f) => {
              const c = p.rowOf(f.server, f.id);
              if (!c) return null;
              return (
                <div key={c.key} className="found" style={{ "--chat": chatColorVar(c.color) } as React.CSSProperties}>
                  <Row {...rowBits(c)} line2={false} title={f.title ? <Marked text={c.title} words={p.search} /> : c.title}
                    when={f.count > 0 ? `${f.count} hit${f.count === 1 ? "" : "s"}` : ago(c.at)} onClick={() => p.onHit(c, null)} />
                  {f.hits.map((h) => (
                    <button key={h.uuid} className={`found-hit ${h.role}`} onClick={() => p.onHit(c, h.uuid)}>
                      <span className="who">{h.role === "user" ? "you" : "claude"}</span>
                      <span className="snip">{h.snippet.split(/(\u0001[^\u0002]*\u0002)/).map((part, i) => part.startsWith("\u0001") ? <mark key={i}>{part.slice(1, -1)}</mark> : part)}</span>
                    </button>
                  ))}
                </div>
              );
            })}
            {!p.found.length && <p className="muted small cs-empty">Nothing said matches.</p>}
          </>
        )}
        {!p.found && p.rows.map((c) => <Row key={c.key} {...rowBits(c)} onClick={(e) => p.onPick(c, e)} />)}
        {!p.found && p.pending && (
          <div className="chat-row cs-row active cs-new"><span className="rail-dot"><span className="rail-blank" aria-hidden="true" /></span><span className="cs-text"><span className="cs-l1"><span className="t">New chat</span></span></span></div>
        )}
        {!p.found && !p.rows.length && !p.pending && <p className="muted small cs-empty">{p.empty}</p>}
      </nav>
      {!p.readOnly && p.phone && (
        <button className="cs-fab" onClick={p.onNew} aria-label="New chat">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14M5 12h14" /></svg>
        </button>
      )}
    </div>
  );
}

// ---- the Settings screen ----

// True while `on`, and for `ms` after it goes off (a screen that slides out stays mounted meanwhile).
export function useLingers(on: boolean, ms = 400) {
  const [left, setLeft] = useState(on);
  useEffect(() => {
    if (on) return setLeft(true);
    const t = setTimeout(() => setLeft(false), ms);
    return () => clearTimeout(t);
  }, [on, ms]);
  return on || left;
}

export type SettingsPaneProps = {
  phone: boolean;
  onBack: () => void;
  // The instance chip (instance name | token | commit) and the plan bars with their reset times.
  brand: React.ReactNode;
  meters: React.ReactNode;
  onArchived: () => void;
  sounds: boolean;
  onSounds: (on: boolean) => void;
  popups: boolean;
  onPopups: (on: boolean) => void;
  all: boolean;
  onAll: (on: boolean) => void;
  onForms: () => void;
  // The hub's admins: its Servers and Access pages.
  onAdmin: ((tab: "servers" | "access") => void) | null;
  // Each server's own settings (null in a read-only view): in the hub, which server's first.
  serverPick: React.ReactNode;
  server: React.ReactNode;
};

// Mounted while shown and for its slide out (lingers); Escape goes back only while shown, and before
// the page's own keys see it (Escape there stops a running chat).
export function SettingsPane(p: SettingsPaneProps & { shown: boolean }) {
  const back = useRef(p.onBack);
  back.current = p.onBack;
  useEffect(() => {
    if (!p.shown) return;
    const esc = (e: KeyboardEvent) => {
      if (e.key !== "Escape" || (e.target as HTMLElement).closest?.("input, textarea")) return;
      e.preventDefault();
      e.stopPropagation();
      back.current();
    };
    window.addEventListener("keydown", esc, true);
    return () => window.removeEventListener("keydown", esc, true);
  }, [p.shown]);
  return (
    <div className="st-pane" role="dialog" aria-label="Settings" aria-hidden={!p.shown} inert={!p.shown}>
      <header className="st-head">
        <button className="st-back" onClick={p.onBack}>
          <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M12.5 4L6.5 10l6 6" /></svg>
          Chats
        </button>
        <h2>Settings</h2>
      </header>
      <div className="st-scroll">
        {p.brand}
        {p.meters}
        <section className="st-group" aria-label="Chats">
          <button className="st-row" onClick={p.onArchived}>
            <ArchiveIcon />
            <span className="st-label">Archived chats</span>
            <svg className="st-chev" viewBox="0 0 20 20" aria-hidden="true"><path d="M7.5 4l6 6-6 6" /></svg>
          </button>
          <Switch on={p.all} onChange={p.onAll} label="Show all ~/Work sessions" />
        </section>
        <section className="st-group" aria-label="Alerts">
          <Switch on={p.sounds} onChange={p.onSounds} label="Sounds" note="when a chat finishes or waits on you" />
          <Switch on={p.popups} onChange={p.onPopups} label="Desktop popups" />
        </section>
        <section className="st-group" aria-label="Drawing">
          <button className="st-row" onClick={p.onForms}>
            <svg className="st-icon" viewBox="0 0 20 20" aria-hidden="true"><rect x="3" y="3" width="6" height="6" rx="1.5" /><circle cx="14" cy="6" r="3" /><path d="M3 15h6M11 12.5l3 3 3-3" /></svg>
            <span className="st-label">Forms <span className="st-note">everything Claude can draw, and your templates</span></span>
            <svg className="st-chev" viewBox="0 0 20 20" aria-hidden="true"><path d="M7.5 4l6 6-6 6" /></svg>
          </button>
        </section>
        {p.onAdmin && (
          <section className="st-group" aria-label="Hub">
            <button className="st-row" onClick={() => p.onAdmin!("servers")}><span className="st-label">Servers <span className="st-note">the servers this hub reaches</span></span></button>
            <button className="st-row" onClick={() => p.onAdmin!("access")}><span className="st-label">Access <span className="st-note">who may use which server</span></span></button>
          </section>
        )}
        {p.server && (
          <div className="st-server">
            {p.serverPick}
            {p.server}
          </div>
        )}
      </div>
    </div>
  );
}

function Switch({ on, onChange, label, note }: { on: boolean; onChange: (on: boolean) => void; label: string; note?: string }) {
  return (
    <label className="st-row st-switch">
      <span className="st-label">{label}{note && <span className="st-note">{note}</span>}</span>
      <input type="checkbox" role="switch" checked={on} onChange={(e) => onChange(e.target.checked)} />
      <span className="st-knob" aria-hidden="true" />
    </label>
  );
}

// A box with a lid; the arrow points out of it for Unarchive.
export function ArchiveIcon({ out }: { out?: boolean }) {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="1.5" y="2.5" width="13" height="3.5" rx="0.8" />
      <path d="M2.8 6v6.5a1 1 0 0 0 1 1h8.4a1 1 0 0 0 1-1V6" />
      {out ? <path d="M8 12V8.2M6.3 9.8 8 8.1l1.7 1.7" /> : <path d="M6.3 9h3.4" />}
    </svg>
  );
}
