// The forms gallery: every kind of block Claude can draw, each on sample data with its "when to
// use" line, and the templates saved from freeform blocks. Same renderers as the picture.
import React, { useEffect, useState } from "react";
import { FORMS, FORM_NAMES } from "../src/forms";
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
function Entry({ n, block, when, children }: { n: number; block: Block; when: string; children: React.ReactNode }) {
  return (
    <section className="g-entry">
      <div className="block-head"><span className="g-num">#{n}</span><BlockBadge block={block} /></div>
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
  const [deleting, setDeleting] = useState<string | null>(null);
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
          {FORM_NAMES.map((f, i) => (
            <Entry key={f} n={i + 2} block={{ id: f, kind: "form", form: f, data: {} }} when={FORMS[f].when}>
              <FormView form={f} data={FORMS[f].sample} />
            </Entry>
          ))}
          <Entry n={FORM_NAMES.length + 2} block={{ id: "freeform", kind: "html", html: "", parts: {} }} when="Claude's own small page, for what no graph or form shows. Save a good one as a template from its badge.">
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
                    <span className="g-num">#{FORM_NAMES.length + 3 + i}</span>
                    <BlockBadge block={{ id: t.name, kind: "html", html: t.html, parts: t.parts, template: t.name }} />
                    <button className="tpl-delete" onClick={() => setDeleting(t.name)}>delete</button>
                  </div>
                  {t.description && <p className="g-when">{t.description}</p>}
                  <div className="g-sample"><PageFrame html={t.html} parts={t.parts} /></div>
                </section>
              ))}
            </div>}
      </div>
      {deleting && <ConfirmDelete name={deleting} onYes={() => remove(deleting)} onNo={() => setDeleting(null)} />}
    </div>
  );
}

// Asked before a template goes: a chalk card over the window; Escape or a click outside keeps it.
function ConfirmDelete({ name, onYes, onNo }: { name: string; onYes: () => void; onNo: () => void }) {
  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if (e.key === "Escape") { e.stopImmediatePropagation(); onNo(); }
    };
    window.addEventListener("keydown", key, true);
    return () => window.removeEventListener("keydown", key, true);
  }, [onYes, onNo]);
  return (
    <div className="confirm-backdrop" onClick={(e) => { e.stopPropagation(); onNo(); }}>
      <div className="confirm-card" role="alertdialog" aria-modal="true" aria-label="Delete template" onClick={(e) => e.stopPropagation()}>
        <h3>Delete this template?</h3>
        <p><b>{name}</b> goes from the Forms window and Claude can't draw it by name any more. Pictures that already use it keep their copy.</p>
        <div className="confirm-actions">
          <button className="confirm-no" onClick={onNo} autoFocus>Keep it</button>
          <button className="confirm-yes" onClick={onYes}>Delete</button>
        </div>
      </div>
    </div>
  );
}
