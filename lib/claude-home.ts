import os from "node:os";
import path from "node:path";

/**
 * Root of the Claude data dir (`~/.claude` by default). Override with
 * CLAUDE_HOME for tests or non-standard setups.
 */
export function claudeHome(): string {
  return process.env.CLAUDE_HOME || path.join(os.homedir(), ".claude");
}

/** Where the `claude` CLI keeps per-project session transcripts. */
export function projectsRoot(): string {
  return path.join(claudeHome(), "projects");
}

/**
 * Encode a working-directory path to its ~/.claude/projects directory name.
 * Matches the Claude CLI scheme: every '/' and '.' becomes '-'.
 */
export function encodePath(p: string): string {
  return p.replace(/[/.]/g, "-");
}

/** The on-disk projects dir for a given folder (may not exist). */
export function sessionDir(folder: string): string {
  return path.join(projectsRoot(), encodePath(folder));
}

/** Path to the `claude` binary: $CLAUDE_BIN, then ~/.local/bin/claude, then PATH. */
export function claudeBin(): string {
  if (process.env.CLAUDE_BIN) return process.env.CLAUDE_BIN;
  const local = path.join(os.homedir(), ".local", "bin", "claude");
  // We can't stat synchronously without fs here cheaply; callers spawn it and
  // the shell resolves it. Prefer the local path only if it exists.
  try {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const fs = require("node:fs") as typeof import("node:fs");
    if (fs.existsSync(local)) return local;
  } catch {
    /* ignore */
  }
  return "claude";
}

/** The model claudia runs. Defaults to the freshest model. */
export function claudeModel(): string {
  return process.env.CLAUDE_MODEL || "claude-opus-4-8";
}
