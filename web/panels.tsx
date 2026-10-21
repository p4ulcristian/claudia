// Claude's questions (a fullscreen modal) and permission asks (the right half).
import React, { useEffect, useRef, useState } from "react";
import { md } from "./md";
import type { Ask, Permit } from "./types";

export function AskPanel({ ask, active, onPeek, onAnswer }: { ask: Ask; active: boolean; onPeek: () => void; onAnswer: (answers: Record<string, string>) => void }) {
  const [picked, setPicked] = useState<Record<number, string[]>>({});
  const [other, setOther] = useState<Record<number, string>>({});
  const [focus, setFocus] = useState<Record<number, string>>({});

  const answerOf = (i: number) => {
    const o = other[i]?.trim();
    const p = picked[i] ?? [];
    return [...p, ...(o ? [o] : [])].join(", ");
  };
  const ready = ask.questions.every((_, i) => answerOf(i));
  const submit = () => {
    if (!ready) return;
    onAnswer(Object.fromEntries(ask.questions.map((q, i) => [q.question, answerOf(i)])));
  };
  const toggleOption = (i: number, label: string) =>
    setPicked((s) => {
      const cur = s[i] ?? [];
      if (!ask.questions[i].multiSelect) return { ...s, [i]: cur[0] === label ? [] : [label] };
      return { ...s, [i]: cur.includes(label) ? cur.filter((l) => l !== label) : [...cur, label] };
    });

  // Keyboard: Enter answers (unless there's typed text in the chat box, which answers by itself);
  // 1–9 pick an option of the first question still without one; Esc peeks at the picture
  // (caught before the window's Esc, which would stop the chat). Nothing while peeking.
  const keys = useRef({ submit, toggleOption, picked, other });
  keys.current = { submit, toggleOption, picked, other };
  useEffect(() => {
    if (!active) return;
    const onEsc = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      e.preventDefault();
      e.stopImmediatePropagation();
      onPeek();
    };
    window.addEventListener("keydown", onEsc, true);
    return () => window.removeEventListener("keydown", onEsc, true);
  }, [active, onPeek]);
  useEffect(() => {
    if (!active) return;
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement;
      const typing = t.closest?.("textarea, input:not([type=checkbox]):not([type=radio]), [contenteditable]");
      const { submit, toggleOption, picked, other } = keys.current;
      if (e.key === "Enter" && !e.shiftKey && !e.isComposing) {
        if (t instanceof HTMLTextAreaElement && t.value.trim()) return;
        e.preventDefault();
        submit();
        return;
      }
      const n = Number(e.key);
      if (typing || e.ctrlKey || e.metaKey || e.altKey || !(n >= 1 && n <= 9)) return;
      const qi = ask.questions.findIndex((_, i) => !(picked[i]?.length || other[i]?.trim()));
      const i = qi < 0 ? 0 : qi;
      const opt = ask.questions[i]?.options[n - 1];
      if (!opt) return;
      e.preventDefault();
      toggleOption(i, opt.label);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [ask, active]);

  return (
    <section className="panel ask">
      {ask.questions.map((q, i) => {
        const multi = !!q.multiSelect;
        const toggle = (label: string) => toggleOption(i, label);
        const preview = q.options.find((o) => o.label === (focus[i] ?? picked[i]?.[0]))?.preview;
        return (
          <div className="question" key={i}>
            <div className="q-head">
              <span className="chip">{q.header}</span>
              <span className="muted">{multi ? "pick any" : "pick one"}</span>
            </div>
            <h3>{q.question}</h3>
            <div className="options" role={multi ? "group" : "radiogroup"}>
              {q.options.map((o, n) => {
                const on = (picked[i] ?? []).includes(o.label);
                return (
                  <button
                    key={o.label}
                    type="button"
                    role={multi ? "checkbox" : "radio"}
                    aria-checked={on}
                    className={`option ${on ? "on" : ""}`}
                    onClick={() => toggle(o.label)}
                    onMouseEnter={() => setFocus((f) => ({ ...f, [i]: o.label }))}
                    onFocus={() => setFocus((f) => ({ ...f, [i]: o.label }))}
                  >
                    <span className={`mark ${multi ? "box" : "dot"}`} />
                    <span className="o-text">
                      <b>{n < 9 && <span className="o-num">{n + 1}</span>}{o.label}</b>
                      {o.description && <span className="md" dangerouslySetInnerHTML={{ __html: md(o.description) }} />}
                    </span>
                  </button>
                );
              })}
              <label className={`option other ${other[i]?.trim() ? "on" : ""}`}>
                <span className={`mark ${multi ? "box" : "dot"}`} />
                <input
                  placeholder="Something else…"
                  value={other[i] ?? ""}
                  onChange={(e) => {
                    const v = e.target.value;
                    setOther((s) => ({ ...s, [i]: v }));
                    if (!multi && v.trim()) setPicked((s) => ({ ...s, [i]: [] }));
                  }}
                />
              </label>
            </div>
            {preview && <pre className="preview">{preview}</pre>}
          </div>
        );
      })}
      <div className="actions">
        <span className="muted small key-hint">1–{Math.min(9, Math.max(...ask.questions.map((q) => q.options.length)))} to pick · Enter to answer · Esc to see the picture</span>
        <button onClick={onPeek}>See picture</button>
        <button className="primary" disabled={!ready} onClick={submit}>Answer</button>
      </div>
    </section>
  );
}

export function PermitPanel({ permit, onPermit }: { permit: Permit; onPermit: (allow: boolean) => void }) {
  const [open, setOpen] = useState(false);
  return (
    <section className="panel permit">
      <div className="q-head"><span className="chip amber">{permit.tool}</span><span className="muted">needs your OK</span></div>
      <h3>{permit.title}</h3>
      {permit.description && <p className="muted">{permit.description}</p>}
      <button className="link" onClick={() => setOpen(!open)}>{open ? "Hide details" : "Details"}</button>
      {open && <pre className="preview">{permit.detail}</pre>}
      <div className="actions">
        <button onClick={() => onPermit(false)}>Deny</button>
        <button className="primary" onClick={() => onPermit(true)}>Allow</button>
      </div>
    </section>
  );
}
