// Which of a chat's background tasks are work Claude left running (decision 25), for the runner (the
// rail's "agents working" state, the hub's pushes) and the page (the agent chips) alike: no imports,
// so the page can bundle it.
import type { Task } from "./chat";

// A shell command (Bash), as opposed to a subagent, a workflow or a monitor.
export const isShell = (t: Pick<Task, "kind">) => /bash|shell/i.test(t.kind);

// The task each one belongs to: an agent's own shells and agents are part of it, up to one Claude
// started itself (a parent no longer known counts as none).
export function topOf(t: Task, byId: Map<string, Task>): Task {
  let at = t;
  for (let n = 0; n < 20 && at.parentId && byId.has(at.parentId); n++) at = byId.get(at.parentId)!;
  return at;
}

// The tasks Claude started that are still at work: running themselves or through one of their own.
// Housekeeping (ambient) is left out, and so are shells in the foreground, which are part of a tool
// call of Claude's turn.
export function workingTops(tasks: Iterable<Task>): Task[] {
  const all = [...tasks].filter((t) => !t.ambient);
  const byId = new Map(all.map((t) => [t.id, t]));
  const out = new Map<string, Task>();
  for (const t of all) {
    if (t.status !== "running" || (isShell(t) && !t.background)) continue;
    const top = topOf(t, byId);
    out.set(top.id, top);
  }
  return [...out.values()];
}
