// Right click on a chat (a rail circle or a list row): colour, passive, rename, archive.
import React, { useEffect, useLayoutEffect, useRef, useState } from "react";
import { CHAT_COLORS, PASSIVE } from "../src/colors";

export type MenuChat = { id: string; title: string; color: string | null; archived?: boolean };

export function ChatMenu({ chat, x, y, close, onColor, onWake, onRename, onArchive }: {
  chat: MenuChat;
  x: number;
  y: number;
  close: () => void;
  onColor: (color: string | null) => void;
  onWake: () => void; // passive → a fresh colour of its own
  onRename: (title: string) => void;
  onArchive: (archived: boolean) => void;
}) {
  const box = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState({ left: x, top: y });
  const [renaming, setRenaming] = useState(false);

  // Kept inside the window.
  useLayoutEffect(() => {
    const r = box.current?.getBoundingClientRect();
    if (!r) return;
    setPos({ left: Math.max(6, Math.min(x, innerWidth - r.width - 6)), top: Math.max(6, Math.min(y, innerHeight - r.height - 6)) });
  }, [x, y, renaming]);

  useEffect(() => {
    const away = (e: PointerEvent) => { if (!box.current?.contains(e.target as Node)) close(); };
    const esc = (e: KeyboardEvent) => { if (e.key === "Escape") close(); };
    window.addEventListener("pointerdown", away, true);
    window.addEventListener("keydown", esc);
    window.addEventListener("blur", close);
    return () => {
      window.removeEventListener("pointerdown", away, true);
      window.removeEventListener("keydown", esc);
      window.removeEventListener("blur", close);
    };
  }, [close]);

  const passive = chat.color === PASSIVE;
  const act = (f: () => void) => () => { f(); close(); };

  return (
    <div className="chat-menu" ref={box} style={pos} role="menu" onContextMenu={(e) => e.preventDefault()}>
      <div className="cm-title">{chat.title}</div>
      <div className="cm-swatches">
        {CHAT_COLORS.filter((c) => c !== PASSIVE).map((c) => (
          <button key={c} className={`swatch ${c === chat.color ? "on" : ""}`} style={{ background: `var(--c-${c})` }} onClick={act(() => onColor(c))} title={c} aria-label={c} />
        ))}
        <button className={`swatch none ${chat.color ? "" : "on"}`} onClick={act(() => onColor(null))} title="No colour" aria-label="No colour" />
      </div>
      {passive
        ? <button className="cm-item" role="menuitem" onClick={act(onWake)}>Make active</button>
        : <button className="cm-item" role="menuitem" onClick={act(() => onColor(PASSIVE))}>Make passive</button>}
      {renaming ? (
        <input
          className="cm-rename"
          autoFocus
          defaultValue={chat.title}
          onFocus={(e) => e.currentTarget.select()}
          onKeyDown={(e) => {
            if (e.key === "Enter") { const t = e.currentTarget.value.trim(); if (t && t !== chat.title) onRename(t); close(); }
            if (e.key === "Escape") { e.stopPropagation(); setRenaming(false); }
          }}
        />
      ) : (
        <button className="cm-item" role="menuitem" onClick={() => setRenaming(true)}>Rename</button>
      )}
      <button className="cm-item" role="menuitem" onClick={act(() => onArchive(!chat.archived))}>{chat.archived ? "Unarchive" : "Archive"}</button>
    </div>
  );
}
