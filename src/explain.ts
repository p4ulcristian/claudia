// "Explain" on a step: Haiku, on the side, tells in plain words what one tool call does (a command,
// an edit, a read) and, while it runs, what it waits on. It sees only that step and the request
// around it; the chat, its context and Claude's turn are not touched. Answers are kept per step.
import { query } from "@anthropic-ai/claude-agent-sdk";
import { createHash } from "node:crypto";
import { account } from "./account";

export type ExplainInput = {
  name: string; // the step's tool, as the chat shows it
  title: string;
  detail: string; // its input
  result?: string | null; // its output so far, if done
  error?: boolean;
  running?: boolean;
  seconds?: number; // how long it has run
  asked?: string; // the user's message this step works on
  before?: string; // what Claude said just before it
};

const SYSTEM = `You explain one step an AI coding agent (Claude Code) took, to the person it works for, who does not read code.
Answer in 2 or 3 short, plain English sentences: what this step does, and why it matters for what they asked.
If it is still running: say what it is waiting on and whether taking this long is normal for this kind of step.
If it failed: say what went wrong in plain words.
No code, no lists, no headings; name a file or command only when it helps. Every claim must follow from the step as given; don't guess beyond it.`;

const cut = (s: string | undefined | null, n: number) => (s ? (s.length > n ? `${s.slice(0, n)}\n…(cut)` : s) : "");

const done = new Map<string, string>();

export async function explain(i: ExplainInput): Promise<string> {
  const prompt = [
    i.asked && `What they asked:\n${cut(i.asked, 1500)}`,
    i.before && `What the agent said just before this step:\n${cut(i.before, 1000)}`,
    `The step: ${i.name} ${i.title}\nIts input:\n${cut(i.detail, 4000)}`,
    i.running
      ? `It is still running${i.seconds ? `, for ${Math.round(i.seconds)} seconds so far` : ""}.`
      : `Its output${i.error ? " (it failed)" : ""}:\n${cut(i.result, 4000) || "(none)"}`,
  ].filter(Boolean).join("\n\n");
  // A finished step's answer stays the same; a running one is asked again each time.
  const key = i.running ? null : createHash("sha256").update(prompt).digest("hex");
  if (key && done.has(key)) return done.get(key)!;

  const acct = await account();
  const q = query({
    prompt,
    options: { model: "haiku", systemPrompt: SYSTEM, tools: [], settingSources: [], maxTurns: 1, persistSession: false, env: acct.env },
  });
  let text = "";
  try {
    for await (const m of q) {
      if (m.type === "assistant") for (const b of (m.message as any).content ?? []) if (b.type === "text") text += b.text;
      if (m.type === "result") break;
    }
  } finally {
    q.close();
  }
  text = text.trim() || "No explanation came back.";
  if (key) {
    done.set(key, text);
    if (done.size > 500) done.delete(done.keys().next().value!);
  }
  return text;
}
