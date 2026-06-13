"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import type { ClaudeEvent } from "@/lib/types";
import { foldEvents, type DisplayItem, type ToolUseBlock } from "./fold";
import Markdown from "./Markdown";

// Pull a short, human hint out of a tool's (possibly partial) JSON input so a
// collapsed tool card can read "Read · lib/sessions.ts" instead of "{...}".
function toolSummary(input: string): string {
  if (!input) return "";
  try {
    const o = JSON.parse(input) as Record<string, unknown>;
    const pick =
      o.file_path ?? o.path ?? o.pattern ?? o.command ?? o.query ?? o.url ?? o.prompt;
    if (typeof pick === "string") return pick.length > 64 ? pick.slice(0, 63) + "…" : pick;
  } catch {
    // partial JSON mid-stream — ignore
  }
  return "";
}

function ToolCard({ block }: { block: ToolUseBlock }) {
  const [open, setOpen] = useState(false);
  const summary = toolSummary(block.input);
  return (
    <div className={`tool-block ${open ? "is-open" : ""}`}>
      <button className="tool-name" onClick={() => setOpen((v) => !v)}>
        <span className="tool-icon">⚙</span>
        <span className="tool-label">{block.name}</span>
        {summary ? <span className="tool-summary">{summary}</span> : null}
        {block.input ? <span className="chev">{open ? "▾" : "▸"}</span> : null}
      </button>
      {open && block.input ? <pre className="tool-input">{block.input}</pre> : null}
    </div>
  );
}

function AssistantItem({ item }: { item: Extract<DisplayItem, { kind: "assistant" }> }) {
  return (
    <div className="msg msg-assistant">
      <div className="msg-role">
        <span className={`dot ${item.streaming ? "is-live" : ""}`} />
        claudia
      </div>
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
        <span className="chev">{open ? "▾" : "▸"}</span>
      </button>
      {open ? <pre>{body}</pre> : null}
    </div>
  );
}

function Item({ item }: { item: DisplayItem }) {
  switch (item.kind) {
    case "system":
      return <div className="msg-system">{item.text}</div>;
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
  events,
  streaming,
}: {
  events: ClaudeEvent[];
  streaming: boolean;
}) {
  const items = useMemo(() => foldEvents(events), [events]);
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [items, streaming]);

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

  return (
    <div className="stream">
      {items.map((item, i) => (
        <div className="stream-item" key={i}>
          <Item item={item} />
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
      <div ref={endRef} />
    </div>
  );
}
