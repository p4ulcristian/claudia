// Background work on the right: subagents and shell tasks, live, each one openable.
import React, { useEffect, useState } from "react";
import { Transcript } from "./chat";
import type { Item, Task } from "./types";

function since(t: Task) {
  const s = Math.max(0, Math.floor(((t.endedAt ?? Date.now()) - t.startedAt) / 1000));
  return s < 60 ? `${s}s` : `${Math.floor(s / 60)}m ${s % 60}s`;
}

function useTick(on: boolean) {
  const [, set] = useState(0);
  useEffect(() => {
    if (!on) return;
    const t = setInterval(() => set((n) => n + 1), 1000);
    return () => clearInterval(t);
  }, [on]);
}

function Output({ chat, task }: { chat: string; task: Task }) {
  const [text, setText] = useState<string | null>(null);
  useEffect(() => {
    let dead = false;
    const load = () =>
      fetch(`/api/chats/${chat}/tasks/${task.id}/output`).then((r) => r.json()).then((j) => !dead && setText(j.text || ""));
    load();
    const t = task.status === "running" ? setInterval(load, 2000) : undefined;
    return () => { dead = true; clearInterval(t); };
  }, [chat, task.id, task.status, task.outputFile]);
  if (text === null) return <p className="muted small">Loading…</p>;
  if (!text) return <p className="muted small">{task.outputFile ? "No output yet." : "Output shows here when the task reports its file."}</p>;
  return <pre className="task-out">{text}</pre>;
}

function TaskRow({ chat, task, items, onStop }: { chat: string; task: Task; items?: Item[]; onStop: () => void }) {
  const [open, setOpen] = useState(false);
  const running = task.status === "running";
  const isAgent = !!items?.length || !/shell|bash|local/i.test(task.kind);
  return (
    <li className={`task ${task.status}`}>
      <div className="task-row">
        <button className="task-main" onClick={() => setOpen(!open)}>
          <span className={`chev ${open ? "open" : ""}`}>›</span>
          <span className="task-dot" />
          <span className="task-desc">{task.description}</span>
          <span className="chip" title={task.kind}>{isAgent ? "agent" : "shell"}</span>
        </button>
        <span className="task-meta muted small">
          {since(task)}
          {task.toolUses ? ` · ${task.toolUses} tools` : ""}
          {running && task.lastTool ? ` · ${task.lastTool}` : ""}
        </span>
        {running && <button className="task-stop" onClick={onStop} title="Stop this task">Stop</button>}
      </div>
      {open && (
        <div className="task-body">
          {isAgent && items?.length ? <Transcript items={items} live={running} /> : null}
          {task.summary && !items?.length && <p className="small">{task.summary}</p>}
          {!isAgent && <Output chat={chat} task={task} />}
          {isAgent && !items?.length && !task.summary && <p className="muted small">Messages show here while it works.</p>}
        </div>
      )}
    </li>
  );
}

export function TasksPanel({ chat, tasks, subs, onStop }: { chat: string; tasks: Task[]; subs: Record<string, Item[]>; onStop: (id: string) => void }) {
  const running = tasks.filter((t) => t.status === "running");
  const [open, setOpen] = useState(true);
  useTick(running.length > 0);
  // Running first, then the most recent finished ones.
  const shown = [...running, ...tasks.filter((t) => t.status !== "running").sort((a, b) => (b.endedAt ?? 0) - (a.endedAt ?? 0)).slice(0, 6)];
  return (
    <section className="panel tasks">
      <button className="tasks-head" onClick={() => setOpen(!open)}>
        <span className={`chev ${open ? "open" : ""}`}>›</span>
        <b>Background</b>
        <span className="muted small">{running.length ? `${running.length} running` : "nothing running"}</span>
      </button>
      {open && (
        <ul>
          {shown.map((t) => <TaskRow key={t.id} chat={chat} task={t} items={t.toolUseId ? subs[t.toolUseId] : undefined} onStop={() => onStop(t.id)} />)}
        </ul>
      )}
    </section>
  );
}
