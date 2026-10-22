// A chat's colour: a dot in the header opens the palette; the list and the chat's accent wear it.
import React, { useEffect, useRef, useState } from "react";
import { CHAT_COLORS, PASSIVE } from "../src/colors";

export const chatColorVar = (color: string | null | undefined) =>
  color && (CHAT_COLORS as readonly string[]).includes(color) ? `var(--c-${color})` : undefined;

export function ColorPicker({ color, onPick }: { color: string | null; onPick: (color: string | null) => void }) {
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const away = (e: PointerEvent) => { if (!box.current?.contains(e.target as Node)) setOpen(false); };
    const esc = (e: KeyboardEvent) => { if (e.key === "Escape") setOpen(false); };
    window.addEventListener("pointerdown", away);
    window.addEventListener("keydown", esc);
    return () => { window.removeEventListener("pointerdown", away); window.removeEventListener("keydown", esc); };
  }, [open]);

  const pick = (c: string | null) => { onPick(c); setOpen(false); };

  return (
    <div className="color-pick" ref={box}>
      <button
        className={`color-dot ${color ? "" : "none"}`}
        style={{ background: chatColorVar(color) }}
        onClick={() => setOpen((o) => !o)}
        title="Chat colour"
        aria-haspopup="true"
        aria-expanded={open}
      />
      {open && (
        <div className="palette" role="menu">
          {CHAT_COLORS.map((c) => (
            <button key={c} className={`swatch ${c === color ? "on" : ""} ${c === PASSIVE ? "passive" : ""}`} style={{ background: `var(--c-${c})` }} onClick={() => pick(c)} title={c === PASSIVE ? "Passive: sinks to the bottom of the rail" : c} aria-label={c} />
          ))}
          <button className={`swatch none ${color ? "" : "on"}`} onClick={() => pick(null)} title="No colour" aria-label="No colour" />
        </div>
      )}
    </div>
  );
}
