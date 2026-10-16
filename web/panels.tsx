// The right half as an interface: Claude's questions and permission asks.
import React, { useState } from "react";
import { md } from "./md";
import type { Ask, Permit } from "./types";

export function AskPanel({ ask, onAnswer }: { ask: Ask; onAnswer: (answers: Record<string, string>) => void }) {
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

  return (
    <section className="panel ask" onKeyDown={(e) => e.key === "Enter" && (e.ctrlKey || e.metaKey) && submit()}>
      {ask.questions.map((q, i) => {
        const multi = !!q.multiSelect;
        const toggle = (label: string) =>
          setPicked((s) => {
            const cur = s[i] ?? [];
            if (!multi) return { ...s, [i]: cur[0] === label ? [] : [label] };
            return { ...s, [i]: cur.includes(label) ? cur.filter((l) => l !== label) : [...cur, label] };
          });
        const preview = q.options.find((o) => o.label === (focus[i] ?? picked[i]?.[0]))?.preview;
        return (
          <div className="question" key={i}>
            <div className="q-head">
              <span className="chip">{q.header}</span>
              <span className="muted">{multi ? "pick any" : "pick one"}</span>
            </div>
            <h3>{q.question}</h3>
            <div className="options" role={multi ? "group" : "radiogroup"}>
              {q.options.map((o) => {
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
                      <b>{o.label}</b>
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
