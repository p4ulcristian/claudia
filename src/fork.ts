// Forking a chat: a new Claude Code session holding the chat up to the end of one turn, with
// Claudia's own rows for the turns it keeps (settings, pictures, file changes) copied over.
// The fork works in the same folder: files on disk are not touched.
import { forkSession, getSessionMessages } from "@anthropic-ai/claude-agent-sdk";
import { WORK, sentMidTurn, sessionFile, withEarlierChains, type Said } from "./chat";
import { store } from "./db";

const blocksOf = (m: Said): any[] => {
  const c = (m.message as any)?.content;
  return Array.isArray(c) ? c : [];
};

// A user entry that starts a turn: something typed (text or images) that follows a finished turn.
// A message sent while a turn ran follows a tool result or a tool call, and belongs to that turn.
function startsTurn(chain: Said[], i: number): boolean {
  const m = chain[i]!;
  if (m.type !== "user") return false;
  const content = (m.message as any)?.content;
  const typed = typeof content === "string" || blocksOf(m).some((b) => b.type === "text" || b.type === "image");
  if (!typed || blocksOf(m).some((b) => b.type === "tool_result")) return false;
  const prev = chain[i - 1];
  if (!prev) return true;
  if (prev.type === "assistant") return (prev.message as any)?.stop_reason !== "tool_use";
  if (prev.type === "user") return !blocksOf(prev).some((b) => b.type === "tool_result");
  return true;
}

type Entry = { uuid?: string; parentUuid?: string | null; forkedFrom?: { messageUuid?: string } } & Record<string, unknown>;

async function entries(sessionId: string): Promise<Entry[]> {
  const text = await Bun.file(sessionFile(sessionId)).text();
  const out: Entry[] = [];
  for (const line of text.split("\n")) {
    if (!line) continue;
    try { out.push(JSON.parse(line)); } catch {}
  }
  return out;
}

// at: the uuid of a user message; the fork keeps everything up to the end of that message's turn.
// Without it, the whole chat up to its last finished turn. A running turn is never kept: its
// entries are still being written.
export async function forkChat(id: string, at: string | null, running: boolean): Promise<string> {
  const chain = (await withEarlierChains(id, await getSessionMessages(id, { dir: WORK }))).filter((m) => !m.parent_tool_use_id);
  if (!chain.length) throw new Error("This chat has nothing to fork yet.");
  const starts = chain.flatMap((_, i) => (startsTurn(chain, i) ? [i] : []));

  // The first turn the fork leaves out (its index in chain), or none = keep everything.
  let cut: number | undefined;
  if (at) {
    const i = chain.findIndex((m) => m.uuid === at);
    if (i === -1) throw new Error("That message isn't saved in the chat yet.");
    cut = starts.find((s) => s > i);
    if (cut === undefined && running) throw new Error("That turn is still running: fork it once it's done.");
  } else if (running) {
    cut = starts.at(-1);
    if (!cut) throw new Error("No finished turn to fork yet.");
  }

  const old = await entries(id);
  let upTo: string | undefined;
  if (cut !== undefined) {
    // The kept turn's last entry is what the next turn's message hangs on (it can be an entry the
    // chat doesn't show, e.g. a hook summary); the shown message before it if that isn't found.
    const next = old.find((e) => e.uuid === chain[cut!]!.uuid);
    upTo = (typeof next?.parentUuid === "string" && next.parentUuid) || chain[cut - 1]!.uuid;
  }
  const { sessionId: fork } = await forkSession(id, { dir: WORK, ...(upTo ? { upToMessageId: upTo } : {}) });

  // Each copied entry names the one it came from: the kept turns' uuids, old → new. A message sent
  // mid-turn is known by the uuid its queued_command attachment holds.
  const byUuid = new Map(old.filter((e) => e.uuid).map((e) => [e.uuid!, e]));
  const turns = new Map<string, string>();
  for (const e of await entries(fork)) {
    const from = e.forkedFrom?.messageUuid;
    if (!from || !e.uuid) continue;
    turns.set(from, e.uuid);
    const was = sentMidTurn(byUuid.get(from));
    const now = sentMidTurn(e);
    if (was && now) turns.set(was, now);
  }

  const settings = store.settings(id);
  store.saveSettings(fork, { ...settings, color: null, prevColor: null });
  store.addChat(fork);
  store.copyTurns(id, fork, turns);
  return fork;
}
