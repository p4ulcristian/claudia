// The pill in the chat header: background agents and file changes as counts, each
// opening its panel in a dropdown under the pill.
import React, { useEffect, useRef, useState } from "react";
import { TasksPanel } from "./tasks";
import { ChangesPanel } from "./changes";
import type { ChangeMeta, Item, Task } from "./types";

type Which = "tasks" | "changes";

export function ActivityPill(props: {
  chat: string;
  tasks: Task[];
  subs: Record<string, Item[]>;
  changes: ChangeMeta[];
  changesLabel: string;
  folder: string | null;
  onStop: (id: string) => void;
  onTimeline: () => void;
}) {
  const [open, setOpen] = useState<Which | null>(null);
  const box = useRef<HTMLDivElement>(null);

  // What Claude started (an agent's own commands sit under it), as the Background panel counts them.
  const seen = props.tasks.filter((t) => !t.ambient);
  const ids = new Set(seen.map((t) => t.id));
  const top = seen.filter((t) => !t.parentId || !ids.has(t.parentId));
  const running = top.filter((t) => t.status === "running").length;
  const failed = top.filter((t) => t.status === "failed").length;
  const done = top.length - running - failed;
  const files = props.changes.length;
  const added = props.changes.reduce((n, c) => n + c.added, 0);
  const removed = props.changes.reduce((n, c) => n + c.removed, 0);

  useEffect(() => setOpen(null), [props.chat]);
  useEffect(() => {
    if ((open === "tasks" && !top.length) || (open === "changes" && !files)) setOpen(null);
  }, [open, top.length, files]);
  useEffect(() => {
    if (!open) return;
    const down = (e: PointerEvent) => !box.current?.contains(e.target as Node) && setOpen(null);
    const key = (e: KeyboardEvent) => e.key === "Escape" && (e.stopPropagation(), setOpen(null));
    document.addEventListener("pointerdown", down);
    window.addEventListener("keydown", key, true);
    return () => {
      document.removeEventListener("pointerdown", down);
      window.removeEventListener("keydown", key, true);
    };
  }, [open]);

  if (!top.length && !files) return null;
  const toggle = (w: Which) => setOpen(open === w ? null : w);
  const agentTitle = [running && `${running} running`, failed && `${failed} failed`, done && `${done} finished`].filter(Boolean).join(" · ");

  return (
    <div className="act" ref={box}>
      <div className="act-pill">
        {top.length > 0 && (
          <button className={`act-part ${open === "tasks" ? "on" : ""}`} onClick={() => toggle("tasks")} title={`Background: ${agentTitle}`}>
            <AgentsIcon />
            {running > 0 && <span className="act-run"><span className="act-dot" />{running}</span>}
            {failed > 0 && <span className="act-fail">{failed}</span>}
            {!running && !failed && <span className="act-done">{done}</span>}
          </button>
        )}
        {top.length > 0 && files > 0 && <span className="act-sep" />}
        {files > 0 && (
          <button className={`act-part ${open === "changes" ? "on" : ""}`} onClick={() => toggle("changes")} title={`Changes · ${props.changesLabel}: ${files} ${files === 1 ? "file" : "files"}, +${added} −${removed}`}>
            <FileIcon />
            <span>{files}</span>
            <span className="plus">+{added}</span>
            <span className="minus">−{removed}</span>
          </button>
        )}
      </div>
      {open && (
        <div className="act-pop">
          {open === "tasks" ? (
            <TasksPanel chat={props.chat} tasks={props.tasks} subs={props.subs} onStop={props.onStop} onTimeline={() => { setOpen(null); props.onTimeline(); }} />
          ) : (
            <ChangesPanel chat={props.chat} changes={props.changes} folder={props.folder} label={props.changesLabel} alwaysOpen />
          )}
        </div>
      )}
    </div>
  );
}

function AgentsIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" aria-hidden="true">
      <circle cx="5" cy="5" r="2.2" />
      <circle cx="11.5" cy="6.5" r="1.8" />
      <path d="M1.5 13.5c0-2.2 1.6-3.8 3.5-3.8s3.5 1.6 3.5 3.8M9.5 13c.2-1.7 1.2-2.9 2.6-2.9 1.4 0 2.4 1.2 2.4 2.9" />
    </svg>
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
