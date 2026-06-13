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

export interface TaskRow {
  id: string;
  subject: string;
  status: string; // pending | in_progress | completed | stopped
  activeForm?: string;
}
export interface QuestionOption {
  label: string;
  description?: string;
}
export interface QuestionSpec {
  question: string;
  header?: string;
  multiSelect?: boolean;
  options: QuestionOption[];
}

export type DisplayItem =
  | { kind: "user"; text: string }
  | { kind: "assistant"; id?: string; blocks: AssistantBlock[]; streaming: boolean }
  | { kind: "tool_result"; text: string; isError: boolean }
  | { kind: "result"; text: string; isError: boolean }
  | { kind: "tasks"; tasks: TaskRow[] }
  | { kind: "bgtask"; taskId: string; description: string; status: string; summary: string | null }
  | { kind: "question"; id?: string; questions: QuestionSpec[] }
  | { kind: "compact" };

// Tools we render specially (and whose raw tool_use / tool_result we suppress).
const TASK_TOOLS = new Set(["TaskCreate", "TaskUpdate", "TaskStop", "TaskList", "TaskGet"]);
function isSpecialTool(name: string): boolean {
  return TASK_TOOLS.has(name) || name === "TodoWrite" || name === "AskUserQuestion";
}

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

/**
 * Fold a list of Claude events into renderable items.
 *
 * A single assistant message is delivered as MULTIPLE events that share one
 * message `id`: the live partial stream (message_start → content_block_* deltas)
 * and one authoritative `assistant` event per content block. We fold every event
 * with a given id into ONE item (live builds it; replay accumulates).
 *
 * On top of that, three tools get richer treatment: Task* tools fold into a
 * single live checklist, background `Bash` shells surface as status cards via
 * the system `task_*` events, and `AskUserQuestion` becomes an answerable card.
 */
export function foldEvents(events: ClaudeEvent[]): DisplayItem[] {
  const items: DisplayItem[] = [];
  const byId = new Map<string, number>(); // message id -> assistant item index
  const partialIds = new Set<string>(); // ids with a live partial stream
  let curIdx: number | null = null; // message currently streaming

  // ---- aggregate state for special tools ----
  const tasks: TaskRow[] = [];
  const taskIndexById = new Map<string, number>();
  let tasksItemIdx: number | null = null;
  let taskSeq = 0;
  const bgIndexById = new Map<string, number>(); // background task_id -> item index
  const suppressedToolIds = new Set<string>(); // tool_use ids whose result we hide

  const newAssistant = (id?: string, streaming = false): number => {
    items.push({ kind: "assistant", id, blocks: [], streaming });
    const idx = items.length - 1;
    if (id) byId.set(id, idx);
    return idx;
  };

  const ensureTasksItem = () => {
    if (tasksItemIdx === null) {
      items.push({ kind: "tasks", tasks });
      tasksItemIdx = items.length - 1;
    }
  };

  // Handle a tool_use that we render specially. Returns true if it was special
  // (so the caller skips adding it as a normal tool card).
  const handleSpecialTool = (
    name: string,
    id: string | undefined,
    input: Record<string, unknown>,
  ): boolean => {
    if (!isSpecialTool(name)) return false;
    if (id) suppressedToolIds.add(id);

    if (name === "AskUserQuestion") {
      const raw = Array.isArray(input.questions) ? input.questions : [];
      const questions: QuestionSpec[] = raw.map((q) => {
        const o = (q ?? {}) as Record<string, unknown>;
        const opts = Array.isArray(o.options) ? o.options : [];
        return {
          question: String(o.question ?? ""),
          header: typeof o.header === "string" ? o.header : undefined,
          multiSelect: o.multiSelect === true,
          options: opts.map((op) => {
            const oo = (op ?? {}) as Record<string, unknown>;
            return {
              label: String(oo.label ?? ""),
              description: typeof oo.description === "string" ? oo.description : undefined,
            };
          }),
        };
      });
      if (questions.length) items.push({ kind: "question", id, questions });
      return true;
    }

    if (name === "TodoWrite") {
      const todos = Array.isArray(input.todos) ? input.todos : [];
      tasks.length = 0;
      taskIndexById.clear();
      todos.forEach((t, i) => {
        const o = (t ?? {}) as Record<string, unknown>;
        tasks.push({
          id: String(i + 1),
          subject: String(o.content ?? "task"),
          status: String(o.status ?? "pending"),
          activeForm: typeof o.activeForm === "string" ? o.activeForm : undefined,
        });
      });
      ensureTasksItem();
      return true;
    }

    if (name === "TaskCreate") {
      taskSeq += 1;
      const tid = String(taskSeq);
      tasks.push({
        id: tid,
        subject: String(input.subject ?? "task"),
        status: "pending",
        activeForm: typeof input.description === "string" ? input.description : undefined,
      });
      taskIndexById.set(tid, tasks.length - 1);
      ensureTasksItem();
      return true;
    }
    if (name === "TaskUpdate") {
      const tid = String(input.taskId ?? "");
      const i = taskIndexById.get(tid);
      if (i !== undefined) {
        if (typeof input.status === "string") tasks[i].status = input.status;
        if (typeof input.activeForm === "string") tasks[i].activeForm = input.activeForm;
      }
      ensureTasksItem();
      return true;
    }
    if (name === "TaskStop") {
      const tid = String(input.taskId ?? "");
      const i = taskIndexById.get(tid);
      if (i !== undefined) tasks[i].status = "stopped";
      return true;
    }
    // TaskList / TaskGet — read-only, just suppress the noise.
    return true;
  };

  // Pull text + tool_use blocks from a complete assistant message.content array,
  // routing special tools to handleSpecialTool instead of rendering them raw.
  const blocksFromContent = (content: unknown): AssistantBlock[] => {
    if (typeof content === "string") return [{ type: "text", text: content }];
    if (!Array.isArray(content)) return [];
    const blocks: AssistantBlock[] = [];
    for (const raw of content) {
      if (!raw || typeof raw !== "object") continue;
      const o = raw as Record<string, unknown>;
      if (o.type === "text" && typeof o.text === "string") {
        blocks.push({ type: "text", text: o.text });
      } else if (o.type === "tool_use") {
        const name = typeof o.name === "string" ? o.name : "tool";
        const id = typeof o.id === "string" ? o.id : undefined;
        if (handleSpecialTool(name, id, (o.input as Record<string, unknown>) ?? {})) continue;
        blocks.push({
          type: "tool_use",
          id,
          name,
          input: o.input ? JSON.stringify(o.input, null, 2) : "",
        });
      }
    }
    return blocks;
  };

  for (const evt of events) {
    const type = evt.type;

    switch (type) {
      case "system": {
        const st = evt.subtype;
        if (st === "task_started") {
          const tid = String((evt as Record<string, unknown>).task_id ?? "");
          if (tid && !bgIndexById.has(tid)) {
            items.push({
              kind: "bgtask",
              taskId: tid,
              description: String((evt as Record<string, unknown>).description ?? "background task"),
              status: "running",
              summary: null,
            });
            bgIndexById.set(tid, items.length - 1);
          }
        } else if (st === "task_notification") {
          const o = evt as Record<string, unknown>;
          const tid = String(o.task_id ?? "");
          const status = String(o.status ?? "done");
          const summary = o.summary != null ? String(o.summary) : null;
          const i = bgIndexById.get(tid);
          if (i !== undefined) {
            const it = items[i] as Extract<DisplayItem, { kind: "bgtask" }>;
            it.status = status;
            it.summary = summary;
          } else if (tid) {
            items.push({ kind: "bgtask", taskId: tid, description: "background task", status, summary });
            bgIndexById.set(tid, items.length - 1);
          }
        } else if (st === "compact_boundary") {
          items.push({ kind: "compact" });
        }
        // other system subtypes (init, thinking_tokens, …) carry no UI content
        break;
      }

      case "user": {
        const content = evt.message?.content ?? (evt as { content?: unknown }).content;
        if (Array.isArray(content)) {
          for (const raw of content) {
            if (raw && typeof raw === "object") {
              const o = raw as Record<string, unknown>;
              if (o.type === "tool_result") {
                const tid = typeof o.tool_use_id === "string" ? o.tool_use_id : "";
                if (tid && suppressedToolIds.has(tid)) continue; // special tool — hidden
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
        if (id && partialIds.has(id)) break; // already built live
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
      case "content_block_stop": {
        // When a special tool's input has fully streamed, route it and drop the
        // raw block (the aggregate card renders it instead).
        if (curIdx === null) break;
        const item = items[curIdx] as Extract<DisplayItem, { kind: "assistant" }>;
        const last = item.blocks[item.blocks.length - 1];
        if (last && last.type === "tool_use" && isSpecialTool(last.name)) {
          let input: Record<string, unknown> = {};
          try {
            input = last.input ? (JSON.parse(last.input) as Record<string, unknown>) : {};
          } catch {
            /* partial / unparseable — handle with what we have */
          }
          handleSpecialTool(last.name, last.id, input);
          item.blocks.pop();
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
        const text = typeof evt.result === "string" ? evt.result : asText(evt.result);
        const trimmed = text.trim();
        if (!trimmed) break;
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

  // Headless mode auto-denies AskUserQuestion, after which the model "recovers"
  // with a fallback ramble and ends the turn. We can't truly pause the process
  // (killing it corrupts resume), so instead hide everything between a question
  // and the user's answer — the card becomes the clear, final call to action.
  const cleaned: DisplayItem[] = [];
  let awaitingAnswer = false;
  for (const it of items) {
    if (it.kind === "question") {
      cleaned.push(it);
      awaitingAnswer = true;
      continue;
    }
    if (awaitingAnswer) {
      if (it.kind === "user") {
        awaitingAnswer = false;
        cleaned.push(it);
      }
      continue; // drop the post-denial fallback until the user answers
    }
    cleaned.push(it);
  }

  return cleaned;
}
