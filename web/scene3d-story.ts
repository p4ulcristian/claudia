// The story of a scene3d: steps that say a line, show or hide parts, aim the camera and light up
// parts. Pure logic (no three.js): which parts are on at which step, how long a step takes.
export type V3 = [number, number, number];
export type CameraSpec = { frame?: string[]; target?: string | V3; dist?: number; az?: number; el?: number };
export type Step = { say: string; show: string[]; hide: string[]; focus: string[]; camera: CameraSpec | null; hold: number };

const strings = (v: unknown): string[] => (typeof v === "string" ? [v] : Array.isArray(v) ? v.filter((x): x is string => typeof x === "string") : []);

export function normalizeStory(v: unknown): Step[] {
  if (!Array.isArray(v)) return [];
  return v.filter((s) => s && typeof s === "object").map((s: any) => {
    const c = s.camera && typeof s.camera === "object" ? s.camera : null;
    return {
      say: typeof s.say === "string" ? s.say.trim().slice(0, 1200) : "",
      show: strings(s.show),
      hide: strings(s.hide),
      focus: strings(s.focus),
      camera: c ? { frame: strings(c.frame), target: c.target, dist: Number.isFinite(+c.dist) && c.dist !== undefined ? +c.dist : undefined, az: c.az !== undefined && Number.isFinite(+c.az) ? +c.az : undefined, el: c.el !== undefined && Number.isFinite(+c.el) ? +c.el : undefined } : null,
      hold: Number.isFinite(+s.hold) && +s.hold > 0 ? +s.hold : 0,
    };
  });
}

// "blade*" matches every id starting with "blade"; anything else must match exactly.
export const matches = (pattern: string, id: string) => (pattern.endsWith("*") ? id.startsWith(pattern.slice(0, -1)) : pattern === id);
export const matchesAny = (patterns: string[], id: string) => patterns.some((p) => matches(p, id));

// A part the story never shows is on from the start (the floor, a housing); the rest appear in their step.
export const isManaged = (steps: Step[], id: string) => steps.some((s) => matchesAny(s.show, id));

// The ids that are on after step n (n = -1: before the story starts).
export function visibleAt(steps: Step[], n: number, ids: string[]): Set<string> {
  const on = new Set(ids.filter((id) => !isManaged(steps, id)));
  for (let i = 0; i <= n && i < steps.length; i++) {
    for (const id of ids) {
      if (matchesAny(steps[i].show, id)) on.add(id);
      if (matchesAny(steps[i].hide, id)) on.delete(id);
    }
  }
  return on;
}

const WORDS_PER_SECOND = 2.6;
export const GAP_SECONDS = 0.45;
// How long a step lasts without a recording: the words at a calm pace, or its hold.
export function estimateSeconds(step: Step): number {
  if (!step.say) return Math.max(step.hold, 1.6);
  return Math.max(step.hold, step.say.split(/\s+/).length / WORDS_PER_SECOND + 0.6);
}
