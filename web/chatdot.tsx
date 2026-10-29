// A chat's circle, the same on the rail and in the chat list: the project's face, and its state
// (waiting on you, working, agents still working after the turn, done since you looked).
import React, { useState } from "react";
import { AgentsRing } from "./agents";
import type { Api } from "./api";
import type { ChatRow } from "./types";

// A chalked badge on a chat's circle: waiting on your answer, working, or finished since you looked.
const BADGES = {
  waiting: { mark: "?", label: "Waiting for you" },
  thinking: { mark: "", label: "Working" },
  done: { mark: "✓", label: "Done" },
};

function StateBadge({ state }: { state: keyof typeof BADGES | null }) {
  if (!state) return null;
  const b = BADGES[state];
  return (
    <span className={`state-badge ${state}`} title={b.label}>
      {state === "thinking" ? <><i /><i /><i /></> : b.mark}
    </span>
  );
}

// Inside a rail circle: the project's logo, else its short name; a chat without a project, a dot.
// (The chat's title and, on the hub, its server's name sit under the circle.) api: the chat's server.
function DotFace({ project, tag, logo, api }: { project: string | null; tag: string; logo: boolean; api: Api }) {
  const [broken, setBroken] = useState(false);
  if (project && logo && !broken)
    return <img className="rail-logo" src={api.src(`/api/projects/${encodeURIComponent(project)}/logo`)} alt="" onError={() => setBroken(true)} />;
  if (!project) return <span className="rail-blank" aria-hidden="true" />;
  return <span className="rail-tag">{tag}</span>;
}

// Subagents still working while Claude's turn runs: small chalk circles around the circle's left and bottom, one each (up to 5).
function AgentMoons({ n }: { n: number }) {
  if (!n) return null;
  const shown = Math.min(n, 5);
  return (
    <span className="moons" title={`${n} agent${n > 1 ? "s" : ""} working`}>
      {Array.from({ length: shown }, (_, i) => {
        const a = ((160 + i * 32) * Math.PI) / 180;
        return <i key={i} style={{ left: `calc(50% + ${Math.cos(a) * 21}px)`, top: `calc(50% - ${Math.sin(a) * 21}px)`, animationDelay: `${i * 0.25}s` }} />;
      })}
    </span>
  );
}

// tag: the project's short name (projectTags); done: finished since you looked (never in a read-only view).
export function ChatDot({ c, tag, api, done }: { c: ChatRow; tag: string; api: Api; done: boolean }) {
  return (
    <span className="rail-dot">
      <DotFace project={c.project} tag={tag} logo={c.logo} api={api} />
      {!c.waiting && !c.running && (c.agents > 0 || c.settling) ? <AgentsRing n={c.agents} /> : <StateBadge state={c.waiting ? "waiting" : c.running ? "thinking" : done ? "done" : null} />}
      <AgentMoons n={c.running ? c.agents : 0} />
    </span>
  );
}

// Short tags for projects, unique among the ones in the list: initials of a dashed name
// (eye-on-rainbow → eor), else the first letters, three when two projects would share two.
export function projectTags(names: (string | null)[]): Map<string, string> {
  const all = [...new Set(names.filter((n): n is string => !!n))];
  const tag = (n: string, len: number) => {
    const parts = n.split(/[-_. ]+/).filter(Boolean);
    return (parts.length > 1 ? parts.map((p) => p[0]).join("").slice(0, 3) : n.slice(0, len)).toLowerCase();
  };
  const out = new Map<string, string>();
  for (const n of all) {
    const clash = all.some((m) => m !== n && tag(m, 2) === tag(n, 2));
    out.set(n, tag(n, clash ? 3 : 2));
  }
  return out;
}

export function ago(t: number) {
  const s = (Date.now() - t) / 1000;
  if (s < 60) return "now";
  if (s < 3600) return `${Math.floor(s / 60)}m`;
  if (s < 86400) return `${Math.floor(s / 3600)}h`;
  if (s < 86400 * 7) return `${Math.floor(s / 86400)}d`;
  return new Date(t).toLocaleDateString(undefined, { month: "short", day: "numeric" });
}
