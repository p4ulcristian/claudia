// What every chat shares: the models, the slash commands, and the plan's usage limits.
// Read with a short Claude Code process that sends no message.
import { query, type SDKRateLimitInfo } from "@anthropic-ai/claude-agent-sdk";
import { account } from "./account";

export type ModelOption = { value: string; name: string; efforts: string[] };
export type Command = { name: string; description: string; hint: string };
export type Limit = { key: string; label: string; percent: number; resetsAt: string | null };
// account = the token name the chats run on (src/account.ts); limits are that token's plan.
// accountLabel = whose it is ("Paul"), from Settings.
export type Meta = { models: ModelOption[]; commands: Command[]; limits: Limit[]; account: string; accountLabel: string | null; defaultModel: string; at: number };

const LIMIT_LABELS: Record<string, string> = { five_hour: "5-hour", seven_day: "Week", seven_day_opus: "Week, Opus", seven_day_sonnet: "Week, Sonnet" };

let meta: Meta = { models: [], commands: [], limits: [], account: "", accountLabel: null, defaultModel: "", at: 0 };
// The last limits seen per account, so switching back shows them before the next turn.
const limitsOf = new Map<string, Limit[]>();
const listeners = new Set<(m: Meta) => void>();
let probing: Promise<void> | null = null;

export const getMeta = () => meta;
export function onMeta(fn: (m: Meta) => void) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

function publish(next: Partial<Meta>) {
  meta = { ...meta, ...next, at: Date.now() };
  for (const fn of listeners) fn(meta);
}

function limitsFrom(rl: any): Limit[] {
  if (!rl) return [];
  const out: Limit[] = [];
  for (const key of Object.keys(LIMIT_LABELS)) {
    const v = rl[key];
    if (v && typeof v.utilization === "number") out.push({ key, label: LIMIT_LABELS[key], percent: v.utilization, resetsAt: v.resets_at });
  }
  for (const m of rl.model_scoped ?? [])
    if (typeof m.utilization === "number" && m.utilization > 0)
      out.push({ key: `model:${m.display_name}`, label: `Week, ${m.display_name}`, percent: m.utilization, resetsAt: m.resets_at });
  return out;
}

// From a turn's rate_limit_event: the only usage readout a long-term token gets
// (the usage call below answers rate_limits_available: false for one).
export function noteLimits(name: string, info: SDKRateLimitInfo) {
  const windows = (info as any).unifiedWindows as Record<string, { utilization?: number; resetsAt?: number }> | undefined;
  if (!windows) return;
  const limits: Limit[] = [];
  for (const key of Object.keys(LIMIT_LABELS)) {
    const w = windows[key];
    if (w && typeof w.utilization === "number")
      limits.push({ key, label: LIMIT_LABELS[key], percent: Math.round(w.utilization * 100), resetsAt: w.resetsAt ? new Date(w.resetsAt * 1000).toISOString() : null });
  }
  if (!limits.length) return;
  limitsOf.set(name, limits);
  if (name === meta.account) publish({ limits });
}

const never = { async *[Symbol.asyncIterator]() { await new Promise(() => {}); } };

// One short process: models and commands (cheap) and the plan usage (one API call).
export function probe(cwd: string): Promise<void> {
  if (probing) return probing;
  probing = (async () => {
    const acct = await account().catch((err) => {
      console.error("meta probe:", err);
      return null;
    });
    if (!acct) {
      probing = null;
      return;
    }
    const q = query({ prompt: never as any, options: { cwd, persistSession: false, env: acct.env } });
    try {
      const [models, commands] = await Promise.all([q.supportedModels(), q.supportedCommands()]);
      let limits = limitsOf.get(acct.name) ?? [];
      // The model a new chat gets when none is picked (from the user's settings).
      const defaultModel = await q.getContextUsage({ detail: "summary" }).then((c) => c.model).catch(() => meta.defaultModel);
      try {
        // Marked experimental by the SDK; the rate_limit_event path below keeps working without it.
        const u = await (q as any).usage_EXPERIMENTAL_MAY_CHANGE_DO_NOT_RELY_ON_THIS_API_YET({ skipBehaviors: true });
        if (u?.rate_limits_available) limits = limitsFrom(u.rate_limits);
      } catch {}
      limitsOf.set(acct.name, limits);
      publish({
        models: models.map((m) => ({ value: m.value, name: m.displayName, efforts: m.supportedEffortLevels ?? [] })),
        commands: commands.map((c) => ({ name: c.name, description: c.description, hint: c.argumentHint })).sort((a, b) => a.name.localeCompare(b.name)),
        limits,
        account: acct.name,
        accountLabel: acct.label,
        defaultModel,
      });
    } catch (err) {
      console.error("meta probe failed:", err);
    } finally {
      q.close();
      probing = null;
    }
  })();
  return probing;
}

// A turn ended or Claude Code reported a rate-limit change: read the exact numbers again soon.
// (The live rate_limit_event's units aren't documented, so it is only a signal.)
let soon: Timer | null = null;
export function refreshSoon(cwd: string, ms = 8000) {
  if (soon) clearTimeout(soon);
  soon = setTimeout(() => { soon = null; probe(cwd); }, ms);
}
