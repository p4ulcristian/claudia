import { spawn } from "node:child_process";
import readline from "node:readline";
import { claudeBin, claudeModel } from "./claude-home";
import type { ChatStreamMessage, ClaudeEvent } from "./types";

export interface ResumeOptions {
  folder: string;
  /** Present → resume that session; absent → start a new one. */
  sessionId?: string | null;
  prompt: string;
  /** Model id to run; falls back to CLAUDE_MODEL / the default (Opus). */
  model?: string;
  /** Aborting this kills the spawned process and ends the stream. */
  signal?: AbortSignal;
}

function buildArgs(opts: ResumeOptions): string[] {
  const args = [
    "--output-format",
    "stream-json",
    "--verbose",
    "--include-partial-messages",
    "--dangerously-skip-permissions",
    // AskUserQuestion can't be answered cleanly in headless mode (it auto-denies
    // and the model rambles), so disable it — claudia chats in plain text.
    "--disallowed-tools",
    "AskUserQuestion",
    "--model",
    opts.model?.trim() || claudeModel(),
  ];
  if (opts.sessionId) args.push("--resume", opts.sessionId);
  args.push("-p", String(opts.prompt));
  return args;
}

// CSI escape sequences the CLI sometimes interleaves into its output.
const ANSI_RE = /\x1b\[[0-9;]*[a-zA-Z]/g;

function parseLine(line: string): ClaudeEvent | null {
  const clean = line.replace(ANSI_RE, "");
  if (!clean.trimStart().startsWith("{")) return null;
  try {
    return JSON.parse(clean) as ClaudeEvent;
  } catch {
    return null;
  }
}

/** Unwrap the `stream_event` envelope to the inner event when present. */
function unwrap(evt: ClaudeEvent): ClaudeEvent {
  if (evt.type === "stream_event" && evt.event) return evt.event;
  return evt;
}

/**
 * Spawn `claude` in `folder` and yield stream messages until it exits.
 * Mirrors the original entity's behaviour (session-id, event, done, error)
 * but as a plain async generator the SSE route can iterate.
 */
export async function* resume(
  opts: ResumeOptions,
): AsyncGenerator<ChatStreamMessage> {
  const args = buildArgs(opts);

  const env = { ...process.env };
  env.TERM = "dumb";
  env.ENABLE_TOOL_SEARCH = "false";
  delete env.CLAUDECODE;
  delete env.CLAUDE_CODE_ENTRYPOINT;

  const child = spawn(claudeBin(), args, {
    cwd: opts.folder,
    env,
    stdio: ["ignore", "pipe", "pipe"],
  });

  // Kill the process if the client disconnects / aborts mid-stream.
  const onAbort = () => {
    child.kill("SIGTERM");
    // Hard stop shortly after if it ignores SIGTERM.
    setTimeout(() => child.kill("SIGKILL"), 2000).unref?.();
  };
  if (opts.signal) {
    if (opts.signal.aborted) onAbort();
    else opts.signal.addEventListener("abort", onAbort, { once: true });
  }

  // Collect stderr so a spawn failure produces a useful error message.
  let stderr = "";
  child.stderr.on("data", (chunk: Buffer) => {
    stderr += chunk.toString("utf8");
  });

  const spawnError = new Promise<Error | null>((res) => {
    child.once("error", (err) => res(err));
    child.once("spawn", () => res(null));
  });

  try {
    const err = await spawnError;
    if (err) {
      yield { kind: "error", message: err.message };
      return;
    }

    const rl = readline.createInterface({
      input: child.stdout,
      crlfDelay: Infinity,
    });

    let lastSessionId: string | null = null;
    for await (const line of rl) {
      const parsed = parseLine(line);
      if (!parsed) continue;
      const evt = unwrap(parsed);
      const sid = evt.session_id;
      if (sid && sid !== lastSessionId) {
        lastSessionId = sid;
        yield { kind: "session-id", sessionId: sid };
      }
      yield { kind: "event", event: evt };
    }

    const code: number = await new Promise((res) =>
      child.once("close", (c) => res(c ?? 0)),
    );
    if (code !== 0 && !opts.signal?.aborted) {
      yield {
        kind: "error",
        message: stderr.trim() || `claude exited with code ${code}`,
      };
      return;
    }
    yield { kind: "done" };
  } catch (e) {
    yield { kind: "error", message: e instanceof Error ? e.message : String(e) };
  } finally {
    opts.signal?.removeEventListener("abort", onAbort);
    if (!child.killed) child.kill("SIGTERM");
  }
}
