// The pill in the chat header: this turn's file changes as a count, opening the Changes panel in a
// dropdown under the pill. (Background agents are chips under the title since decision 25: agents.tsx.)
import React, { useEffect, useRef, useState } from "react";
import { ChangesPanel } from "./changes";
import type { ChangeMeta } from "./types";

export function ActivityPill(props: {
  chat: string;
  changes: ChangeMeta[];
  changesLabel: string;
  folder: string | null;
}) {
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  const files = props.changes.length;
  const added = props.changes.reduce((n, c) => n + c.added, 0);
  const removed = props.changes.reduce((n, c) => n + c.removed, 0);

  useEffect(() => setOpen(false), [props.chat]);
  useEffect(() => {
    if (open && !files) setOpen(false);
  }, [open, files]);
  useEffect(() => {
    if (!open) return;
    const down = (e: PointerEvent) => !box.current?.contains(e.target as Node) && setOpen(false);
    const key = (e: KeyboardEvent) => e.key === "Escape" && (e.stopPropagation(), setOpen(false));
    document.addEventListener("pointerdown", down);
    window.addEventListener("keydown", key, true);
    return () => {
      document.removeEventListener("pointerdown", down);
      window.removeEventListener("keydown", key, true);
    };
  }, [open]);

  if (!files) return null;
  return (
    <div className="act" ref={box}>
      <div className="act-pill">
        <button className={`act-part ${open ? "on" : ""}`} onClick={() => setOpen(!open)} title={`Changes · ${props.changesLabel}: ${files} ${files === 1 ? "file" : "files"}, +${added} −${removed}`}>
          <FileIcon />
          <span>{files}</span>
          <span className="plus">+{added}</span>
          <span className="minus">−{removed}</span>
        </button>
      </div>
      {open && (
        <div className="act-pop">
          <ChangesPanel chat={props.chat} changes={props.changes} folder={props.folder} label={props.changesLabel} alwaysOpen />
        </div>
      )}
    </div>
  );
}

function FileIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round" aria-hidden="true">
      <path d="M3.5 1.5h6l3 3v10h-9z" />
      <path d="M9.5 1.5v3h3M6 8.5h4M8 6.5v4M6 12.5h4" strokeLinecap="round" />
    </svg>
  );
}
