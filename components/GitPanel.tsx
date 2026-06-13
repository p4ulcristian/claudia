"use client";

import { useEffect, useState } from "react";
import type { GitData } from "@/lib/types";
import { getGit } from "./api";
import { FontAwesomeIcon, faCodeBranch, faXmark } from "./icons";

// code-server (VS Code online). It only supports ?folder= / ?workspace= — there
// is no URL param to open an individual file, so file links open the repo folder.
const CODE_SERVER = "https://code.irisdoes.work";
const codeUrl = (folder: string) =>
  `${CODE_SERVER}/?folder=${encodeURIComponent(folder)}`;

// Graph geometry (ported from gitgud).
const ROW_H = 52;
const LANE_W = 22;
const PAD = 14;
const LANE_COLORS = [
  "#3fb950",
  "#2f81f7",
  "#a371f7",
  "#d29922",
  "#f85149",
  "#39c5cf",
  "#db61a2",
];

function statusClass(x: string, y: string): string {
  if (x === "?" || y === "?") return "untracked";
  if (x !== " ") return "staged";
  return "unstaged";
}

export default function GitPanel({
  folder,
  onClose,
}: {
  folder: string;
  onClose: () => void;
}) {
  const [data, setData] = useState<GitData | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    setData(null);
    setError(null);
    getGit(folder)
      .then((d) => live && setData(d))
      .catch((e) => live && setError(e instanceof Error ? e.message : String(e)));
    return () => {
      live = false;
    };
  }, [folder]);

  const commits = data?.commits ?? [];
  const maxCol = commits.reduce((m, c) => Math.max(m, c.col), 0);
  const graphW = (maxCol + 1) * LANE_W + PAD;
  const rowOf: Record<string, number> = {};
  commits.forEach((c, i) => (rowOf[c.hash] = i));
  const nodeX = (col: number) => PAD / 2 + col * LANE_W + LANE_W / 2;
  const nodeY = (i: number) => i * ROW_H + ROW_H / 2;

  const files = data?.status.files ?? [];

  return (
    <div className="git-backdrop" onClick={onClose}>
      <div className="git-shelf" onClick={(e) => e.stopPropagation()}>
        <div className="git-shelf-head">
          <FontAwesomeIcon icon={faCodeBranch} />
          <span className="git-repo-name">{data?.repo.name ?? "…"}</span>
          {data?.currentBranch && (
            <span className="ref head">⎇ {data.currentBranch}</span>
          )}
          {data?.detached && <span className="ref detached">detached HEAD</span>}
          {data && (data.status.ahead > 0 || data.status.behind > 0) && (
            <span className="git-aheadbehind">
              {data.status.ahead > 0 && <>↑{data.status.ahead}</>}
              {data.status.behind > 0 && <>↓{data.status.behind}</>}
            </span>
          )}
          <span className="spacer" />
          <a
            className="btn ghost"
            href={codeUrl(folder)}
            target="_blank"
            rel="noreferrer"
          >
            Open in VS Code ↗
          </a>
          <button className="icon-btn" onClick={onClose} title="Close">
            <FontAwesomeIcon icon={faXmark} />
          </button>
        </div>

        <div className="git-shelf-body">
          {error && <div className="error mono">error: {error}</div>}
          {data?.error && <div className="git-empty">{data.error}</div>}

          {!data && !error && <div className="git-empty">Loading…</div>}

          {data && !data.error && (
            <>
              <div className="git-section-title">
                Working tree{" "}
                {files.length > 0 && <span className="git-dim">({files.length})</span>}
              </div>
              {files.length === 0 ? (
                <div className="git-clean">Clean working tree</div>
              ) : (
                <div className="gw-files">
                  {files.map((f) => (
                    <a
                      key={f.path}
                      className="gw-file"
                      href={codeUrl(folder)}
                      target="_blank"
                      rel="noreferrer"
                      title="Open repo in VS Code"
                    >
                      <span className={"gw-badge " + statusClass(f.x, f.y)}>
                        {(f.x + f.y).replace(/ /g, "") || "·"}
                      </span>
                      <span className="gw-path">{f.path}</span>
                    </a>
                  ))}
                </div>
              )}

              <div className="git-section-title">Commits</div>
              {commits.length === 0 ? (
                <div className="git-empty">No commits yet.</div>
              ) : (
                <div className="graph" style={{ position: "relative" }}>
                  <svg
                    width={graphW}
                    height={commits.length * ROW_H}
                    style={{ position: "absolute", left: 0, top: 0 }}
                  >
                    {commits.map((c, i) =>
                      c.parents.map((p) => {
                        if (rowOf[p] === undefined) return null;
                        const j = rowOf[p];
                        const x1 = nodeX(c.col);
                        const y1 = nodeY(i);
                        const x2 = nodeX(commits[j].col);
                        const y2 = nodeY(j);
                        const midY = (y1 + y2) / 2;
                        return (
                          <path
                            key={c.hash + p}
                            d={`M ${x1} ${y1} C ${x1} ${midY}, ${x2} ${midY}, ${x2} ${y2}`}
                            stroke={LANE_COLORS[commits[j].col % LANE_COLORS.length]}
                            strokeWidth="2"
                            fill="none"
                          />
                        );
                      }),
                    )}
                    {commits.map((c, i) => {
                      const isHead = c.hash === data.head;
                      return (
                        <circle
                          key={c.hash}
                          cx={nodeX(c.col)}
                          cy={nodeY(i)}
                          r={isHead ? 7 : 5}
                          fill={isHead ? "#ffcf7a" : "#0d0b13"}
                          stroke={LANE_COLORS[c.col % LANE_COLORS.length]}
                          strokeWidth={isHead ? 3 : 2}
                        />
                      );
                    })}
                  </svg>

                  {commits.map((c) => {
                    const isHead = c.hash === data.head;
                    return (
                      <div className="git-row" key={c.hash}>
                        <div className="lanes" style={{ width: graphW }} />
                        <div className={"commit-box" + (isHead ? " is-head" : "")}>
                          <div className="subject">
                            {c.refs.length > 0 && (
                              <span className="refs">
                                {c.refs.map((r) => (
                                  <span key={r.name} className={"ref " + r.type}>
                                    {r.type === "head" ? "⎇ " : ""}
                                    {r.name}
                                  </span>
                                ))}
                              </span>
                            )}
                            {c.subject}
                          </div>
                          <div className="meta">
                            <span className="hash">{c.hash.slice(0, 8)}</span> ·{" "}
                            {c.author} · {c.when}
                          </div>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
}
