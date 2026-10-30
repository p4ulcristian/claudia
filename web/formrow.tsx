// The forms: one chalk circle per form, in the right rail (on phones, a row over the picture); hover shows the form's sample,
// a click goes allowed → required → off for this chat. "required" keeps one in the picture, "off"
// takes it away (both through the chat's draw tool, src/chat.ts tools()).
import React, { useLayoutEffect, useRef, useState } from "react";
import { FORMS, FORM_NAMES, type FormName } from "../src/forms";
import { FormView } from "./forms";
import { layer } from "./viewport";

// "allowed" is the default and isn't saved: a form left out of the chat's choices is allowed.
type Choice = "required" | "off";
const NEXT: Record<string, Choice | undefined> = { allowed: "required", required: "off", off: undefined };
// Questions only go allowed ↔ off: allowed (Claude may ask with buttons) → off (it decides itself).
const NEXT_Q: Record<string, Choice | undefined> = { allowed: "off", off: undefined };
const SAY: Record<string, string> = { allowed: "allowed: Claude may draw it", required: "required: always in the picture", off: "off: Claude can't draw it" };
const SAY_Q: Record<string, string> = { allowed: "allowed: Claude may ask you", off: "off: Claude decides itself" };
const SAY_T: Record<string, string> = { allowed: "yours: click to show it in the picture", required: "in the picture: click to take it away", off: "off: click to show it in the picture" };
const say = (f: string, state: string) => (f === "questions" ? SAY_Q : TOOLS.includes(f) ? SAY_T : SAY)[state];
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
  clojure: <path d="M6 6a2.5 2.5 0 1 0 0 .01M14 5a1.8 1.8 0 1 0 0 .01M13 14.5a2.8 2.8 0 1 0 0 .01M8.4 6.4l3.8-.9M7.6 7.8l3.4 4.6M14 6.8l-.5 4.9" />,
  calls: <path d="M3 4h6M5 4v12M5 8h5M5 12h5M5 16h5M12 8a1.6 1.6 0 1 0 0 .01M12 12a1.6 1.6 0 1 0 0 .01M12 16a1.6 1.6 0 1 0 0 .01M14 8h3M14 12h3" />,
  editor: <path d="M2 3h16v14H2zM2 6h16M5 9l-2 2 2 2M8 14l3-6M14 9l2 2-2 2" />,
  scene3d: <path d="M10 3l6 3v8l-6 3-6-3V6zM4 6l6 3 6-3M10 9v8" />,
  flow: <path d="M2 4h4v4H2zM8 4h4v4H8zM14 4h4v4h-4zM4 12h6v4H4zM12 14h4M14 12l2 2-2 2" />,
};
// The user's own tools, at the foot of the rail in blue: a click shows one in the picture for the chat's
// folder (required) or takes it away again (allowed). Never off: Claude may always draw them too.
const TOOLS: string[] = ["files", "git", "clojure"];
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

export function FormRow({ forms, hidden, onChange, cols, onCols, rail }: {
  forms: Record<string, Choice>;
  hidden: string[];
  onChange: (next: Record<string, Choice>) => void;
  cols: number;
  onCols: (cols: number) => void;
  rail?: boolean; // a column in the right rail; the sample opens to its left, level with the circle
}) {
  const [peek, setPeek] = useState<string | null>(null);
  const [peekTop, setPeekTop] = useState(0);
  const box = useRef<HTMLDivElement>(null);
  const show = (f: string, el: HTMLElement) => {
    setPeek(f);
    if (rail && box.current) setPeekTop(el.getBoundingClientRect().top - box.current.getBoundingClientRect().top);
  };
  // In the rail the sample never runs off the bottom of the window.
  const peekBox = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    const el = peekBox.current;
    if (!rail || !el || !box.current) return;
    const top = box.current.getBoundingClientRect().top;
    const l = layer();
    el.style.top = `${Math.max(l.top + 8 - top, Math.min(peekTop, l.bottom - 8 - top - el.offsetHeight))}px`;
  });
  const shown = ["graph", ...FORM_NAMES.filter((f) => !hidden.includes(f) && !TOOLS.includes(f)), "freeform", "questions"];
  const tools = TOOLS.filter((f) => !hidden.includes(f));
  const cycle = (f: string) => {
    const now = forms[f] ?? "allowed";
    const next = { ...forms };
    const to = TOOLS.includes(f) ? (now === "required" ? undefined : "required") : (f === "questions" ? NEXT_Q : NEXT)[now];
    if (to) next[f] = to;
    else delete next[f];
    onChange(next);
  };
  const dot = (f: string) => {
    const state = forms[f] ?? "allowed";
    return (
      <button
        key={f}
        className={`form-dot ${TOOLS.includes(f) ? "tool" : ""} ${state}`}
        onClick={() => cycle(f)}
        onMouseEnter={(e) => show(f, e.currentTarget)}
        onFocus={(e) => show(f, e.currentTarget)}
        aria-label={`${extra(f)?.label ?? FORMS[f as FormName].label}: ${say(f, state)}`}
        aria-pressed={TOOLS.includes(f) ? state === "required" : undefined}
      >
        <svg viewBox="0 0 20 20" aria-hidden="true">{extra(f)?.glyph ?? GLYPH[f as FormName]}</svg>
      </button>
    );
  };
  return (
    <div className={rail ? "form-rail" : "form-row"} ref={box} onMouseLeave={() => setPeek(null)}>
      <div className="form-dots">
      {shown.map(dot)}
      </div>
      {tools.length > 0 && <div className="form-tools">{tools.map(dot)}</div>}
      {/* Columns for this chat's picture; only the counts that fit the pane show (CSS container queries). */}
      <div className="col-pick" role="radiogroup" aria-label="Columns">
        {[0, 1, 2, 3].map((n) => (
          <button key={n} className={`col-btn c${n} ${cols === n ? "on" : ""}`} role="radio" aria-checked={cols === n} onClick={() => onCols(n)} title={n ? `${n} column${n > 1 ? "s" : ""}` : "Claude's layout"}>
            {n ? <ColGlyph n={n} /> : "auto"}
          </button>
        ))}
      </div>
      {peek && (
        <div className="form-peek" ref={peekBox}>
          <div className="form-peek-head">
            <b>{extra(peek)?.label ?? FORMS[peek as FormName].label}</b>
            <span className={`form-peek-state ${forms[peek] ?? "allowed"}`}>{say(peek, forms[peek] ?? "allowed")}</span>
          </div>
          <p>{extra(peek)?.when ?? FORMS[peek as FormName].when}</p>
          {!extra(peek) && <div className="form-peek-sample"><FormView form={peek as FormName} data={FORMS[peek as FormName].sample} /></div>}
          <p className="form-peek-hint">{peek === "questions" ? "click: allowed ↔ off · this chat only" : TOOLS.includes(peek) ? "the chat's folder, live · Claude may draw it too" : "click: allowed → required → off · this chat only"}</p>
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
