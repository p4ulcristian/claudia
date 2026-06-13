import type { ClaudeEvent } from "./types";

// ---------------------------------------------------------------------------
// Context-window occupancy for a session.
//
// stream-json never reports "% context used" directly, but every assistant
// message carries message.usage. The INPUT side of the most recent assistant
// message — input_tokens + cache_read + cache_creation — is the full prompt
// that was sent, i.e. the entire conversation history + system prompt +
// CLAUDE.md + tools. That sum IS the context occupancy, the same number the
// Claude TUI meter shows. We derive a % from it client-side.
// ---------------------------------------------------------------------------

export interface ContextInfo {
  /** Current context occupancy in tokens. */
  tokens: number;
  /** The model's context window limit in tokens. */
  window: number;
  /** Occupancy as a 0–100 percentage of the window. */
  pct: number;
}

const WINDOW_200K = 200_000;
const WINDOW_1M = 1_000_000;

/** Auto-compact fires around here (~83.5% of the window); we hint a bit earlier. */
export const COMPACT_SUGGEST_PCT = 80;
export const COMPACT_AUTO_PCT = 83;

function usageTokens(usage: Record<string, unknown> | undefined): number | null {
  if (!usage) return null;
  const n = (k: string) => (typeof usage[k] === "number" ? (usage[k] as number) : 0);
  const t =
    n("input_tokens") + n("cache_read_input_tokens") + n("cache_creation_input_tokens");
  return t > 0 ? t : null;
}

/**
 * The context window for a model id. The CLI doesn't report it in the stream,
 * so we map it: Haiku is 200k; the 1M-capable family (Opus 4.6+, Sonnet 4.6,
 * Fable 5) runs at 1M in this deployment — verified empirically, a plain
 * `claude-opus-4-8` session reached 380k tokens. An explicit `[1m]` suffix is
 * always 1M; anything unrecognised defaults to the safe 200k.
 */
function windowForModel(model?: string): number {
  const m = (model || "").toLowerCase();
  if (m.includes("[1m]")) return WINDOW_1M;
  if (m.includes("haiku")) return WINDOW_200K;
  if (/opus|sonnet|fable/.test(m)) return WINDOW_1M;
  return WINDOW_200K;
}

/**
 * Current context occupancy, from the most recent assistant message that
 * carries usage. Returns null until at least one turn has produced usage.
 *
 * Window comes from the model id; if we ever observe more tokens than that
 * window, the session is plainly on a larger one, so bump to 1M.
 */
export function contextOf(events: ClaudeEvent[], model?: string): ContextInfo | null {
  let tokens: number | null = null;
  for (let i = events.length - 1; i >= 0; i--) {
    if (events[i].type !== "assistant") continue;
    const usage = (events[i].message as { usage?: Record<string, unknown> } | undefined)?.usage;
    const t = usageTokens(usage);
    if (t != null) {
      tokens = t;
      break;
    }
  }
  if (tokens == null) return null;

  let window = windowForModel(model);
  if (tokens > window) window = WINDOW_1M;

  return { tokens, window, pct: Math.min(100, (tokens / window) * 100) };
}

/** Short human token count: 1_000 → "1k", 1_000_000 → "1M". */
export function fmtTokens(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(n % 1_000_000 === 0 ? 0 : 1)}M`;
  if (n >= 1_000) return `${Math.round(n / 1_000)}k`;
  return String(n);
}
