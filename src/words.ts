// One word per chat for the rail, under its circle: Haiku picks it from the chat's title, once per
// title, and it is kept; until then (or if Haiku fails) a word from the title stands in.
import { Database } from "bun:sqlite";
import { join } from "node:path";
import { query } from "@anthropic-ai/claude-agent-sdk";
import { account } from "./account";
import { DATA_DIR } from "./db";

const db = new Database(join(DATA_DIR, "app.db"));
db.exec("CREATE TABLE IF NOT EXISTS chat_words (session_id TEXT PRIMARY KEY, title TEXT NOT NULL, word TEXT NOT NULL)");
const q = {
  get: db.query<{ title: string; word: string }, [string]>("SELECT title, word FROM chat_words WHERE session_id = ?"),
  set: db.query("INSERT INTO chat_words (session_id, title, word) VALUES (?, ?, ?) ON CONFLICT (session_id) DO UPDATE SET title = excluded.title, word = excluded.word"),
};

const SKIP = new Set("a an the and or but of to in on at for with from by is are be it its this that these those my your our we i you he she they me us do does did can could should would will what how why when where which who let lets let's make add fix new chat pls please hey hi ok okay yes no just so also".split(" "));
const MAX = 12;

const tidy = (w: string) => {
  const clean = w.replace(/[^\p{L}\p{N}-]/gu, "").slice(0, MAX);
  return clean ? clean[0].toUpperCase() + clean.slice(1) : "";
};

// A word from the title: the first one that says something.
export function guess(title: string): string {
  const words = title.split(/\s+/).map((w) => w.replace(/[^\p{L}\p{N}-]/gu, "")).filter(Boolean);
  return tidy(words.find((w) => w.length > 2 && !SKIP.has(w.toLowerCase())) ?? words[0] ?? "Chat");
}

const waiting: { id: string; title: string }[] = [];
let working = false;
let changed: () => void = () => {};
export const onWords = (fn: () => void) => { changed = fn; };

async function ask(title: string): Promise<string | null> {
  const acct = await account();
  const it = query({
    prompt: `Chat title: "${title}"\n\nGive one word (at most ${MAX} letters) that names this chat on a tiny label under its icon, so its owner recognises it at a glance among other chats. Prefer the project or the thing being worked on over generic words. Answer with the word only.`,
    options: { model: "haiku", tools: [], settingSources: [], maxTurns: 1, persistSession: false, env: acct.env },
  });
  let text = "";
  try {
    for await (const m of it) {
      if (m.type === "assistant") for (const b of (m.message as any).content ?? []) if (b.type === "text") text += b.text;
      if (m.type === "result") break;
    }
  } finally {
    it.close();
  }
  return tidy(text.trim().split(/\s+/)[0] ?? "") || null;
}

async function work() {
  if (working) return;
  working = true;
  while (waiting.length) {
    const { id, title } = waiting.shift()!;
    if (q.get.get(id)?.title === title) continue;
    const word = await ask(title).catch(() => null);
    q.set.run(id, title, word ?? guess(title));
    changed();
  }
  working = false;
}

// The word for a chat now: the kept one if its title is unchanged, else a guess while Haiku is asked.
export function wordFor(id: string, title: string): string {
  const kept = q.get.get(id);
  if (kept?.title === title) return kept.word;
  if (title !== "New chat" && !waiting.some((w) => w.id === id && w.title === title)) {
    waiting.push({ id, title });
    void work();
  }
  return kept?.word ?? guess(title);
}
