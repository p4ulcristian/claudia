// A background shell's output, live (the agent view's Output tab, web/agents.tsx).
import React, { useEffect, useState } from "react";
import { useApi } from "./api";
import type { Task } from "./types";

export function Output({ chat, task }: { chat: string; task: Task }) {
  const [text, setText] = useState<string | null>(null);
  const api = useApi();
  useEffect(() => {
    let dead = false;
    const load = () =>
      api.fetch(`/api/chats/${chat}/tasks/${task.id}/output`).then((r) => r.json()).then((j) => !dead && setText(j.text || ""));
    load();
    const t = task.status === "running" ? setInterval(load, 2000) : undefined;
    return () => { dead = true; clearInterval(t); };
  }, [api, chat, task.id, task.status, task.outputFile]);
  if (text === null) return <p className="muted small">Loading…</p>;
  if (!text) return <p className="muted small">{task.outputFile ? "No output yet." : "Output shows here when the task reports its file."}</p>;
  return <pre className="task-out">{text}</pre>;
}
