// The flow form: something travelling through a system, one frame at a time. Stations sit side by
// side (a station may have steps inside); each lane under them carries one card that slides to
// the station (or step) it is at; a caption says what happens. Back / next (or ←/→ while it has
// focus), and several ways through (scenarios) to switch between.
import React, { useEffect, useLayoutEffect, useRef, useState } from "react";
import { codeTokens, type Token } from "./shiki";

type Data = Record<string, any>;
type Card = { lane?: string; at: string; title?: string; body?: string; color?: string; lang?: string; dim?: boolean; new?: string };
type Frame = { caption?: string; cards?: Card[]; on?: string[]; leave?: string[]; skip?: string[] };
const list = (v: unknown): any[] => (Array.isArray(v) ? v : []);
const CARD_W = 260;

export function Flow({ data }: { data: Data }) {
  const stations = list(data.stations);
  const lanes: { id: string; label?: string }[] = list(data.lanes).length ? list(data.lanes) : [{ id: "" }];
  // One way through (frames) or several to switch between (scenarios).
  const ways: { id: string; label?: string; frames: Frame[] }[] = list(data.scenarios).length
    ? list(data.scenarios).map((s) => ({ ...s, frames: list(s.frames) }))
    : [{ id: "", frames: list(data.frames) }];
  const [way, setWay] = useState(ways[0]?.id ?? "");
  const [i, setI] = useState(0);
  const cur = ways.find((w) => w.id === way) ?? ways[0];
  const frames = cur?.frames ?? [];
  const n = Math.min(i, Math.max(0, frames.length - 1));
  const f: Frame = frames[n] ?? {};
  const go = (d: number) => setI(Math.max(0, Math.min(frames.length - 1, n + d)));
  const pick = (id: string) => { setWay(id); setI(0); };

  // Where each station and step sits, measured, so a card can slide under it.
  const stage = useRef<HTMLDivElement>(null);
  const [x, setX] = useState<{ w: number; at: Record<string, number> }>({ w: 0, at: {} });
  useLayoutEffect(() => {
    const el = stage.current;
    if (!el) return;
    const measure = () => {
      const box = el.getBoundingClientRect();
      const at: Record<string, number> = {};
      el.querySelectorAll<HTMLElement>("[data-at]").forEach((s) => {
        const r = s.getBoundingClientRect();
        at[s.dataset.at!] = r.left - box.left + r.width / 2;
      });
      setX({ w: box.width, at });
    };
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, [JSON.stringify(stations)]);

  const cardOf = (lane: string, fr: Frame) => list(fr.cards).find((c: Card) => (c.lane ?? lanes[0].id) === lane) as Card | undefined;
  // A step's station is lit too.
  const stationOf = (id: string) => stations.find((s) => s.id === id || list(s.steps).some((t: any) => t.id === id))?.id;
  const lit = new Set([...list(f.cards).map((c: Card) => stationOf(c.at)), ...list(f.on).map(stationOf)].filter(Boolean));
  const has = (k: "on" | "leave" | "skip", id: string) => list(f[k]).includes(id);

  return (
    <div
      className="f-flow"
      tabIndex={0}
      onKeyDown={(e) => {
        if (e.key !== "ArrowLeft" && e.key !== "ArrowRight") return;
        e.preventDefault();
        e.stopPropagation(); // not the picture's own ←/→
        go(e.key === "ArrowLeft" ? -1 : 1);
      }}
    >
      <div className="f-flow-bar">
        <button type="button" className="f-flow-btn" onClick={() => go(-1)} disabled={n === 0}>◀ back</button>
        <button type="button" className="f-flow-btn" onClick={() => go(1)} disabled={n >= frames.length - 1}>next ▶</button>
        {ways.length > 1 && (
          <span className="f-flow-ways" role="radiogroup" aria-label="Scenario">
            {ways.map((w) => (
              <button key={w.id} type="button" role="radio" aria-checked={w.id === cur.id} className={`f-flow-btn ${w.id === cur.id ? "on" : ""}`} onClick={() => pick(w.id)}>{w.label ?? w.id}</button>
            ))}
          </span>
        )}
        <span className="f-flow-n">{frames.length ? `${n + 1} / ${frames.length}` : "no frames yet"}</span>
      </div>
      <div className="f-flow-stage" ref={stage}>
        <div className="f-flow-stations" style={{ gridTemplateColumns: stations.map((s) => `${Math.max(1, list(s.steps).length * 0.55)}fr`).join(" ") }}>
          {stations.map((s) => (
            <div key={s.id} className={`f-flow-st chalk-box ${lit.has(s.id) ? "on" : ""}`} data-at={s.id} data-item={s.id}>
              <b>{s.label ?? s.id}</b>
              {s.note && <small>{s.note}</small>}
              {list(s.steps).length > 0 && (
                <div className="f-flow-steps">
                  {list(s.steps).map((t: any) => (
                    <span key={t.id} data-at={t.id} className={`f-flow-step ${has("on", t.id) ? "on" : ""} ${has("leave", t.id) ? "leave" : ""} ${has("skip", t.id) ? "skip" : ""}`}>{t.label ?? t.id}</span>
                  ))}
                </div>
              )}
            </div>
          ))}
        </div>
        {lanes.map((l) => {
          // No card in this lane now: the last one fades out where it was.
          let card = cardOf(l.id, f);
          const gone = !card;
          for (let k = n - 1; !card && k >= 0; k--) card = cardOf(l.id, frames[k]);
          // The lane is as tall as its tallest card, so the cards below don't jump.
          const lines = Math.max(1, ...frames.map((fr) => cardOf(l.id, fr)).filter(Boolean).map((c) => String(c!.body ?? "").split("\n").length));
          const w = Math.min(CARD_W, x.w * 0.48);
          const at = card ? x.at[card.at] ?? 0 : 0;
          const left = Math.max(0, Math.min(at - w / 2, x.w - w));
          return (
            <div key={l.id} className="f-flow-lane" style={{ minHeight: lines * 18 + 52 }}>
              {l.label && <span className="f-flow-lane-label">{l.label}</span>}
              {card && x.w > 0 && (
                <div className={`f-flow-card ${gone ? "gone" : ""} ${card.dim ? "dim" : ""}`} style={{ left, width: w, "--c": `var(--${card.color ?? "grey"})` } as React.CSSProperties}>
                  {card.title && <div className="hd">{card.title}</div>}
                  {card.body !== undefined && <CardBody text={String(card.body)} lang={card.lang} fresh={card.new} />}
                </div>
              )}
            </div>
          );
        })}
      </div>
      {f.caption && <p className="f-flow-cap">{f.caption}</p>}
    </div>
  );
}

// A card's text, coloured when it names a language; `fresh` (a piece of it) is marked as new.
function CardBody({ text, lang, fresh }: { text: string; lang?: string; fresh?: string }) {
  const [tokens, setTokens] = useState<Token[][] | null>(null);
  useEffect(() => {
    let gone = false;
    setTokens(null);
    codeTokens(text, lang).then((t) => { if (!gone) setTokens(t); }).catch(() => {});
    return () => { gone = true; };
  }, [text, lang]);
  const from = fresh ? text.indexOf(fresh) : -1;
  const to = from < 0 ? -1 : from + fresh!.length;
  // Pieces of text with their colour, split where the new part starts and ends.
  const pieces: { s: string; color?: string; at: number }[] = [];
  let at = 0;
  const lines = tokens ?? text.split("\n").map((l) => [{ content: l }]);
  lines.forEach((line, li) => {
    for (const t of [...line, ...(li < lines.length - 1 ? [{ content: "\n" }] : [])]) {
      const cuts = [0, t.content.length, from - at, to - at].filter((c) => c > 0 && c < t.content.length);
      const edges = [0, ...new Set(cuts)].sort((a, b) => a - b).concat(t.content.length);
      for (let k = 0; k < edges.length - 1; k++) pieces.push({ s: t.content.slice(edges[k], edges[k + 1]), color: (t as Token).color, at: at + edges[k] });
      at += t.content.length;
    }
  });
  return (
    <pre>
      {pieces.map((p, k) => (
        <span key={k} className={p.at >= from && p.at < to ? "new" : undefined} style={p.color ? { color: p.color } : undefined}>{p.s}</span>
      ))}
    </pre>
  );
}
