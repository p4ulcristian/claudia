// The left half: the conversation, kept short, with tool steps folded away.
import React, { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { md } from "./md";
import type { Command, Item } from "./types";
import { CodeChip, codeSends } from "./editor";

type Tool = Extract<Item, { k: "tool" }>;
type Block = { k: "user" | "text" | "note"; item: Item } | { k: "steps"; tools: Tool[]; id: string };

function blocks(items: Item[]): Block[] {
  const out: Block[] = [];
  for (const it of items) {
    if (it.k === "tool") {
      const last = out[out.length - 1];
      if (last?.k === "steps") last.tools.push(it);
      else out.push({ k: "steps", tools: [it], id: it.id });
    } else out.push({ k: it.k, item: it });
  }
  return out;
}

// What a step works on, for "explain": the user's message and what Claude said just before.
export type StepCtx = { asked?: string; before?: string };

// When each running step was first seen here, for "running for 40 s" in an explanation.
const started = new Map<string, number>();
const explained = new Map<string, string>(); // finished step id → its explanation

// since: when the step started, if known better than "first seen here".
export function useExplain(t: Tool, ctx: StepCtx, since?: number) {
  const [note, setNote] = useState<{ text: string; busy?: boolean; error?: boolean } | null>(() => (explained.has(t.id) ? { text: explained.get(t.id)! } : null));
  const run = async () => {
    if (note?.busy) return;
    if (t.result && explained.has(t.id)) { setNote({ text: explained.get(t.id)! }); return; }
    setNote({ text: "explaining…", busy: true });
    const running = !t.result;
    try {
      const r = await fetch("/api/explain", {
        method: "POST",
        body: JSON.stringify({
          name: t.name, title: t.title, detail: t.detail, result: t.result?.text ?? null, error: !!t.result?.error, running,
          seconds: running && (since ?? started.get(t.id)) ? (Date.now() - (since ?? started.get(t.id)!)) / 1000 : undefined, ...ctx,
        }),
      });
      const j = await r.json();
      if (!r.ok) throw new Error(j.error ?? r.statusText);
      if (!running) explained.set(t.id, j.text);
      setNote({ text: j.text });
    } catch (err) {
      setNote({ text: `Couldn't explain: ${(err as Error).message}`, error: true });
    }
  };
  return { note, run, close: () => setNote(null) };
}

export function ExplainNote({ note, close }: { note: { text: string; busy?: boolean; error?: boolean }; close: () => void }) {
  return (
    <div className={`explain-note ${note.busy ? "busy" : ""} ${note.error ? "err" : ""}`}>
      <span>{note.text}</span>
      {!note.busy && <button className="explain-x" onClick={close} aria-label="Close">×</button>}
    </div>
  );
}

function Step({ t, ctx }: { t: Tool; ctx: StepCtx }) {
  const [open, setOpen] = useState(false);
  const state = !t.result ? "busy" : t.result.error ? "err" : "ok";
  if (!t.result && !started.has(t.id)) started.set(t.id, Date.now());
  const ex = useExplain(t, ctx);
  return (
    <li className={`step ${state}`}>
      <div className="step-line">
        <button className="step-row" onClick={() => setOpen(!open)}>
          <span className="step-dot" />
          <b>{t.name}</b>
          <span className="step-title">{t.title}</span>
        </button>
        <button className="explain-btn" onClick={ex.run} title="Explain this step in plain words (Haiku, on the side)">{ex.note?.busy ? "…" : "explain"}</button>
      </div>
      {ex.note && <ExplainNote note={ex.note} close={ex.close} />}
      {open && (
        <div className="step-detail">
          <pre>{t.detail}</pre>
          {t.result && <pre className={t.result.error ? "err" : ""}>{t.result.text || "(no output)"}</pre>}
        </div>
      )}
    </li>
  );
}

// The running step of a folded group, explained from its head.
function CurrentExplain({ t, ctx }: { t: Tool; ctx: StepCtx }) {
  const ex = useExplain(t, ctx);
  return (
    <>
      <button className="explain-btn live" onClick={ex.run} title="Explain what this step is doing (Haiku, on the side)">{ex.note?.busy ? "…" : "explain"}</button>
      {ex.note && <div className="explain-under"><ExplainNote note={ex.note} close={ex.close} /></div>}
    </>
  );
}

function Steps({ tools, live, ctx = {} }: { tools: Tool[]; live: boolean; ctx?: StepCtx }) {
  const [open, setOpen] = useState(false);
  const busy = tools.some((t) => !t.result);
  const errors = tools.filter((t) => t.result?.error).length;
  const names = [...new Set(tools.map((t) => t.name))].slice(0, 4).join(", ");
  const current = busy ? tools.find((t) => !t.result) : null;
  if (current && !started.has(current.id)) started.set(current.id, Date.now());
  return (
    <div className={`steps ${busy && live ? "busy" : ""}`}>
      <div className="steps-head-line">
        <button className="steps-head" onClick={() => setOpen(!open)}>
          <span className={`chev ${open ? "open" : ""}`}>›</span>
          <span>{tools.length} {tools.length === 1 ? "step" : "steps"}</span>
          <span className="muted">{current && live ? `${current.name} ${current.title}` : names}</span>
          {errors > 0 && <span className="err-count">{errors} failed</span>}
        </button>
        {current && live && !open && <CurrentExplain key={current.id} t={current} ctx={ctx} />}
      </div>
      {open && <ul>{tools.map((t) => <Step key={t.id} t={t} ctx={ctx} />)}</ul>}
    </div>
  );
}

// A subagent's messages, in the same compact form as the main chat.
export function Transcript({ items, live }: { items: Item[]; live: boolean }) {
  const bs = blocks(items);
  return (
    <div className="transcript">
      {bs.map((b, i) =>
        b.k === "steps" ? <Steps key={b.id} tools={b.tools} live={live && i === bs.length - 1} />
          : b.k === "text" ? <div key={(b.item as any).id} className="msg claude small"><Markdown text={(b.item as any).text} /></div>
          : null,
      )}
    </div>
  );
}

function Markdown({ text }: { text: string }) {
  return <div className="md" dangerouslySetInnerHTML={{ __html: md(text) }} />;
}

function Elapsed({ since }: { since: number }) {
  const [, tick] = useState(0);
  useEffect(() => {
    const t = setInterval(() => tick((n) => n + 1), 1000);
    return () => clearInterval(t);
  }, []);
  const s = Math.floor((Date.now() - since) / 1000);
  return <>{s < 60 ? `${s}s` : `${Math.floor(s / 60)}m ${s % 60}s`}</>;
}

export function Messages(props: {
  items: Item[];
  partial: string;
  running: boolean;
  runningSince: number;
  note?: string;
  error?: string;
  stopped: boolean;
  pictureTurns: Set<string>;
  changeTurns: Map<string, number>;
  viewing: string | null;
  onView: (turn: string | null) => void;
  // A new chat with this one up to the end of that message's turn (none when read only).
  onFork?: (uuid: string) => void;
}) {
  const { items, partial, running } = props;
  const ref = useRef<HTMLDivElement>(null);
  const stick = useRef(true);
  // Messages sent while Claude works wait below everything until Claude takes them in.
  const queued = items.filter((it): it is Extract<Item, { k: "user" }> => it.k === "user" && !!it.queued);
  const bs = blocks(queued.length ? items.filter((it) => !(it.k === "user" && it.queued)) : items);
  // The turn running now can't be forked yet: its message gets no fork link.
  const lastUser = running ? (items.findLast((it) => it.k === "user" && !it.queued) as Extract<Item, { k: "user" }> | undefined)?.uuid : undefined;

  useLayoutEffect(() => {
    const el = ref.current;
    if (el && stick.current) el.scrollTop = el.scrollHeight;
  }, [items, partial, running]);

  return (
    <div
      className="messages"
      ref={ref}
      onScroll={(e) => {
        const el = e.currentTarget;
        stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
      }}
    >
      {!items.length && !running && <div className="hello"><h2>What are we working on?</h2><p className="muted">Claude answers short here and explains on the right.</p></div>}
      {bs.map((b, i) => {
        if (b.k === "steps") {
          const asked = (bs.slice(0, i).reverse().find((x) => x.k === "user") as { item: Extract<Item, { k: "user" }> } | undefined)?.item.text;
          const prev = bs[i - 1];
          const before = prev?.k === "text" ? (prev.item as Extract<Item, { k: "text" }>).text : undefined;
          return <Steps key={b.id} tools={b.tools} live={running && i === bs.length - 1} ctx={{ asked, before }} />;
        }
        if (b.k === "user") {
          const it = b.item as Extract<Item, { k: "user" }>;
          const files = props.changeTurns.get(it.uuid) ?? 0;
          const has = props.pictureTurns.has(it.uuid) || files > 0;
          // (answers to Claude's questions are tool results inside a turn, not messages of their own)
          const forkable = !!props.onFork && !it.uuid.startsWith("answer-") && it.uuid !== lastUser;
          return (
            <div key={it.uuid} data-at={it.uuid} className={`msg user ${props.viewing === it.uuid ? "viewing" : ""}`}>
              <div className="bubble">
                {it.images > 0 && <span className="att">{it.images} image{it.images > 1 ? "s" : ""}</span>}
                <UserText text={it.text} />
              </div>
              {(has || forkable) && (
                <div className="msg-links">
                  {forkable && <button className="pic-link fork-link" title="A new chat with this one up to the end of this turn, in the same folder" onClick={() => props.onFork!(it.uuid)}>fork from here</button>}
                  {has && (
                    <button className="pic-link" title="Show the picture from this turn" onClick={() => props.onView(props.viewing === it.uuid ? null : it.uuid)}>
                      {props.viewing === it.uuid ? "back to live" : [props.pictureTurns.has(it.uuid) && "picture", files && `${files} ${files === 1 ? "file" : "files"}`].filter(Boolean).join(" · ")}
                    </button>
                  )}
                </div>
              )}
            </div>
          );
        }
        if (b.k === "note") {
          const it = b.item as Extract<Item, { k: "note" }>;
          return <pre key={`note-${it.id}-${i}`} className="note-line">{it.text}</pre>;
        }
        const it = b.item as Extract<Item, { k: "text" }>;
        return <div key={it.id} data-at={it.id.split(":")[0]} className="msg claude"><Markdown text={it.text} /></div>;
      })}
      {partial && <div className="msg claude streaming"><Markdown text={partial} /></div>}
      {running && (
        <div className="working">
          <span className="pulse" /> {props.note ?? "Working"} · <Elapsed since={props.runningSince} />
        </div>
      )}
      {queued.map((it) => (
        <div key={it.uuid} className="msg user queued" title="Claude reads this at its next step">
          <div className="bubble">
            {it.images > 0 && <span className="att">{it.images} image{it.images > 1 ? "s" : ""}</span>}
            <UserText text={it.text} />
          </div>
          <span className="queued-tag">queued</span>
        </div>
      ))}
      {props.stopped && !running && <div className="muted small">Stopped.</div>}
      {props.error && <div className="error-line">{props.error}</div>}
    </div>
  );
}

// The user's message: code sent from an editor block shows as a chip per file (its diff on click), the rest as text.
function UserText({ text }: { text: string }) {
  const sent = useMemo(() => codeSends(text), [text]);
  if (!sent) return <Markdown text={text} />;
  return (
    <>
      {sent.note && <Markdown text={sent.note} />}
      {sent.sends.map((s, i) => <CodeChip key={i} send={s} />)}
    </>
  );
}

type Img = { media_type: string; data: string; url: string };

export function Composer(props: { running: boolean; asking: boolean; onSend: (text: string, images: Img[]) => void; onStop: () => void; focusKey: string; initialText?: string; commands: Command[]; tools?: React.ReactNode; actions?: React.ReactNode }) {
  const [text, setText] = useState(props.initialText ?? "");
  const [images, setImages] = useState<Img[]>([]);
  const [pick, setPick] = useState(0);
  const [menuOff, setMenuOff] = useState(false);
  const ta = useRef<HTMLTextAreaElement>(null);

  // "/" at the start, before any space: offer the slash commands.
  const slash = /^\/(\S*)$/.exec(text);
  const matches = slash && !menuOff
    ? props.commands.filter((c) => c.name.toLowerCase().includes(slash[1].toLowerCase()))
        .sort((a, b) => Number(!b.name.startsWith(slash[1])) - Number(!a.name.startsWith(slash[1])))
        .slice(0, 8)
    : [];
  useEffect(() => setPick(0), [text]);
  const complete = (c: Command) => {
    setText(`/${c.name} `);
    setMenuOff(false);
    ta.current?.focus();
  };

  useEffect(() => ta.current?.focus(), [props.focusKey]);
  useLayoutEffect(() => {
    const el = ta.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = Math.min(el.scrollHeight, 260) + "px";
  }, [text]);

  const addFiles = (files: FileList | File[]) => {
    for (const f of Array.from(files)) {
      if (!f.type.startsWith("image/")) continue;
      const r = new FileReader();
      r.onload = () => {
        const url = String(r.result);
        setImages((xs) => [...xs, { media_type: f.type, data: url.split(",")[1], url }]);
      };
      r.readAsDataURL(f);
    }
  };

  const send = () => {
    const t = text.trim();
    if (!t && !images.length) return;
    props.onSend(t, images);
    setText("");
    setImages([]);
  };

  return (
    <div
      className="composer"
      onDragOver={(e) => e.preventDefault()}
      onDrop={(e) => { e.preventDefault(); addFiles(e.dataTransfer.files); }}
    >
      {images.length > 0 && (
        <div className="thumbs">
          {images.map((im, i) => (
            <div key={i} className="thumb">
              <img src={im.url} alt="" />
              <button onClick={() => setImages((xs) => xs.filter((_, j) => j !== i))} title="Remove">×</button>
            </div>
          ))}
        </div>
      )}
      {matches.length > 0 && (
        <ul className="slash-menu" role="listbox">
          {matches.map((c, i) => (
            <li key={c.name} role="option" aria-selected={i === pick} className={i === pick ? "on" : ""} onMouseDown={(e) => { e.preventDefault(); complete(c); }} onMouseEnter={() => setPick(i)}>
              <b>/{c.name}</b>{c.hint && <span className="hint"> {c.hint}</span>}
              <span className="desc">{c.description}</span>
            </li>
          ))}
        </ul>
      )}
      <div className="input-row">
        <textarea
          ref={ta}
          rows={1}
          value={text}
          placeholder={props.asking ? "Answer on the right, or type an answer here…" : props.running ? "Add to this turn…" : "Message Claude…"}
          onChange={(e) => { setText(e.target.value); if (!e.target.value.startsWith("/")) setMenuOff(false); }}
          onPaste={(e) => { if (e.clipboardData.files.length) { e.preventDefault(); addFiles(e.clipboardData.files); } }}
          onKeyDown={(e) => {
            if (matches.length) {
              if (e.key === "ArrowDown") { e.preventDefault(); return setPick((p) => (p + 1) % matches.length); }
              if (e.key === "ArrowUp") { e.preventDefault(); return setPick((p) => (p - 1 + matches.length) % matches.length); }
              if (e.key === "Tab" || (e.key === "Enter" && !e.shiftKey)) { e.preventDefault(); return complete(matches[pick]); }
              if (e.key === "Escape") { e.preventDefault(); e.stopPropagation(); return setMenuOff(true); }
            }
            if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); send(); }
          }}
        />
        <div className="toolbar">
          <div className="tools">{props.tools}</div>
          {props.actions}
          {props.running && <button className="stop" onClick={props.onStop} title="Stop (Esc)">Stop</button>}
          <button className="send primary" onClick={send} disabled={!text.trim() && !images.length} title="Send (Enter)">Send</button>
        </div>
      </div>
    </div>
  );
}
