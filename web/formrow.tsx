// The row of forms over the picture: one chalk circle per form; hover shows the form's sample,
// a click goes auto → use → off for this chat. "use" asks Claude for the form, "off" takes it away
// (both through the chat's draw tool, src/chat.ts tools()).
import React, { useState } from "react";
import { FORMS, FORM_NAMES, type FormName } from "../src/forms";
import { FormView } from "./forms";

type Choice = "use" | "off";
const NEXT: Record<string, Choice | undefined> = { auto: "use", use: "off", off: undefined };
const SAY: Record<string, string> = { auto: "Claude decides", use: "use it: Claude reaches for it", off: "off: Claude won't draw it" };

// A small chalk drawing per form.
const GLYPH: Record<FormName, React.ReactNode> = {
  pipeline: <path d="M3 10h3M14 10h3M7 7h6v6H7z" />,
  timeline: <path d="M10 3v14M10 6h5M10 11h-5M10 15h4" />,
  compare: <path d="M3 5h6v10H3zM11 5h6v10h-6zM5 8h2M13 8h2M5 11h2M13 11h2" />,
  chart: <path d="M4 16V9M8 16V5M12 16v-6M16 16V7M3 16.5h14" />,
  stats: <path d="M4 14l3-3 3 2 5-6M4 16h12" />,
  code: <path d="M7 6l-4 4 4 4M13 6l4 4-4 4M11 4l-2 12" />,
  files: <path d="M3 5.5h5l1.5 1.5H17v8.5H3zM6 10h8M6 13h5" />,
};

export function FormRow({ forms, hidden, onChange }: { forms: Record<string, Choice>; hidden: string[]; onChange: (next: Record<string, Choice>) => void }) {
  const [peek, setPeek] = useState<FormName | null>(null);
  const shown = FORM_NAMES.filter((f) => !hidden.includes(f));
  const cycle = (f: FormName) => {
    const now = forms[f] ?? "auto";
    const next = { ...forms };
    const to = NEXT[now];
    if (to) next[f] = to;
    else delete next[f];
    onChange(next);
  };
  return (
    <div className="form-row" onMouseLeave={() => setPeek(null)}>
      {shown.map((f) => {
        const state = forms[f] ?? "auto";
        return (
          <button
            key={f}
            className={`form-dot ${state}`}
            onClick={() => cycle(f)}
            onMouseEnter={() => setPeek(f)}
            onFocus={() => setPeek(f)}
            aria-label={`${FORMS[f].label}: ${SAY[state]}`}
          >
            <svg viewBox="0 0 20 20" aria-hidden="true">{GLYPH[f]}</svg>
          </button>
        );
      })}
      {peek && (
        <div className="form-peek">
          <div className="form-peek-head">
            <b>{FORMS[peek].label}</b>
            <span className={`form-peek-state ${forms[peek] ?? "auto"}`}>{SAY[forms[peek] ?? "auto"]}</span>
          </div>
          <p>{FORMS[peek].when}</p>
          <div className="form-peek-sample"><FormView form={peek} data={FORMS[peek].sample} /></div>
          <p className="form-peek-hint">click: auto → use → off · this chat only</p>
        </div>
      )}
    </div>
  );
}
