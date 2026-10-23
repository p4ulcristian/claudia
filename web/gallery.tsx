// The forms gallery: every kind of block Claude can draw, each on sample data with its "when to
// use" line, and the templates saved from freeform blocks. Same renderers as the picture.
import React, { useEffect, useState } from "react";
import { FORMS, FORM_NAMES, type FormSpec } from "../src/forms";
import { FormView } from "./forms";
import { BlockBadge, Diagram, PageFrame } from "./picture";
import type { Block } from "./types";
import type { Template } from "../src/db";

const SAMPLE_GRAPH = {
  nodes: [
    { id: "you", label: "You", color: "violet" as const },
    { id: "page", label: "Page", shape: "box" as const, color: "blue" as const },
    { id: "claude", label: "Claude", color: "amber" as const, state: "active" as const },
  ],
  edges: [{ from: "you", to: "page", label: "type" }, { from: "page", to: "claude", label: "turn" }],
  groups: [],
};
const SAMPLE_FREEFORM = `<div style="display:flex;gap:10px;align-items:center"><span style="font:700 20px var(--hand)">Anything else</span><input type="range" min="0" max="10" value="6"><span style="color:var(--muted)">sliders, mockups, a table, code…</span></div>`;

// n: the entry's number in the window, so it can be named in a chat ("#7 and #12 look the same").
function Entry({ n, block, when, children, onDelete, onRestore }: { n: number; block: Block; when: string; children: React.ReactNode; onDelete?: () => void; onRestore?: () => void }) {
  return (
    <section className={`g-entry ${onRestore ? "deleted" : ""}`}>
      <div className="block-head">
        <span className="g-num">#{n}</span><BlockBadge block={block} />
        {onDelete && <button className="tpl-delete" onClick={onDelete}>delete</button>}
        {onRestore && <button className="tpl-delete restore" onClick={onRestore}>restore</button>}
      </div>
      <p className="g-when">{when}</p>
      <div className="g-sample">{children}</div>
    </section>
  );
}

export function FormsGallery({ close }: { close: () => void }) {
  const [templates, setTemplates] = useState<Template[] | null>(null);
  const load = () => fetch("/api/templates").then((r) => r.json()).then(setTemplates, () => setTemplates([]));
  useEffect(() => { load(); }, []);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && close();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [close]);
  // Built-in forms the user deleted (prefs.hiddenForms): Claude doesn't get them; restorable here.
  const [hidden, setHidden] = useState<string[]>([]);
  // Opt-in forms this instance doesn't have (prefs.notHere) aren't shown at all.
  const [notHere, setNotHere] = useState<string[]>(FORM_NAMES.filter((f) => (FORMS[f] as FormSpec).optIn));
  useEffect(() => { fetch("/api/prefs").then((r) => r.json()).then((p) => { setHidden(p.hiddenForms ?? []); setNotHere(p.notHere ?? []); }, () => {}); }, []);
  const forms = FORM_NAMES.filter((f) => !notHere.includes(f));
  const setForms = (next: string[]) => {
    setHidden(next);
    fetch("/api/prefs", { method: "PATCH", body: JSON.stringify({ hiddenForms: next }) });
  };
  const [deleting, setDeleting] = useState<{ kind: "form" | "template"; name: string } | null>(null);
  const remove = async (name: string) => {
    setDeleting(null);
    await fetch(`/api/templates/${encodeURIComponent(name)}`, { method: "DELETE" });
    load();
  };

  return (
    <div className="gallery-backdrop" onClick={close}>
      <div className="gallery right-scroll" role="dialog" aria-label="Forms" onClick={(e) => e.stopPropagation()}>
        <header>
          <h2>Forms</h2>
          <p className="muted">What Claude can draw on the right. Ask for one by its name, or for changes to a block by the name on its badge.</p>
          <button className="icon" onClick={close} title="Close (Esc)">×</button>
        </header>
        <div className="g-list">
          <Entry n={1} block={{ id: "graph", kind: "graph", name: "", nodes: [], edges: [], groups: [] }} when="Things and how they connect: circles, boxes, arrows, groups. The default.">
            <Diagram scene={SAMPLE_GRAPH} changed={[]} seq={0} />
          </Entry>
          {forms.map((f, i) => (
            <Entry key={f} n={i + 2} onDelete={hidden.includes(f) ? undefined : () => setDeleting({ kind: "form", name: f })} onRestore={hidden.includes(f) ? () => setForms(hidden.filter((x) => x !== f)) : undefined} block={{ id: f, kind: "form", form: f, data: {} }} when={FORMS[f].when}>
              <FormView form={f} data={FORMS[f].sample} />
            </Entry>
          ))}
          <Entry n={forms.length + 2} block={{ id: "freeform", kind: "html", html: "", parts: {} }} when="Claude's own small page, for what no graph or form shows. Save a good one as a template from its badge.">
            <PageFrame html={SAMPLE_FREEFORM} />
          </Entry>
        </div>
        <h3 className="g-section">Templates</h3>
        {templates === null ? <p className="muted">Loading…</p>
          : !templates.length ? <p className="muted">None yet. Use "save as template" on a freeform block, or ask Claude to save one.</p>
          : <div className="g-list">
              {templates.map((t, i) => (
                <section key={t.name} className="g-entry">
                  <div className="block-head">
                    <span className="g-num">#{forms.length + 3 + i}</span>
                    <BlockBadge block={{ id: t.name, kind: "html", html: t.html, parts: t.parts, template: t.name }} />
                    <button className="tpl-delete" onClick={() => setDeleting({ kind: "template", name: t.name })}>delete</button>
                  </div>
                  {t.description && <p className="g-when">{t.description}</p>}
                  <div className="g-sample"><PageFrame html={t.html} parts={t.parts} /></div>
                </section>
              ))}
            </div>}
      </div>
      {deleting && (
        <ConfirmDelete
          {...deleting}
          onYes={() => {
            if (deleting.kind === "template") void remove(deleting.name);
            else { setForms([...hidden, deleting.name]); setDeleting(null); }
          }}
          onNo={() => setDeleting(null)}
        />
      )}
    </div>
  );
}

// Asked before a template goes: a chalk card over the window; Escape or a click outside keeps it.
function ConfirmDelete({ kind, name, onYes, onNo }: { kind: "form" | "template"; name: string; onYes: () => void; onNo: () => void }) {
  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if (e.key === "Escape") { e.stopImmediatePropagation(); onNo(); }
    };
    window.addEventListener("keydown", key, true);
    return () => window.removeEventListener("keydown", key, true);
  }, [onYes, onNo]);
  return (
    <div className="confirm-backdrop" onClick={(e) => { e.stopPropagation(); onNo(); }}>
      <div className="confirm-card" role="alertdialog" aria-modal="true" aria-label={`Delete ${kind}`} onClick={(e) => e.stopPropagation()}>
        <h3>Delete this {kind}?</h3>
        {kind === "template"
          ? <p><b>{name}</b> goes from the Forms window and Claude can't draw it by name any more. Pictures that already use it keep their copy.</p>
          : <p>Claude stops getting the <b>{name}</b> form, in open chats too. Pictures that already have one keep showing it, and you can restore it here any time.</p>}
        <div className="confirm-actions">
          <button className="confirm-no" onClick={onNo} autoFocus>Keep it</button>
          <button className="confirm-yes" onClick={onYes}>Delete</button>
        </div>
      </div>
    </div>
  );
}
