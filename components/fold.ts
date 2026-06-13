import type { ClaudeEvent } from "@/lib/types";

// Display items the renderer draws. We fold the raw Claude event stream
// (both replayed transcripts and live deltas) into this shape.

export interface TextBlock {
  type: "text";
  text: string;
}
export interface ToolUseBlock {
  type: "tool_use";
  id?: string;
  name: string;
  input: string; // accumulated JSON, possibly partial
}
export type AssistantBlock = TextBlock | ToolUseBlock;

export type DisplayItem =
  | { kind: "system"; text: string }
  | { kind: "user"; text: string }
  | { kind: "assistant"; id?: string; blocks: AssistantBlock[]; streaming: boolean }
  | { kind: "tool_result"; text: string; isError: boolean }
  | { kind: "result"; text: string; isError: boolean };

function asText(content: unknown): string {
  if (typeof content === "string") return content;
  if (Array.isArray(content)) {
    return content
      .map((b) => {
        if (typeof b === "string") return b;
        if (b && typeof b === "object") {
          const o = b as Record<string, unknown>;
          if (typeof o.text === "string") return o.text;
          if (typeof o.content === "string") return o.content;
        }
        return "";
      })
      .join("");
  }
  return "";
}

// Pull text + tool_use blocks out of a complete assistant message.content array.
function blocksFromContent(content: unknown): AssistantBlock[] {
  if (typeof content === "string") return [{ type: "text", text: content }];
  if (!Array.isArray(content)) return [];
  const blocks: AssistantBlock[] = [];
  for (const raw of content) {
    if (!raw || typeof raw !== "object") continue;
    const o = raw as Record<string, unknown>;
    if (o.type === "text" && typeof o.text === "string") {
      blocks.push({ type: "text", text: o.text });
    } else if (o.type === "tool_use") {
      blocks.push({
        type: "tool_use",
        id: typeof o.id === "string" ? o.id : undefined,
        name: typeof o.name === "string" ? o.name : "tool",
        input: o.input ? JSON.stringify(o.input, null, 2) : "",
      });
    }
  }
  return blocks;
}

/**
 * Fold a list of Claude events into renderable items.
 *
 * A single assistant message is delivered as MULTIPLE events that all share one
 * message `id`: the live partial stream (message_start → content_block_* deltas)
 * and one authoritative `assistant` event per content block (each carrying only
 * its own block). To avoid showing the same turn several times we fold every
 * event with a given id into ONE item:
 *   - when the partial stream is present (live), it builds the blocks
 *     token-by-token and the authoritative `assistant` events are ignored;
 *   - when it is absent (replaying a saved transcript), the `assistant` events
 *     accumulate their blocks into the same item.
 */
export function foldEvents(events: ClaudeEvent[]): DisplayItem[] {
  const items: DisplayItem[] = [];
  // message id -> index of its assistant item
  const byId = new Map<string, number>();
  // ids that produced a live partial stream (so we ignore their `assistant`s)
  const partialIds = new Set<string>();
  // the message currently being streamed
  let curIdx: number | null = null;

  const newAssistant = (id?: string, streaming = false): number => {
    items.push({ kind: "assistant", id, blocks: [], streaming });
    const idx = items.length - 1;
    if (id) byId.set(id, idx);
    return idx;
  };

  for (const evt of events) {
    const type = evt.type;

    switch (type) {
      case "system":
        // init banner — keep it terse
        if (evt.subtype === "init") {
          items.push({ kind: "system", text: "session started" });
        }
        break;

      case "user": {
        const content = evt.message?.content ?? (evt as { content?: unknown }).content;
        // Tool results arrive as user-role messages with an array content.
        if (Array.isArray(content)) {
          for (const raw of content) {
            if (raw && typeof raw === "object") {
              const o = raw as Record<string, unknown>;
              if (o.type === "tool_result") {
                items.push({
                  kind: "tool_result",
                  text: asText(o.content),
                  isError: o.is_error === true,
                });
              }
            }
          }
        } else {
          const text = asText(content).trim();
          if (text && !text.startsWith("<")) {
            items.push({ kind: "user", text });
          }
        }
        break;
      }

      case "assistant": {
        const id = (evt.message as { id?: string } | undefined)?.id;
        // Already rendered live from the partial stream; `message_stop` settles
        // the streaming flag, so ignore the authoritative copy here.
        if (id && partialIds.has(id)) break;
        // Replay (or no partial stream): accumulate blocks into the id's item.
        const idx = id && byId.has(id) ? byId.get(id)! : newAssistant(id, false);
        const item = items[idx] as Extract<DisplayItem, { kind: "assistant" }>;
        item.blocks.push(...blocksFromContent(evt.message?.content));
        item.streaming = false;
        break;
      }

      // ---- live partial-message stream events ----
      case "message_start": {
        const id = (evt.message as { id?: string } | undefined)?.id;
        if (id) partialIds.add(id);
        curIdx = id && byId.has(id) ? byId.get(id)! : newAssistant(id, true);
        (items[curIdx] as Extract<DisplayItem, { kind: "assistant" }>).streaming = true;
        break;
      }
      case "content_block_start": {
        if (curIdx === null) curIdx = newAssistant(undefined, true);
        const cb = (evt as { content_block?: Record<string, unknown> }).content_block;
        const item = items[curIdx] as Extract<DisplayItem, { kind: "assistant" }>;
        if (cb?.type === "tool_use") {
          item.blocks.push({
            type: "tool_use",
            id: typeof cb.id === "string" ? cb.id : undefined,
            name: typeof cb.name === "string" ? cb.name : "tool",
            input: "",
          });
        } else if (cb?.type === "text") {
          item.blocks.push({ type: "text", text: "" });
        }
        break;
      }
      case "content_block_delta": {
        if (curIdx === null) curIdx = newAssistant(undefined, true);
        const item = items[curIdx] as Extract<DisplayItem, { kind: "assistant" }>;
        const delta = (evt as { delta?: Record<string, unknown> }).delta;
        const last = item.blocks[item.blocks.length - 1];
        if (delta?.type === "text_delta" && typeof delta.text === "string") {
          if (last?.type === "text") last.text += delta.text;
          else item.blocks.push({ type: "text", text: delta.text });
        } else if (
          delta?.type === "input_json_delta" &&
          typeof delta.partial_json === "string"
        ) {
          if (last?.type === "tool_use") last.input += delta.partial_json;
        }
        break;
      }
      case "message_stop": {
        if (curIdx !== null) {
          (items[curIdx] as Extract<DisplayItem, { kind: "assistant" }>).streaming = false;
        }
        curIdx = null;
        break;
      }

      case "result": {
        const text =
          typeof evt.result === "string" ? evt.result : asText(evt.result);
        const trimmed = text.trim();
        if (!trimmed) break;
        // The result event echoes the final answer; if it merely repeats the
        // last assistant message's text, don't render it a second time. Only
        // surface it on error or when it adds something new.
        const lastAssistant = [...items]
          .reverse()
          .find((it) => it.kind === "assistant") as
          | Extract<DisplayItem, { kind: "assistant" }>
          | undefined;
        const lastText = lastAssistant?.blocks
          .filter((b): b is TextBlock => b.type === "text")
          .map((b) => b.text)
          .join("")
          .trim();
        const isEcho = lastText !== undefined && lastText === trimmed;
        if (evt.is_error === true || !isEcho) {
          items.push({ kind: "result", text: trimmed, isError: evt.is_error === true });
        }
        break;
      }

      default:
        break;
    }
  }

  return items;
}
