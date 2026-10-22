// The predefined forms (src/forms.ts), drawn in the board's chalk style. Each gets its block's
// data as it stands after the draw calls (items merged by id).
import React, { useEffect, useRef } from "react";
import type { FormName } from "../src/forms";

type Data = Record<string, any>;
const list = (v: unknown): any[] => (Array.isArray(v) ? v : []);
const colorOf = (c?: string) => (c ? `var(--${c})` : undefined);

const STATUS_MARK: Record<string, string> = { done: "✓", running: "…", failed: "✗", skipped: "–", todo: "" };

function Pipeline({ data }: { data: Data }) {
  const stages = list(data.stages);
  return (
    <div className="f-pipeline">
      {stages.map((s, i) => (
        <React.Fragment key={s.id}>
          {i > 0 && <span className={`f-arrow ${stages[i - 1].status === "done" ? "lit" : ""}`} aria-hidden="true" />}
          <div className={`f-stage chalk-box st-${s.status ?? "todo"}`} data-item={s.id}>
            <span className="f-stage-mark">{STATUS_MARK[s.status ?? "todo"]}</span>
            <span className="f-stage-label">{s.label ?? s.id}</span>
            {s.note && <span className="f-note">{s.note}</span>}
          </div>
        </React.Fragment>
      ))}
    </div>
  );
}

function Timeline({ data }: { data: Data }) {
  return (
    <ol className="f-timeline">
      {list(data.events).map((e) => (
        <li key={e.id} className={`st-${e.status ?? "none"}`} data-item={e.id} style={{ "--dot": colorOf(e.color) } as React.CSSProperties}>
          <span className="f-at">{e.at}</span>
          <span className="f-dot" aria-hidden="true" />
          <span className="f-event">
            <b>{e.label ?? e.id}</b>
            {e.status && e.status !== "none" && <i className="f-tag">{e.status}</i>}
            {e.note && <span className="f-note">{e.note}</span>}
          </span>
        </li>
      ))}
    </ol>
  );
}

function Compare({ data }: { data: Data }) {
  return (
    <div className="f-compare">
      {list(data.options).map((o) => (
        <section key={o.id} className={`f-option chalk-box ${o.picked ? "picked" : ""}`} data-item={o.id}>
          <h4>{o.label ?? o.id}{o.picked && <span className="f-tag">picked</span>}</h4>
          {o.note && <p className="f-note">{o.note}</p>}
          <ul>
            {list(o.pros).map((p: string, i: number) => <li key={`p${i}`} className="pro">{p}</li>)}
            {list(o.cons).map((c: string, i: number) => <li key={`c${i}`} className="con">{c}</li>)}
          </ul>
        </section>
      ))}
    </div>
  );
}

function Stats({ data }: { data: Data }) {
  return (
    <div className="f-stats">
      {list(data.tiles).map((t) => (
        <div key={t.id} className="f-tile chalk-box" data-item={t.id} style={{ "--tile": colorOf(t.color) } as React.CSSProperties}>
          <span className="f-label">{t.label ?? t.id}</span>
          <span className="f-value">{t.value}</span>
          {typeof t.progress === "number" && (
            <span className="f-bar"><i style={{ width: `${Math.max(0, Math.min(1, t.progress)) * 100}%` }} /></span>
          )}
          {t.note && <span className="f-note">{t.note}</span>}
        </div>
      ))}
    </div>
  );
}

// Charts are Vega-Lite, loaded from the CDN the first time one is drawn, themed to the board.
const importUrl = new Function("u", "return import(u)") as (u: string) => Promise<any>;
let embedLib: Promise<any> | null = null;
const embed = () => (embedLib ??= importUrl("https://cdn.jsdelivr.net/npm/vega-embed@6/+esm").then((m) => m.default));

function Chart({ data }: { data: Data }) {
  const box = useRef<HTMLDivElement>(null);
  const key = JSON.stringify(data);
  useEffect(() => {
    const el = box.current;
    if (!el) return;
    let view: any;
    let gone = false;
    const cs = getComputedStyle(el);
    const v = (n: string) => cs.getPropertyValue(n).trim();
    const fg = v("--fg"), muted = v("--muted"), line = v("--line");
    const palette = ["--blue", "--amber", "--green", "--violet", "--red", "--grey"].map(v);
    const x = data.x ?? "x", y = data.y ?? "y";
    const values = list(data.rows).map(({ id: _id, ...rest }) => rest);
    const numericX = values.length > 0 && values.every((r) => typeof r[x] === "number");
    const spec = {
      $schema: "https://vega.github.io/schema/vega-lite/v5.json",
      width: "container",
      height: 220,
      background: "transparent",
      ...(data.title ? { title: data.title } : {}),
      data: { values },
      mark: { type: data.mark ?? "bar", tooltip: true, ...(data.mark === "line" || data.mark === "area" ? { point: true, strokeWidth: 3 } : { cornerRadiusEnd: 3 }) },
      encoding: {
        x: { field: x, type: numericX ? "quantitative" : "ordinal", sort: null, axis: { labelAngle: 0 } },
        y: { field: y, type: "quantitative" },
        ...(data.color ? { color: { field: data.color, type: "nominal" } } : { color: { value: palette[0] } }),
      },
      config: {
        font: "Atkinson Hyperlegible",
        view: { stroke: null },
        range: { category: palette },
        title: { font: "Kalam", fontSize: 18, fontWeight: 700, color: fg, anchor: "start" },
        axis: { labelColor: muted, titleColor: muted, domainColor: line, tickColor: line, gridColor: line, gridOpacity: 0.5, labelFontSize: 12, titleFont: "Kalam", titleFontSize: 14, titleFontWeight: 700 },
        legend: { labelColor: muted, titleColor: muted },
      },
    };
    embed()
      .then((vegaEmbed) => (gone ? null : vegaEmbed(el, spec, { actions: false, renderer: "svg" })))
      .then((r) => { view = r?.view; })
      .catch((e) => { if (!gone) el.textContent = `Chart failed: ${e.message ?? e}`; });
    return () => { gone = true; view?.finalize(); };
  }, [key]);
  return <div className="f-chart" ref={box} />;
}

const RENDER: Record<FormName, (p: { data: Data }) => React.ReactNode> = {
  pipeline: Pipeline,
  timeline: Timeline,
  compare: Compare,
  chart: Chart,
  stats: Stats,
};

export function FormView({ form, data }: { form: FormName; data: Data }) {
  const R = RENDER[form];
  return <div className={`form-view form-${form}`}>{R ? <R data={data} /> : <p className="f-note">Unknown form "{form}".</p>}</div>;
}
