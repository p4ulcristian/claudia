// The bar over the bottom of the right half (shown on hover): Picture | Timeline, for pictures a scrubber over every picture of
// the chat, one line at any width and any number of pictures.
import React, { useLayoutEffect, useRef, useState } from "react";
import type { Picture } from "./types";
import { layer } from "./viewport";

const clock = (t: number) => new Date(t).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", hour12: false });

export function RightBar(props: {
  view: "picture" | "timeline";
  onView: (v: "picture" | "timeline") => void;
  pictureBehind: boolean;
  pictures: Picture[];
  index: number; // the picture shown; -1 when there is none
  viewing: boolean; // an earlier picture, not the live one
  unchanged: boolean; // the last turn didn't draw
  onPick: (i: number) => void;
  onLive: () => void;
  onFold: () => void;
  foldDisabled: boolean;
}) {
  const { pictures, index } = props;
  const n = pictures.length;
  return (
    <footer className="rbar">
      <div className="view-switch" role="tablist">
        <button role="tab" aria-selected={props.view === "picture"} className={props.view === "picture" ? "on" : ""} onClick={() => props.onView("picture")} title={props.pictureBehind ? "New picture · show (Alt+T)" : "Claude's picture (Alt+T)"}>
          Picture{props.pictureBehind && <span className="fold-dot" />}
        </button>
        <button role="tab" aria-selected={props.view === "timeline"} className={props.view === "timeline" ? "on" : ""} onClick={() => props.onView("timeline")} title="Every step of the chat and its agents (Alt+T)">
          Timeline
        </button>
      </div>
      {props.view === "picture" && n > 0 && (
        <div className="rbar-nav">
          <button className="rbar-step" disabled={index <= 0} onClick={() => props.onPick(index - 1)} title="Previous picture (←)" aria-label="Previous picture">‹</button>
          {n > 1 ? <Scrubber pictures={pictures} index={index} onPick={props.onPick} /> : <span className="rbar-track-empty" />}
          <button className="rbar-step" disabled={index >= n - 1} onClick={() => props.onPick(index + 1)} title="Next picture (→)" aria-label="Next picture">›</button>
          <span className="rbar-count">{index + 1}<span className="muted">/{n}</span></span>
          {props.viewing ? (
            <button className="rbar-chip past" onClick={props.onLive} title="Back to the live picture">
              <span className="rbar-chip-dot" />
              <span className="rbar-chip-text">Back to live</span>
            </button>
          ) : (
            <span className={`rbar-chip live ${props.unchanged ? "still" : ""}`} title={props.unchanged ? "The live picture; the last turn didn't change it" : "The live picture"}>
              <span className="rbar-chip-dot" />
              <span className="rbar-chip-text">{props.unchanged ? "Live · unchanged" : "Live"}</span>
            </span>
          )}
        </div>
      )}
      <button className="rbar-fold" onClick={props.onFold} disabled={props.foldDisabled} title="Hide the right half (Alt+P)" aria-label="Hide the right half">›</button>
    </footer>
  );
}

// One mark per picture along a track; click or drag to move through them, hover to see which one.
function Scrubber({ pictures, index, onPick }: { pictures: Picture[]; index: number; onPick: (i: number) => void }) {
  const track = useRef<HTMLDivElement>(null);
  const tipRef = useRef<HTMLSpanElement>(null);
  const [hover, setHover] = useState<number | null>(null);
  const [width, setWidth] = useState(0);
  const n = pictures.length;
  const at = (i: number) => (n === 1 ? 100 : (i / (n - 1)) * 100);
  const nearest = (clientX: number) => {
    const r = track.current!.getBoundingClientRect();
    const f = Math.min(1, Math.max(0, (clientX - r.left) / r.width));
    return Math.round(f * (n - 1));
  };

  useLayoutEffect(() => {
    const el = track.current!;
    const ro = new ResizeObserver(() => setWidth(el.clientWidth));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  // The hover label sits over its mark but never leaves the right half.
  useLayoutEffect(() => {
    const tip = tipRef.current;
    const el = track.current;
    if (!tip || !el || hover === null) return;
    const box = (el.closest(".right") ?? document.body).getBoundingClientRect();
    const r = el.getBoundingClientRect();
    const w = tip.offsetWidth;
    const x = r.left + (r.width * at(hover)) / 100 - w / 2;
    // (the tip is fixed: placed in the page's layer)
    const l = layer();
    tip.style.left = `${Math.max(box.left + 8, Math.min(x, box.right - w - 8)) - l.left}px`;
    tip.style.top = `${r.top - tip.offsetHeight - 6 - l.top}px`;
  });

  // Marks as dots while they have room, as thin lines when packed, and none past that (the rail still works).
  const gap = n > 1 ? width / (n - 1) : width;
  const density = gap >= 14 ? "" : gap >= 4 ? "dense" : "packed";
  return (
    <div
      className={`rbar-track ${density}`}
      ref={track}
      role="slider"
      aria-label="Pictures"
      aria-valuemin={1}
      aria-valuemax={n}
      aria-valuenow={index + 1}
      onPointerDown={(e) => {
        e.currentTarget.setPointerCapture(e.pointerId);
        onPick(nearest(e.clientX));
      }}
      onPointerMove={(e) => {
        const i = nearest(e.clientX);
        setHover(i);
        if (e.currentTarget.hasPointerCapture(e.pointerId) && i !== index) onPick(i);
      }}
      onPointerLeave={() => setHover(null)}
    >
      <span className="rbar-rail" />
      <span className="rbar-fill" style={{ width: `${at(index)}%` }} />
      {pictures.map((p, i) => (
        <span key={p.turn} className={`rbar-tick ${i === index ? "on" : ""} ${i === hover ? "hover" : ""}`} style={{ left: `${at(i)}%` }} />
      ))}
      {hover !== null && pictures[hover] && (
        <span className="rbar-tip" ref={tipRef}>
          <b>{hover + 1}</b> {pictures[hover].scene.title || "Untitled"} <span className="muted">{clock(pictures[hover].at)}</span>
        </span>
      )}
    </div>
  );
}
