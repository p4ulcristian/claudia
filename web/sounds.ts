// Sounds, made here with Web Audio (no files): each chat colour has its own note, so you can hear
// which chat it was. A finished turn rings its note once like a small bell; a chat waiting on you
// asks with two notes going up. Off unless turned on (the toggle in the sidebar, kept per device).
import { CHAT_COLORS } from "../src/colors";

// A major pentatonic from C4 upwards: any two notes sound fine together, and neighbouring colours
// (the list goes round the hues) get neighbouring notes. Index 0 = no colour.
const STEPS = [0, 2, 4, 7, 9];
const midi = (i: number) => 60 + 12 * Math.floor(i / STEPS.length) + STEPS[i % STEPS.length];
const hz = (m: number) => 440 * 2 ** ((m - 69) / 12);
const noteOf = (color: string | null) => midi(color ? (CHAT_COLORS as readonly string[]).indexOf(color) + 1 : 0);

let ctx: AudioContext | null = null;
const audio = () => (ctx ??= new AudioContext());

// The browser lets a page play only after a click or key: wake the context on the first one.
for (const ev of ["pointerdown", "keydown"]) addEventListener(ev, () => { if (ctx?.state === "suspended") void ctx.resume(); }, { capture: true });

// A soft bell: a sine with a faint octave above, a gentle start and a fade.
function bell(m: number, at: number, gain = 0.045, length = 0.9) {
  const a = audio();
  const out = a.createGain();
  out.gain.setValueAtTime(0, at);
  out.gain.linearRampToValueAtTime(gain, at + 0.03);
  out.gain.exponentialRampToValueAtTime(0.0001, at + length);
  out.connect(a.destination);
  for (const [mult, g] of [[1, 1], [2, 0.12]] as const) {
    const o = a.createOscillator();
    const og = a.createGain();
    o.type = "sine";
    o.frequency.value = hz(m) * mult;
    og.gain.value = g;
    o.connect(og).connect(out);
    o.start(at);
    o.stop(at + length + 0.05);
  }
}

export type SoundKind = "done" | "waiting";

export function play(kind: SoundKind, color: string | null) {
  const a = audio();
  if (a.state === "suspended") void a.resume();
  const m = noteOf(color);
  const t = a.currentTime + 0.02;
  if (kind === "done") bell(m, t);
  else {
    bell(m, t, 0.035, 0.5);
    bell(m + 4, t + 0.16, 0.035, 0.8);
  }
}
