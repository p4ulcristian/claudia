"use client";

import { useEffect, useLayoutEffect, useRef, useState } from "react";
import type {
  GitCommitDetail,
  GitData,
  GitFileDiff,
  GitStatusFile,
} from "@/lib/types";
import {
  getCommit,
  getCommitFileDiff,
  getGit,
  getWorktreeFileDiff,
} from "./api";
import {
  FontAwesomeIcon,
  faArrowLeft,
  faCodeBranch,
  faUpRightFromSquare,
  faXmark,
} from "./icons";

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
  const [selected, setSelected] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    setData(null);
    setError(null);
    setSelected(null);
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

  // Commit rows are variable height (wrapping subjects, ref pills), so the SVG
  // can't assume a fixed pitch — measure each row's real center and total
  // height after layout, or the lowest commits fall outside the canvas and
  // lose their node + line. Re-measured on any resize.
  const graphRef = useRef<HTMLDivElement | null>(null);
  const rowRefs = useRef<(HTMLDivElement | null)[]>([]);
  const [geom, setGeom] = useState<{ ys: number[]; h: number }>({ ys: [], h: 0 });

  useLayoutEffect(() => {
    const measure = () => {
      const graph = graphRef.current;
      if (!graph) return;
      const top = graph.getBoundingClientRect().top;
      const ys = commits.map((_, i) => {
        const el = rowRefs.current[i];
        if (!el) return i * ROW_H + ROW_H / 2;
        const r = el.getBoundingClientRect();
        return r.top - top + r.height / 2;
      });
      const last = rowRefs.current[commits.length - 1];
      const h = last
        ? last.getBoundingClientRect().bottom - top
        : commits.length * ROW_H;
      setGeom({ ys, h });
    };
    measure();
    const ro = new ResizeObserver(measure);
    if (graphRef.current) ro.observe(graphRef.current);
    return () => ro.disconnect();
  }, [data]);

  // Measured row center, with a fixed-pitch fallback before the first measure.
  const nodeY = (i: number) => geom.ys[i] ?? i * ROW_H + ROW_H / 2;
  const svgHeight = geom.h || commits.length * ROW_H;

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
                <WorktreeFiles folder={folder} files={files} />
              )}

              <div className="git-section-title">Commits</div>
              {commits.length === 0 ? (
                <div className="git-empty">No commits yet.</div>
              ) : (
                <div className="graph" ref={graphRef} style={{ position: "relative" }}>
                  <svg
                    width={graphW}
                    height={svgHeight}
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

                  {commits.map((c, i) => {
                    const isHead = c.hash === data.head;
                    return (
                      <div
                        className="git-row"
                        key={c.hash}
                        ref={(el) => {
                          rowRefs.current[i] = el;
                        }}
                      >
                        <div className="lanes" style={{ width: graphW }} />
                        <button
                          type="button"
                          className={"commit-box" + (isHead ? " is-head" : "")}
                          onClick={() => setSelected(c.hash)}
                        >
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
                        </button>
                      </div>
                    );
                  })}
                </div>
              )}
            </>
          )}
        </div>

        {selected && (
          <CommitDetail
            folder={folder}
            hash={selected}
            onBack={() => setSelected(null)}
          />
        )}
      </div>
    </div>
  );
}

// The working-tree file list. Each row expands its uncommitted diff inline
// (lazy-loaded on first open); a trailing icon opens the repo in code-server.
function WorktreeFiles({
  folder,
  files,
}: {
  folder: string;
  files: GitStatusFile[];
}) {
  const [open, setOpen] = useState<string | null>(null);
  const [diffs, setDiffs] = useState<Record<string, GitFileDiff | "loading">>(
    {},
  );

  function toggle(f: GitStatusFile) {
    if (open === f.path) {
      setOpen(null);
      return;
    }
    setOpen(f.path);
    if (!diffs[f.path]) {
      setDiffs((d) => ({ ...d, [f.path]: "loading" }));
      const untracked = f.x === "?" || f.y === "?";
      getWorktreeFileDiff(folder, f.path, untracked)
        .then((fd) => setDiffs((d) => ({ ...d, [f.path]: fd })))
        .catch((e) =>
          setDiffs((d) => ({
            ...d,
            [f.path]: { path: f.path, patch: `error: ${e}`, binary: false },
          })),
        );
    }
  }

  return (
    <div className="cd-files">
      {files.map((f) => {
        const d = diffs[f.path];
        const isOpen = open === f.path;
        return (
          <div key={f.path} className="cd-file">
            <div className="gw-file-row">
              <button
                type="button"
                className="cd-file-row gw-file-btn"
                onClick={() => toggle(f)}
              >
                <span className={"gw-badge " + statusClass(f.x, f.y)}>
                  {(f.x + f.y).replace(/ /g, "") || "·"}
                </span>
                <span className="gw-path">{f.path}</span>
              </button>
              <a
                className="icon-btn gw-open"
                href={codeUrl(folder)}
                target="_blank"
                rel="noreferrer"
                title="Open repo in VS Code"
              >
                <FontAwesomeIcon icon={faUpRightFromSquare} />
              </a>
            </div>
            {isOpen && (
              <div className="cd-diff">
                {d === "loading" || !d ? (
                  <div className="git-empty">Loading diff…</div>
                ) : d.binary ? (
                  <div className="git-empty">Binary file.</div>
                ) : d.patch.trim() === "" ? (
                  <div className="git-empty">No textual changes.</div>
                ) : (
                  <DiffView patch={d.patch} />
                )}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

function statusBadge(s: string): string {
  if (s === "A") return "staged";
  if (s === "D") return "untracked";
  return "unstaged";
}

// Slides over the graph: a commit's message + the files it touched, each
// expanding to its unified diff (lazy-loaded on first open).
function CommitDetail({
  folder,
  hash,
  onBack,
}: {
  folder: string;
  hash: string;
  onBack: () => void;
}) {
  const [detail, setDetail] = useState<GitCommitDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState<string | null>(null);
  const [diffs, setDiffs] = useState<Record<string, GitFileDiff | "loading">>({});

  useEffect(() => {
    let live = true;
    setDetail(null);
    setError(null);
    setOpen(null);
    setDiffs({});
    getCommit(folder, hash)
      .then((d) => live && setDetail(d))
      .catch((e) => live && setError(e instanceof Error ? e.message : String(e)));
    return () => {
      live = false;
    };
  }, [folder, hash]);

  function toggle(path: string) {
    if (open === path) {
      setOpen(null);
      return;
    }
    setOpen(path);
    if (!diffs[path]) {
      setDiffs((d) => ({ ...d, [path]: "loading" }));
      getCommitFileDiff(folder, hash, path)
        .then((fd) => setDiffs((d) => ({ ...d, [path]: fd })))
        .catch((e) =>
          setDiffs((d) => ({
            ...d,
            [path]: { path, patch: `error: ${e}`, binary: false },
          })),
        );
    }
  }

  return (
    <div className="git-detail">
      <div className="git-detail-head">
        <button className="icon-btn" onClick={onBack} title="Back">
          <FontAwesomeIcon icon={faArrowLeft} />
        </button>
        <span className="hash">{hash.slice(0, 8)}</span>
        {detail?.isMerge && <span className="ref detached">merge</span>}
        <span className="spacer" />
      </div>

      <div className="git-detail-body">
        {error && <div className="error mono">error: {error}</div>}
        {!detail && !error && <div className="git-empty">Loading…</div>}

        {detail && (
          <>
            <div className="cd-subject">{detail.subject}</div>
            {detail.body && <pre className="cd-body">{detail.body}</pre>}
            <div className="cd-meta">
              {detail.author} &lt;{detail.authorEmail}&gt; · {detail.date} ·{" "}
              {detail.when}
            </div>
            {detail.isMerge && (
              <div className="git-dim cd-mergenote">
                Showing changes against the first parent.
              </div>
            )}

            <div className="git-section-title">
              Files <span className="git-dim">({detail.files.length})</span>
            </div>
            {detail.files.length === 0 ? (
              <div className="git-empty">No file changes.</div>
            ) : (
              <div className="cd-files">
                {detail.files.map((f) => {
                  const d = diffs[f.path];
                  const isOpen = open === f.path;
                  return (
                    <div key={f.path} className="cd-file">
                      <button
                        type="button"
                        className="cd-file-row"
                        onClick={() => toggle(f.path)}
                      >
                        <span className={"gw-badge " + statusBadge(f.status)}>
                          {f.status}
                        </span>
                        <span className="gw-path">
                          {f.oldPath && (
                            <span className="cd-oldpath">{f.oldPath} → </span>
                          )}
                          {f.path}
                        </span>
                        <span className="cd-counts">
                          {f.additions >= 0 && (
                            <span className="cd-add">+{f.additions}</span>
                          )}
                          {f.deletions >= 0 && (
                            <span className="cd-del">−{f.deletions}</span>
                          )}
                        </span>
                      </button>
                      {isOpen && (
                        <div className="cd-diff">
                          {d === "loading" || !d ? (
                            <div className="git-empty">Loading diff…</div>
                          ) : d.binary ? (
                            <div className="git-empty">Binary file.</div>
                          ) : (
                            <DiffView patch={d.patch} />
                          )}
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}

// Render a unified diff: skip the git/index/--- /+++ header noise, color the
// hunk headers and +/- lines.
function DiffView({ patch }: { patch: string }) {
  const lines = patch.split("\n");
  const start = lines.findIndex((l) => l.startsWith("@@"));
  const body = start === -1 ? lines : lines.slice(start);
  return (
    <pre className="diff">
      {body.map((line, i) => {
        let cls = "ctx";
        if (line.startsWith("@@")) cls = "hunk";
        else if (line.startsWith("+")) cls = "add";
        else if (line.startsWith("-")) cls = "del";
        return (
          <span key={i} className={"diff-line " + cls}>
            {line || " "}
          </span>
        );
      })}
    </pre>
  );
}
