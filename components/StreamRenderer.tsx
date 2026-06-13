"use client";

import { useEffect, useRef, useState } from "react";
import { type DisplayItem, type ToolUseBlock } from "./fold";
import Markdown from "./Markdown";
import {
  FontAwesomeIcon,
  faChevronDown,
  faChevronRight,
  faCircleQuestion,
  faClock,
  faCompress,
  faGear,
  faSpinner,
  faTerminal,
  faXmark,
} from "./icons";

// Collapse whitespace and clip to a single short line for the header hint.
function truncate(s: string, n = 72): string {
  const line = s.replace(/\s+/g, " ").trim();
  return line.length > n ? line.slice(0, n - 1) + "…" : line;
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
): { icon: typeof faGear; summary: string; command?: string; json?: string } {
  let parsed: Record<string, unknown> | null = null;
  if (input) {
    try {
      parsed = JSON.parse(input) as Record<string, unknown>;
    } catch {
      /* partial JSON mid-stream */
    }
  }
  if (name === "Bash") {
    const command = typeof parsed?.command === "string" ? parsed.command : "";
    const description = typeof parsed?.description === "string" ? parsed.description : "";
    if (command) return { icon: faTerminal, summary: truncate(description || command), command };
    return { icon: faTerminal, summary: "", json: input }; // mid-stream / no command yet
  }
  return { icon: faGear, summary: parsed ? truncate(pickSummary(parsed)) : "", json: input };
}

function ToolCard({ block }: { block: ToolUseBlock }) {
  const [open, setOpen] = useState(false);
  const { icon, summary, command, json } = toolView(block.name, block.input);
  const hasDetail = Boolean(command || json);
  return (
    <div className={`tool-block ${open ? "is-open" : ""}`}>
      <button className="tool-name" onClick={() => setOpen((v) => !v)}>
        <span className="tool-icon">
          <FontAwesomeIcon icon={icon} />
        </span>
        <span className="tool-label">{block.name}</span>
        {summary ? <span className="tool-summary">{summary}</span> : null}
        {hasDetail ? (
          <span className="chev">
            <FontAwesomeIcon icon={open ? faChevronDown : faChevronRight} />
          </span>
        ) : null}
      </button>
      {open && command ? <pre className="tool-cmd">{command}</pre> : null}
      {open && !command && json ? <pre className="tool-input">{json}</pre> : null}
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
              {item.streaming && i === item.blocks.length - 1 ? (
                <span className="cursor" />
              ) : null}
            </div>
          ) : (
            <ToolCard key={i} block={b} />
          ),
        )}
        {item.blocks.length === 0 && item.streaming ? (
          <div className="text-block">
            <span className="cursor" />
          </div>
        ) : null}
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

function Item({ item }: { item: DisplayItem }) {
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
}

export default function StreamRenderer({
  items,
  streaming,
  autoScroll,
  queue,
  sessionId,
  onAnswer,
  onCancelQueued,
}: {
  items: DisplayItem[];
  streaming: boolean;
  autoScroll: boolean;
  queue: string[];
  sessionId: string | null;
  onAnswer: (text: string) => void;
  onCancelQueued: (index: number) => void;
}) {
  const endRef = useRef<HTMLDivElement>(null);
  // Jump instantly on the first paint of a chat; animate only later updates.
  // A smooth scroll on open would visibly travel the whole transcript top→bottom.
  const didInitialScroll = useRef(false);

  useEffect(() => {
    didInitialScroll.current = false;
  }, [sessionId]);

  useEffect(() => {
    if (!autoScroll) return;
    const behavior = didInitialScroll.current ? "smooth" : "instant";
    endRef.current?.scrollIntoView({ behavior, block: "end" });
    if (items.length) didInitialScroll.current = true;
  }, [items, streaming, autoScroll, queue]);

  if (items.length === 0) {
    return (
      <div className="empty">
        {streaming ? (
          <span className="working-dots">
            <i />
            <i />
            <i />
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
    <div className="stream">
      {visible.map((item, i) => (
        <div className="stream-item" key={i}>
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
      <div ref={endRef} />
    </div>
  );
}
