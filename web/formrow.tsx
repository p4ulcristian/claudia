// The row of forms over the picture: one chalk circle per form; hover shows the form's sample,
// a click goes allowed → required → off for this chat. "required" keeps one in the picture, "off"
// takes it away (both through the chat's draw tool, src/chat.ts tools()).
import React, { useState } from "react";
import { FORMS, FORM_NAMES, type FormName } from "../src/forms";
import { FormView } from "./forms";

// "allowed" is the default and isn't saved: a form left out of the chat's choices is allowed.
type Choice = "required" | "off";
const NEXT: Record<string, Choice | undefined> = { allowed: "required", required: "off", off: undefined };
// Questions only go allowed ↔ off: allowed (Claude may ask with buttons) → off (it decides itself).
const NEXT_Q: Record<string, Choice | undefined> = { allowed: "off", off: undefined };
const SAY: Record<string, string> = { allowed: "allowed: Claude may draw it", required: "required: always in the picture", off: "off: Claude can't draw it" };
const SAY_Q: Record<string, string> = { allowed: "allowed: Claude may ask you", off: "off: Claude decides itself" };
const say = (f: string, state: string) => (f === "questions" ? SAY_Q : SAY)[state];
// The non-form circles (freeform, questions): label, text and glyph.
const extra = (f: string) => (f === "freeform" ? FREEFORM : f === "questions" ? QUESTIONS : f === "graph" ? GRAPH : null);

// A small chalk drawing per form.
const GLYPH: Record<FormName, React.ReactNode> = {
  pipeline: <path d="M3 10h3M14 10h3M7 7h6v6H7z" />,
  timeline: <path d="M10 3v14M10 6h5M10 11h-5M10 15h4" />,
  compare: <path d="M3 5h6v10H3zM11 5h6v10h-6zM5 8h2M13 8h2M5 11h2M13 11h2" />,
  chart: <path d="M4 16V9M8 16V5M12 16v-6M16 16V7M3 16.5h14" />,
  stats: <path d="M4 14l3-3 3 2 5-6M4 16h12" />,
  files: <path d="M3 5.5h5l1.5 1.5H17v8.5H3zM6 10h8M6 13h5" />,
  git: <path d="M7 4v12M7 5.2a1.6 1.6 0 1 0 0 .01M7 15a1.6 1.6 0 1 0 0 .01M14 6.5a1.6 1.6 0 1 0 0 .01M14 8.2c0 3-7 2.5-7 5" />,
  flow: <path d="M2 4h4v4H2zM8 4h4v4H8zM14 4h4v4h-4zM4 12h6v4H4zM12 14h4M14 12l2 2-2 2" />,
};
// Freeform has no form entry: its own circle at the end of the row.
const GRAPH = {
  label: "Graph",
  when: "Circles, boxes and arrows: how things connect.",
  glyph: <path d="M5 6.5a2 2 0 1 0 0 .01M15 6.5a2 2 0 1 0 0 .01M10 15a2 2 0 1 0 0 .01M7 6.5h6M6 8.3l3 5M14 8.3l-3 5" />,
};
const QUESTIONS = {
  label: "Questions",
  when: "Claude asking you with buttons (the full-window choices). Off: it decides itself and says in a line what it chose.",
  glyph: <path d="M7.5 7.5a2.5 2.5 0 1 1 3.6 2.2c-.7.4-1.1.9-1.1 1.7v.6M10 15v.2" />,
};
const FREEFORM = {
  label: "Freeform",
  when: "Claude's own page: interactive, playful, made for this one thing. Required: Claude gets creative where it helps. Off: only graphs and forms.",
  glyph: <path d="M4 15c2-6 4-9 6-9s1 5 3 5 2-3 3-5M5 17h10" />,
};

export function FormRow({ forms, hidden, onChange, cols, onCols }: {
  forms: Record<string, Choice>;
  hidden: string[];
  onChange: (next: Record<string, Choice>) => void;
  cols: number;
  onCols: (cols: number) => void;
}) {
  const [peek, setPeek] = useState<string | null>(null);
  const shown = ["graph", ...FORM_NAMES.filter((f) => !hidden.includes(f)), "freeform", "questions"];
  const cycle = (f: string) => {
    const now = forms[f] ?? "allowed";
    const next = { ...forms };
    const to = (f === "questions" ? NEXT_Q : NEXT)[now];
    if (to) next[f] = to;
    else delete next[f];
    onChange(next);
  };
  return (
    <div className="form-row" onMouseLeave={() => setPeek(null)}>
      {shown.map((f) => {
        const state = forms[f] ?? "allowed";
        return (
          <button
            key={f}
            className={`form-dot ${state}`}
            onClick={() => cycle(f)}
            onMouseEnter={() => setPeek(f)}
            onFocus={() => setPeek(f)}
            aria-label={`${extra(f)?.label ?? FORMS[f as FormName].label}: ${say(f, state)}`}
          >
            <svg viewBox="0 0 20 20" aria-hidden="true">{extra(f)?.glyph ?? GLYPH[f as FormName]}</svg>
          </button>
        );
      })}
      {/* Columns for this chat's picture; only the counts that fit the pane show (CSS container queries). */}
      <div className="col-pick" role="radiogroup" aria-label="Columns">
        {[0, 1, 2, 3].map((n) => (
          <button key={n} className={`col-btn c${n} ${cols === n ? "on" : ""}`} role="radio" aria-checked={cols === n} onClick={() => onCols(n)} title={n ? `${n} column${n > 1 ? "s" : ""}` : "Claude's layout"}>
            {n ? <ColGlyph n={n} /> : "auto"}
          </button>
        ))}
      </div>
      {peek && (
        <div className="form-peek">
          <div className="form-peek-head">
            <b>{extra(peek)?.label ?? FORMS[peek as FormName].label}</b>
            <span className={`form-peek-state ${forms[peek] ?? "allowed"}`}>{say(peek, forms[peek] ?? "allowed")}</span>
          </div>
          <p>{extra(peek)?.when ?? FORMS[peek as FormName].when}</p>
          {!extra(peek) && <div className="form-peek-sample"><FormView form={peek as FormName} data={FORMS[peek as FormName].sample} /></div>}
          <p className="form-peek-hint">{peek === "questions" ? "click: allowed ↔ off" : "click: allowed → required → off"} · this chat only</p>
        </div>
      )}
    </div>
  );
}

function ColGlyph({ n }: { n: number }) {
  const w = (16 - (n - 1) * 2) / n;
  return (
    <svg viewBox="0 0 20 14" aria-hidden="true">
      {Array.from({ length: n }, (_, i) => <rect key={i} x={2 + i * (w + 2)} y={2} width={w} height={10} rx={1.5} />)}
    </svg>
  );
}
