"use client";

import {
  createContext,
  memo,
  useContext,
  useEffect,
  useRef,
  useState,
} from "react";
import { type DisplayItem, type ToolUseBlock } from "./fold";
import Markdown from "./Markdown";
import {
  FontAwesomeIcon,
  faAnglesDown,
  faChevronDown,
  faChevronRight,
  faCircleQuestion,
  faClock,
  faCode,
  faCompress,
  faFileLines,
  faGear,
  faPencil,
  faSpinner,
  faTerminal,
  faXmark,
} from "./icons";

// Lets deeply-nested tool cards request the inline editor open a file without
// prop-drilling through the memoized Item rows. Null when no handler is wired.
const OpenFileContext = createContext<((path: string) => void) | null>(null);

// Collapse whitespace and clip to a single short line for the header hint.
function truncate(s: string, n = 72): string {
  const line = s.replace(/\s+/g, " ").trim();
  return line.length > n ? line.slice(0, n - 1) + "…" : line;
}

function basename(p: string): string {
  const i = p.replace(/\/+$/, "").lastIndexOf("/");
  return i >= 0 ? p.slice(i + 1) : p;
}

// ---- diff rendering for Edit / MultiEdit / Write ----
type DiffRow = { type: "ctx" | "add" | "del"; text: string };
interface DiffSection {
  rows: DiffRow[];
}

// Line-level diff via longest common subsequence. Edit strings are small, so the
// O(n*m) table is cheap; unchanged lines render as context, the rest as ±.
function lineDiff(a: string, b: string): DiffRow[] {
  const aL = a.split("\n");
  const bL = b.split("\n");
  const n = aL.length;
  const m = bL.length;
  const dp: number[][] = Array.from({ length: n + 1 }, () => new Array(m + 1).fill(0));
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      dp[i][j] = aL[i] === bL[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
    }
  }
  const rows: DiffRow[] = [];
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (aL[i] === bL[j]) {
      rows.push({ type: "ctx", text: aL[i++] });
      j++;
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      rows.push({ type: "del", text: aL[i++] });
    } else {
      rows.push({ type: "add", text: bL[j++] });
    }
  }
  while (i < n) rows.push({ type: "del", text: aL[i++] });
  while (j < m) rows.push({ type: "add", text: bL[j++] });
  return rows;
}

// Turn a parsed Edit/MultiEdit/Write input into diff sections, or null if the
// input hasn't streamed far enough to hold the strings yet.
function buildDiffSections(name: string, o: Record<string, unknown>): DiffSection[] | null {
  if (name === "Write") {
    if (typeof o.content !== "string") return null;
    return [{ rows: o.content.split("\n").map((text) => ({ type: "add", text })) }];
  }
  if (name === "MultiEdit") {
    if (!Array.isArray(o.edits)) return null;
    const sections: DiffSection[] = [];
    for (const e of o.edits) {
      const eo = (e ?? {}) as Record<string, unknown>;
      if (typeof eo.old_string !== "string" || typeof eo.new_string !== "string") continue;
      sections.push({ rows: lineDiff(eo.old_string, eo.new_string) });
    }
    return sections.length ? sections : null;
  }
  // Edit
  if (typeof o.old_string !== "string" || typeof o.new_string !== "string") return null;
  return [{ rows: lineDiff(o.old_string, o.new_string) }];
}

function pickSummary(o: Record<string, unknown>): string {
  const pick =
    o.description ?? o.file_path ?? o.path ?? o.pattern ?? o.command ?? o.query ?? o.url ?? o.prompt;
  return typeof pick === "string" ? pick : "";
}

// Per-tool presentation: an icon, a short header summary, and what to reveal
// when expanded. Bash shows its `description` as the summary and the actual
// command as a shell snippet; other tools show their prettified JSON input.
function toolView(
  name: string,
  input: string,
): {
  icon: typeof faGear;
  summary: string;
  command?: string;
  json?: string;
  diff?: DiffSection[];
  /** Absolute path of the file this tool touched, if any — drives the editor button. */
  filePath?: string;
} {
  let parsed: Record<string, unknown> | null = null;
  if (input) {
    try {
      parsed = JSON.parse(input) as Record<string, unknown>;
    } catch {
      /* partial JSON mid-stream */
    }
  }
  const fp = typeof parsed?.file_path === "string" ? parsed.file_path : undefined;
  if (name === "Bash") {
    const command = typeof parsed?.command === "string" ? parsed.command : "";
    const description = typeof parsed?.description === "string" ? parsed.description : "";
    if (command) return { icon: faTerminal, summary: truncate(description || command), command };
    return { icon: faTerminal, summary: "", json: input }; // mid-stream / no command yet
  }
  if (name === "Edit" || name === "MultiEdit" || name === "Write") {
    const diff = parsed ? buildDiffSections(name, parsed) : null;
    const file = fp ? basename(fp) : "";
    // Once the strings have streamed in we show the diff; until then fall back to
    // the raw partial JSON so the card isn't empty mid-stream.
    if (diff) return { icon: faPencil, summary: truncate(file), diff, filePath: fp };
    return { icon: faPencil, summary: truncate(file), json: input, filePath: fp };
  }
  if (name === "Read") {
    return {
      icon: faFileLines,
      summary: fp ? truncate(basename(fp)) : "",
      json: input,
      filePath: fp,
    };
  }
  return { icon: faGear, summary: parsed ? truncate(pickSummary(parsed)) : "", json: input };
}

function DiffBody({ sections }: { sections: DiffSection[] }) {
  return (
    <div className="tool-diff">
      {sections.map((sec, si) => (
        <div className="diff-section" key={si}>
          {sec.rows.map((r, ri) => (
            <div className={`diff-row diff-${r.type}`} key={ri}>
              <span className="diff-gutter">{r.type === "add" ? "+" : r.type === "del" ? "−" : " "}</span>
              <span className="diff-text">{r.text || " "}</span>
            </div>
          ))}
        </div>
      ))}
    </div>
  );
}

function ToolCard({ block }: { block: ToolUseBlock }) {
  const { icon, summary, command, json, diff, filePath } = toolView(
    block.name,
    block.input,
  );
  const openFile = useContext(OpenFileContext);
  const result = block.result;
  const [open, setOpen] = useState(false);
  const hasDetail = Boolean(command || json || diff || result);

  // Diffs and errors are worth seeing at a glance, so auto-open the card the
  // first time one appears — but only once, so a manual collapse afterward sticks.
  const autoOpened = useRef(false);
  const wantOpen = Boolean(diff) || result?.isError === true;
  useEffect(() => {
    if (wantOpen && !autoOpened.current) {
      autoOpened.current = true;
      setOpen(true);
    }
  }, [wantOpen]);

  return (
    <div className={`tool-block ${open ? "is-open" : ""}`}>
      <button className="tool-name" onClick={() => setOpen((v) => !v)}>
        <span className="tool-icon">
          <FontAwesomeIcon icon={icon} />
        </span>
        <span className="tool-label">{block.name}</span>
        {summary ? <span className="tool-summary">{summary}</span> : null}
        {result?.isError ? <span className="tool-err">error</span> : null}
        {filePath && openFile ? (
          <span
            className="tool-open"
            role="button"
            tabIndex={0}
            title="Open in editor"
            onClick={(e) => {
              e.stopPropagation();
              openFile(filePath);
            }}
            onKeyDown={(e) => {
              if (e.key === "Enter" || e.key === " ") {
                e.stopPropagation();
                openFile(filePath);
              }
            }}
          >
            <FontAwesomeIcon icon={faCode} />
          </span>
        ) : null}
        {hasDetail ? (
          <span className="chev">
            <FontAwesomeIcon icon={open ? faChevronDown : faChevronRight} />
          </span>
        ) : null}
      </button>
      {open && diff ? <DiffBody sections={diff} /> : null}
      {open && !diff && command ? <pre className="tool-cmd">{command}</pre> : null}
      {open && !diff && !command && json ? <pre className="tool-input">{json}</pre> : null}
      {open && result ? (
        <div className={`tool-out ${result.isError ? "is-error" : ""}`}>
          <div className="tool-out-label">
            {result.isError ? "error" : "result"}
            <span className="tool-out-meta">
              {result.text ? `${result.text.length} chars` : "empty"}
            </span>
          </div>
          <pre>{result.text || "(empty)"}</pre>
        </div>
      ) : null}
    </div>
  );
}

function AssistantItem({ item }: { item: Extract<DisplayItem, { kind: "assistant" }> }) {
  return (
    <div className="msg msg-assistant">
      <div className="msg-body">
        {item.blocks.map((b, i) =>
          b.type === "text" ? (
            <div key={i} className="text-block">
              <Markdown text={b.text} />
            </div>
          ) : (
            <ToolCard key={i} block={b} />
          ),
        )}
      </div>
    </div>
  );
}

function ToolResult({ item }: { item: Extract<DisplayItem, { kind: "tool_result" }> }) {
  const [open, setOpen] = useState(item.isError);
  const body = item.text || "(empty)";
  const chars = item.text ? `${item.text.length} chars` : "empty";
  return (
    <div className={`tool-result ${item.isError ? "is-error" : ""} ${open ? "is-open" : ""}`}>
      <button className="tool-result-label" onClick={() => setOpen((v) => !v)}>
        {item.isError ? "error" : "result"}
        <span className="tool-result-meta">{chars}</span>
        <span className="chev">
          <FontAwesomeIcon icon={open ? faChevronDown : faChevronRight} />
        </span>
      </button>
      {open ? <pre>{body}</pre> : null}
    </div>
  );
}

function fmtDur(ms: number): string {
  const s = Math.floor(ms / 1000);
  const m = Math.floor(s / 60);
  return m > 0 ? `${m}m ${String(s % 60).padStart(2, "0")}s` : `${s}s`;
}

// Live "compacting…" card with an elapsed timer. While the compaction runs it
// ticks; when the boundary arrives it freezes to the final duration. A card
// that mounts already-done (replayed transcript) shows a plain divider — we
// never observed it live, so we have no duration to report.
function CompactingCard({ item }: { item: Extract<DisplayItem, { kind: "compacting" }> }) {
  const [start] = useState(() => Date.now());
  const [elapsed, setElapsed] = useState(0);
  const [frozen, setFrozen] = useState<number | null>(null);
  const mountedDone = useRef(item.done);

  useEffect(() => {
    if (item.done) return;
    const id = setInterval(() => setElapsed(Date.now() - start), 250);
    return () => clearInterval(id);
  }, [item.done, start]);

  useEffect(() => {
    if (item.done && !mountedDone.current && frozen === null) {
      setFrozen(Date.now() - start);
    }
  }, [item.done, start, frozen]);

  if (item.done) {
    return (
      <div className="compact-divider">
        <span>
          <FontAwesomeIcon icon={faCompress} /> Conversation compacted
          {!mountedDone.current && frozen != null ? ` · ${fmtDur(frozen)}` : ""}
        </span>
      </div>
    );
  }
  return (
    <div className="compacting-card">
      <FontAwesomeIcon icon={faCompress} />
      <span className="compacting-label">Compacting conversation…</span>
      <span className="compacting-timer">{fmtDur(elapsed)}</span>
    </div>
  );
}

function BgTaskCard({ item }: { item: Extract<DisplayItem, { kind: "bgtask" }> }) {
  const running = item.status === "running";
  return (
    <div className={`bgtask-card ${running ? "is-running" : "is-done"}`}>
      <span className="bgtask-ic">
        <FontAwesomeIcon icon={faTerminal} />
      </span>
      <span className="bgtask-desc">{item.description}</span>
      <span className="bgtask-status">
        {running ? (
          <>
            <FontAwesomeIcon icon={faSpinner} spin /> running
          </>
        ) : (
          item.summary || item.status
        )}
      </span>
    </div>
  );
}

function QuestionCard({
  item,
  active,
  onAnswer,
}: {
  item: Extract<DisplayItem, { kind: "question" }>;
  active: boolean;
  onAnswer: (text: string) => void;
}) {
  const [sel, setSel] = useState<Record<number, string[]>>({});
  const [sent, setSent] = useState(false);
  const single = item.questions.length === 1 && !item.questions[0]?.multiSelect;
  const disabled = !active || sent;

  const format = (s: Record<number, string[]>) =>
    item.questions
      .map((q, qi) => `${q.header || q.question}: ${(s[qi] || []).join(", ")}`)
      .join("\n");

  const pick = (qi: number, label: string, multi?: boolean) => {
    if (disabled) return;
    if (single) {
      setSent(true);
      onAnswer(label);
      return;
    }
    setSel((prev) => {
      const cur = new Set(prev[qi] || []);
      if (multi) {
        cur.has(label) ? cur.delete(label) : cur.add(label);
      } else {
        cur.clear();
        cur.add(label);
      }
      return { ...prev, [qi]: [...cur] };
    });
  };

  const allAnswered = item.questions.every((_, qi) => (sel[qi] || []).length > 0);
  const send = () => {
    if (disabled || !allAnswered) return;
    setSent(true);
    onAnswer(format(sel));
  };

  return (
    <div className={`question-card ${disabled ? "is-disabled" : ""}`}>
      <div className="question-head">
        <FontAwesomeIcon icon={faCircleQuestion} />
        <span>{item.questions.length > 1 ? "Questions" : "Question"}</span>
      </div>
      {item.questions.map((q, qi) => (
        <div key={qi} className="question-q">
          {q.header ? <div className="question-label">{q.header}</div> : null}
          <div className="question-text">{q.question}</div>
          <div className="question-opts">
            {q.options.map((op, oi) => {
              const selected = (sel[qi] || []).includes(op.label);
              return (
                <button
                  key={oi}
                  className={`question-opt ${selected ? "selected" : ""}`}
                  disabled={disabled}
                  title={op.description}
                  onClick={() => pick(qi, op.label, q.multiSelect)}
                >
                  {op.label}
                </button>
              );
            })}
          </div>
        </div>
      ))}
      {!single ? (
        <button
          className="btn accent question-send"
          disabled={disabled || !allAnswered}
          onClick={send}
        >
          Send answer
        </button>
      ) : null}
      {sent ? <div className="question-sent muted">answer sent</div> : null}
    </div>
  );
}

// Memoized so a reconcile that reuses an item's object identity (unchanged
// key+sig) skips re-rendering that row entirely — no flash on swap.
const Item = memo(function Item({ item }: { item: DisplayItem }) {
  switch (item.kind) {
    case "user":
      return (
        <div className="msg msg-user">
          <div className="msg-body">{item.text}</div>
        </div>
      );
    case "assistant":
      return <AssistantItem item={item} />;
    case "tool_result":
      return <ToolResult item={item} />;
    case "bgtask":
      return <BgTaskCard item={item} />;
    case "compacting":
      return <CompactingCard item={item} />;
    case "result":
      return (
        <div className={`msg-result ${item.isError ? "is-error" : ""}`}>
          <Markdown text={item.text} />
        </div>
      );
    default:
      return null;
  }
});

// Remembers each session's scroll position for this page-load, so switching back
// to a chat lands where you left it instead of snapping to the bottom.
const scrollPositions = new Map<string, number>();

export default function StreamRenderer({
  items,
  streaming,
  queue,
  sessionId,
  onAnswer,
  onCancelQueued,
  onOpenFile,
}: {
  items: DisplayItem[];
  streaming: boolean;
  queue: string[];
  sessionId: string | null;
  onAnswer: (text: string) => void;
  onCancelQueued: (index: number) => void;
  onOpenFile?: (path: string) => void;
}) {
  const endRef = useRef<HTMLDivElement>(null);
  const rootRef = useRef<HTMLDivElement>(null);
  // Jump instantly on the first paint of a chat; animate only later updates.
  // A smooth scroll on open would visibly travel the whole transcript top→bottom.
  const didInitialScroll = useRef(false);
  // "Stick to bottom": auto-scroll only while the user is pinned at the bottom.
  // Scrolling up pauses it (so a streaming answer doesn't yank the view back);
  // scrolling back down — or sending a turn — re-pins.
  const stuck = useRef(true);
  const prevLen = useRef(0);
  // Last observed scrollTop, so onScroll can tell which way the user moved.
  const lastTop = useRef(0);
  // A remembered scrollTop to re-apply once this session's items have rendered.
  const pendingRestore = useRef<number | null>(null);
  const [showJump, setShowJump] = useState(false);

  // The scrollable ancestor is the .chat-scroll wrapper around this component.
  const scroller = () => rootRef.current?.parentElement ?? null;
  const scrollToBottom = (behavior: ScrollBehavior) => {
    const el = scroller();
    if (el) el.scrollTo({ top: el.scrollHeight, behavior });
  };

  useEffect(() => {
    didInitialScroll.current = false;
    prevLen.current = 0;
    lastTop.current = 0;
    setShowJump(false);
    // If we have a remembered position for this session, restore it once items
    // render; otherwise pin to the bottom as before.
    const saved = sessionId ? scrollPositions.get(sessionId) : undefined;
    pendingRestore.current = saved ?? null;
    stuck.current = saved == null;
  }, [sessionId]);

  // Watch the user's scroll position; pin within ~120px of the bottom.
  // Only a genuine *upward* scroll unpins — the auto-scroll below only ever
  // moves down, so its own scroll events (which lag behind fast-growing content
  // mid-animation) can't spuriously flip us out of "stuck" and pop the pill.
  useEffect(() => {
    const el = scroller();
    if (!el) return;
    const onScroll = () => {
      const top = el.scrollTop;
      if (sessionId) scrollPositions.set(sessionId, top); // remember per session
      const goingUp = top < lastTop.current - 2;
      lastTop.current = top;
      const atBottom = el.scrollHeight - top - el.clientHeight < 120;
      if (atBottom) {
        stuck.current = true;
        setShowJump(false);
      } else if (goingUp) {
        stuck.current = false;
        setShowJump(true);
      }
    };
    el.addEventListener("scroll", onScroll, { passive: true });
    return () => el.removeEventListener("scroll", onScroll);
  }, [sessionId]);

  useEffect(() => {
    const vis = items.filter((it) => it.kind !== "tasks");
    const last = vis[vis.length - 1];
    // A new user turn (sending) always snaps back to the bottom.
    if (vis.length > prevLen.current && last?.kind === "user") {
      stuck.current = true;
    }
    prevLen.current = vis.length;
    // Restore a remembered scroll position on the first paint after a switch,
    // instead of jumping to the bottom.
    if (pendingRestore.current != null) {
      const el = scroller();
      if (el) {
        el.scrollTop = pendingRestore.current;
        // If that spot is effectively the bottom, keep following new content.
        stuck.current =
          el.scrollHeight - pendingRestore.current - el.clientHeight < 120;
        setShowJump(!stuck.current);
      }
      pendingRestore.current = null;
      if (vis.length) didInitialScroll.current = true;
      return;
    }
    if (!stuck.current) return;
    // Instant while streaming so the follow never lags behind growing content;
    // smooth only for discrete post-stream updates (e.g. a queued turn landing).
    const behavior = !didInitialScroll.current || streaming ? "instant" : "smooth";
    scrollToBottom(behavior);
    if (vis.length) didInitialScroll.current = true;
    setShowJump(false);
  }, [items, streaming, queue]);

  if (items.length === 0) {
    return (
      <div className="empty">
        {streaming ? (
          <span className="working">
            <span className="working-dots">
              <i />
              <i />
              <i />
            </span>
          </span>
        ) : (
          "No messages yet. Say something below."
        )}
      </div>
    );
  }

  const visible = items.filter((it) => it.kind !== "tasks");
  // While compacting, the card shows its own status + timer — hide the generic dots.
  const compacting = visible.some((it) => it.kind === "compacting" && !it.done);

  return (
    <OpenFileContext.Provider value={onOpenFile ?? null}>
    <div className="stream" ref={rootRef}>
      {visible.map((item, i) => (
        <div className="stream-item" key={item.key ?? i}>
          {item.kind === "question" ? (
            <QuestionCard
              item={item}
              active={!streaming && i === visible.length - 1}
              onAnswer={onAnswer}
            />
          ) : (
            <Item item={item} />
          )}
        </div>
      ))}
      {streaming && !compacting ? (
        <div className="working">
          <span className="working-dots">
            <i />
            <i />
            <i />
          </span>
        </div>
      ) : null}
      {queue.map((q, i) => (
        <div className="stream-item" key={`q${i}`}>
          <div className="msg msg-user is-queued">
            <div className="msg-body queued-body">
              <span className="queued-ic">
                <FontAwesomeIcon icon={faClock} />
              </span>
              <span className="queued-text">{q}</span>
              <button
                className="queued-x"
                onClick={() => onCancelQueued(i)}
                title="Remove from queue"
              >
                <FontAwesomeIcon icon={faXmark} />
              </button>
            </div>
          </div>
        </div>
      ))}
      {!streaming && !compacting && visible.length > 0 && queue.length === 0 ? (
        <div className="end-of-chat" aria-hidden>
          <span className="end-of-chat-line" />
          <span className="end-of-chat-text">end of conversation</span>
          <span className="end-of-chat-line" />
        </div>
      ) : null}
      <div ref={endRef} />
      {showJump ? (
        <button
          className="jump-latest"
          onClick={() => {
            stuck.current = true;
            setShowJump(false);
            scrollToBottom("smooth");
          }}
          title="Jump to latest"
        >
          <FontAwesomeIcon icon={faAnglesDown} /> Jump to latest
        </button>
      ) : null}
    </div>
    </OpenFileContext.Provider>
  );
}
