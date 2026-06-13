import { randomUUID } from "node:crypto";
import { resume } from "./claude-process";
import { loadSession } from "./sessions";
import { setActive } from "./session-meta";
import type { ChatStreamMessage, ClaudeEvent, JobStatus } from "./types";

// First human message in a transcript, for a session label.
function titleFromEvents(events: ClaudeEvent[]): string | null {
  for (const evt of events) {
    if (evt.type !== "user") continue;
    const content = evt.message?.content;
    const text =
      typeof content === "string"
        ? content
        : typeof (evt as { content?: unknown }).content === "string"
          ? ((evt as { content?: string }).content as string)
          : null;
    const t = text?.trim();
    if (t && !t.startsWith("<")) return t.length > 80 ? `${t.slice(0, 80)}…` : t;
  }
  return null;
}

// ---------------------------------------------------------------------------
// Long-running chat jobs.
//
// A job owns a spawned `claude` process and lives in this module-level registry,
// independent of any HTTP request. Requests merely *subscribe* to a job's event
// stream; a client disconnecting (refresh / navigation) only unsubscribes. The
// process is killed exclusively via `stopJob` (the Stop button).
// ---------------------------------------------------------------------------

type Subscriber = (msg: ChatStreamMessage) => void;

interface Job {
  id: string;
  folder: string;
  sessionId: string | null;
  /** Full event list: prior transcript + the user prompt + live events. */
  events: ClaudeEvent[];
  status: JobStatus;
  error?: string;
  /** Epoch millis the job was started — for "running for Xs" in the UI. */
  startedAt: number;
  ac: AbortController;
  subscribers: Set<Subscriber>;
  ended: boolean;
  cleanupTimer?: ReturnType<typeof setTimeout>;
}

const jobs = new Map<string, Job>(); // jobId -> Job
const bySession = new Map<string, string>(); // sessionId -> jobId

// Keep a finished job around briefly so a client reconnecting right after it
// ends still sees the final state before falling back to the saved transcript.
const GRACE_MS = 2 * 60 * 1000;

function broadcast(job: Job, msg: ChatStreamMessage) {
  for (const cb of job.subscribers) {
    try {
      cb(msg);
    } catch {
      /* a dead subscriber shouldn't break the others */
    }
  }
}

function scheduleCleanup(job: Job) {
  if (job.cleanupTimer) return;
  job.cleanupTimer = setTimeout(() => {
    jobs.delete(job.id);
    if (job.sessionId && bySession.get(job.sessionId) === job.id) {
      bySession.delete(job.sessionId);
    }
  }, GRACE_MS);
}

function finalize(job: Job, status?: JobStatus) {
  if (job.ended) return;
  job.ended = true;
  if (status) job.status = status;
  else if (job.status === "running") job.status = "done";
  broadcast(job, { kind: "done" });
  scheduleCleanup(job);
}

export function getJob(jobId: string): Job | null {
  return jobs.get(jobId) ?? null;
}

export function getJobBySession(sessionId: string): Job | null {
  const id = bySession.get(sessionId);
  return id ? jobs.get(id) ?? null : null;
}

export function snapshotOf(job: Job): ChatStreamMessage {
  return {
    kind: "snapshot",
    jobId: job.id,
    sessionId: job.sessionId,
    events: job.events,
    status: job.status,
    error: job.error,
  };
}

/** Subscribe to a job's stream. Returns an unsubscribe fn. */
export function subscribe(job: Job, cb: Subscriber): () => void {
  job.subscribers.add(cb);
  return () => {
    job.subscribers.delete(cb);
  };
}

/** Whether a job has stopped producing events. */
export function isEnded(job: Job): boolean {
  return job.status !== "running";
}

/** Start a generation turn. If one is already running for the session, reuse it. */
export async function startJob(opts: {
  folder: string;
  sessionId: string | null;
  prompt: string;
  model?: string;
}): Promise<Job> {
  if (opts.sessionId) {
    const prev = getJobBySession(opts.sessionId);
    if (prev && prev.status === "running") return prev; // don't double-spawn
    if (prev) {
      if (prev.cleanupTimer) clearTimeout(prev.cleanupTimer);
      jobs.delete(prev.id);
      bySession.delete(opts.sessionId);
    }
  }

  const base = opts.sessionId ? await loadSession(opts.folder, opts.sessionId) : [];
  const userEvent: ClaudeEvent = {
    type: "user",
    message: { role: "user", content: opts.prompt },
    timestamp: new Date().toISOString(),
  };

  const job: Job = {
    id: randomUUID(),
    folder: opts.folder,
    sessionId: opts.sessionId,
    events: [...base, userEvent],
    status: "running",
    startedAt: Date.now(),
    ac: new AbortController(),
    subscribers: new Set(),
    ended: false,
  };
  jobs.set(job.id, job);
  if (opts.sessionId) bySession.set(opts.sessionId, job.id);

  // Engaging with a session (sending a message) marks it active for the home
  // list. Title from the first user message, else the prompt. Fire-and-forget.
  const title = titleFromEvents(job.events) ?? opts.prompt.slice(0, 80);
  if (opts.sessionId) {
    void setActive(opts.sessionId, opts.folder, title, Date.now());
  }

  // Drive the process in the background, decoupled from any request lifecycle.
  void (async () => {
    try {
      for await (const msg of resume({
        folder: opts.folder,
        sessionId: opts.sessionId,
        prompt: opts.prompt,
        model: opts.model,
        signal: job.ac.signal,
      })) {
        if (msg.kind === "event") {
          job.events.push(msg.event);
          broadcast(job, msg);
        } else if (msg.kind === "session-id") {
          job.sessionId = msg.sessionId;
          if (!bySession.has(msg.sessionId)) bySession.set(msg.sessionId, job.id);
          // New session just got its id — mark it active for the home list.
          void setActive(msg.sessionId, job.folder, title, Date.now());
          broadcast(job, msg);
        } else if (msg.kind === "error") {
          job.status = "error";
          job.error = msg.message;
          broadcast(job, msg);
        }
        // "done" is emitted by finalize() below
      }
    } catch (e) {
      job.status = "error";
      job.error = e instanceof Error ? e.message : String(e);
      broadcast(job, { kind: "error", message: job.error });
    }
    finalize(job);
  })();

  return job;
}

/** Explicitly kill a job's process (Stop). Returns false if unknown. */
export function stopJob(ref: { jobId?: string; sessionId?: string }): boolean {
  const job = ref.jobId
    ? jobs.get(ref.jobId)
    : ref.sessionId
      ? getJobBySession(ref.sessionId)
      : undefined;
  if (!job) return false;
  if (job.status === "running") {
    job.ac.abort(); // resume() kills the child on abort
    finalize(job, "stopped");
  }
  return true;
}

/** All jobs currently in the registry (running + within the grace window). */
export function listJobs(): Job[] {
  return Array.from(jobs.values());
}
