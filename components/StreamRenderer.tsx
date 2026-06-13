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
  faGear,
  faSpinner,
  faTerminal,
  faXmark,
} from "./icons";

// Pull a short, human hint out of a tool's (possibly partial) JSON input.
function toolSummary(input: string): string {
  if (!input) return "";
  try {
    const o = JSON.parse(input) as Record<string, unknown>;
    const pick =
      o.file_path ?? o.path ?? o.pattern ?? o.command ?? o.query ?? o.url ?? o.prompt;
    if (typeof pick === "string") return pick.length > 64 ? pick.slice(0, 63) + "…" : pick;
  } catch {
    /* partial JSON mid-stream */
  }
  return "";
}

function ToolCard({ block }: { block: ToolUseBlock }) {
  const [open, setOpen] = useState(false);
  const summary = toolSummary(block.input);
  return (
    <div className={`tool-block ${open ? "is-open" : ""}`}>
      <button className="tool-name" onClick={() => setOpen((v) => !v)}>
        <span className="tool-icon">
          <FontAwesomeIcon icon={faGear} />
        </span>
        <span className="tool-label">{block.name}</span>
        {summary ? <span className="tool-summary">{summary}</span> : null}
        {block.input ? (
          <span className="chev">
            <FontAwesomeIcon icon={open ? faChevronDown : faChevronRight} />
          </span>
        ) : null}
      </button>
      {open && block.input ? <pre className="tool-input">{block.input}</pre> : null}
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
  onAnswer,
  onCancelQueued,
}: {
  items: DisplayItem[];
  streaming: boolean;
  autoScroll: boolean;
  queue: string[];
  onAnswer: (text: string) => void;
  onCancelQueued: (index: number) => void;
}) {
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (autoScroll) endRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
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
      {streaming ? (
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
