// A box the user can make taller or shorter: a chalk grip under it to drag; double-click the grip
// for its starting size. The height is kept per box (key) on this device.
import React, { useState } from "react";

const read = (key: string) => {
  try { const v = Number(localStorage.getItem(`size:${key}`)); return v > 0 ? v : null; } catch { return null; }
};
const write = (key: string, h: number | null) => {
  try { h ? localStorage.setItem(`size:${key}`, String(Math.round(h))) : localStorage.removeItem(`size:${key}`); } catch {}
};

// initial: the starting height in px (left out: as tall as its content).
export function ResizeBox({ id, initial, className, children }: { id: string; initial?: number; className?: string; children: React.ReactNode }) {
  const [h, setH] = useState<number | null>(() => read(id) ?? initial ?? null);
  const box = React.useRef<HTMLDivElement>(null);
  const drag = (e: React.PointerEvent) => {
    e.preventDefault();
    const start = e.clientY;
    const from = box.current?.getBoundingClientRect().height ?? h ?? 200;
    let now = from;
    const move = (m: PointerEvent) => setH((now = Math.max(80, from + m.clientY - start)));
    const up = () => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", up);
      write(id, now);
    };
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up);
  };
  return (
    <div className={`rs ${h ? "sized" : ""} ${className ?? ""}`}>
      <div className="rs-body" ref={box} style={h ? { height: h } : undefined}>{children}</div>
      <div
        className="rs-grip"
        onPointerDown={drag}
        onDoubleClick={() => { setH(initial ?? null); write(id, null); }}
        title="Drag to resize · double-click for the starting size"
        role="separator"
        aria-orientation="horizontal"
      />
    </div>
  );
}
