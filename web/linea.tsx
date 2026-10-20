import React, { useEffect, useRef } from "react";

// A little chalk man on the input ledge: a tribute to Osvaldo Cavandoli's La Linea (1969-1986), the
// man made of one line who argues with the hand that draws him. This is our own character, not his;
// watch the real thing at https://www.youtube.com/@LaLineaCavandoli
//
// He acts out what Claude is doing. Working: he walks the ledge. A tool fails: the ledge cracks, he walks
// off the edge, hangs in the air, looks down, drops, climbs out and grumbles (angrier with each failure
// this turn) until a tool succeeds. Then the hand comes to fix it: he shoves it away, the eraser takes his
// leg, he behaves, and the hand draws his leg and the ledge back. Asked something: he taps his foot,
// sighs, lies down, then goes and knocks on the side where the question is. Done: a jump, a bow, he sits.

type Props = { chat: string; loaded: boolean; running: boolean; waiting: boolean; errors: number; oks: number };
type Pt = [number, number];
type Act = "sit" | "walk" | "fall" | "grumble" | "fix" | "bored" | "celebrate";
type Arm = { from: Pt; elbow: Pt; hand: Pt; open: boolean };

const H = 130; // stage height above the ledge
const DEEP = 8; // how far the stage reaches into the ledge (the hole)
const G = H; // the ledge's top edge
const S = 1.25; // his units to px; he is about 38 units tall
const SPEED = 36; // walking, px/s
const GAP = 56; // width of the hole
const FRONT: Pt = [2.5, -21]; // shoulders
const BACK: Pt = [-2.5, -21];
const ANGRY = [["grr…", "hmm…"], ["#@%!", "%&*!"], ["#@%&*!!", "&%#@$!!"]];

const lerp = (a: Pt, b: Pt, t: number): Pt => [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t];
const clamp01 = (u: number) => Math.max(0, Math.min(1, u));
const smoothstep = (u: number) => u * u * (3 - 2 * u);

// His outline, facing right, feet on y=0: heel, back of the leg, a round rump and back, a round head
// with a curl on top, a small nose, mouth, chin, chest, belly, the front leg and the toe. The inner
// edges of the legs are a second, short line. step swings the legs (-1..1), jaw opens the mouth,
// sit blends to sitting with the legs out, erase takes the front leg away.
const SHARP = new Set([0, 1, 13, 20, 29, 30]);
function figure(step: number, jaw: number, sit: number, erase: number): { body: Pt[]; legs: Pt[] | null } {
  const a = 0.42 * Math.abs(step);
  const fr = -2.2 - 12 * Math.tan(a), ff = 2.2 + 12 * Math.tan(a);
  const j = jaw;
  const stand: Pt[] = [
    [fr - 3, 0], [fr - 3.2, -3], [-4, -10], [-5.5, -14], [-5.8, -19], [-4.5, -23], [-3, -25],
    [-5.5, -27.5], [-6.2, -31], [-4.5, -34.5], [-1, -36], [0.5, -38.5], [2.2, -38.2], [1.6, -36.6],
    [3.5, -35.2], [5.5, -32.5], [6, -30], [7.6, -29.2], [6.1, -28.2], [5.9, -27.2], [5.9 - 3 * j, -26.8 + 0.3 * j],
    [5.9 + 0.3 * j, -26.2 + 2.2 * j], [4.5, -24.8 + 1.5 * j], [2.5, -24.2], [5.5, -21.5], [8, -17], [7.5, -13.5],
    [4.5, -11.5], [(ff + 7.7) / 2 + 0.5, -6], [ff + 3.2, -3], [ff + 5, 0],
  ];
  const seated = stand.map((p, i): Pt => (i >= 2 && i <= 26 ? [p[0] - 1, p[1] + 10] : p));
  seated[0] = [-4, 0]; seated[1] = [-4.5, -0.2];
  seated[27] = [8, -3.6]; seated[28] = [14, -3.4]; seated[29] = [17.5, -3]; seated[30] = [19, 0];
  const body = stand.map((p, i) => lerp(p, seated[i], sit));
  for (let i = 28; i <= 30; i++) body[i] = lerp(body[i], body[27], erase);
  if (sit > 0.9) return { body, legs: null };
  const crotch: Pt = [(fr + ff) / 2, -9.5];
  const legs: Pt[] = [[fr + 2.5, 0], [fr + 1.6, -3], crotch, lerp([ff - 1.6, -3], crotch, erase), lerp([ff - 2.5, 0], crotch, erase)];
  return { body, legs: legs.map((p): Pt => [p[0], p[1] * (1 - sit)]) };
}

// A smooth curve through the points (Catmull-Rom as cubic Béziers), with corners at the sharp ones.
function smooth(pts: Pt[], sharp: (i: number) => boolean): string {
  const f = (n: number) => n.toFixed(1);
  let d = `M${f(pts[0][0])},${f(pts[0][1])}`;
  for (let i = 0; i < pts.length - 1; i++) {
    const p1 = pts[i], p2 = pts[i + 1];
    const p0 = sharp(i) ? p1 : pts[i - 1] ?? p1;
    const p3 = sharp(i + 1) ? p2 : pts[i + 2] ?? p2;
    d += ` C${f(p1[0] + (p2[0] - p0[0]) / 6)},${f(p1[1] + (p2[1] - p0[1]) / 6)} ${f(p2[0] - (p3[0] - p1[0]) / 6)},${f(p2[1] - (p3[1] - p1[1]) / 6)} ${f(p2[0])},${f(p2[1])}`;
  }
  return d;
}

// Shoulder, elbow, hand (screen points), then an open hand (three fingers) or a fist.
function armPath([sh, el, h]: Pt[], open: boolean): string {
  const f = (n: number) => n.toFixed(1);
  let d = `M${f(sh[0])},${f(sh[1])}L${f(el[0])},${f(el[1])}L${f(h[0])},${f(h[1])}`;
  if (!open) return d + `M${f(h[0] + 2.2)},${f(h[1])}a2.2,2.2 0 1,0 -4.4,0a2.2,2.2 0 1,0 4.4,0`;
  const len = Math.hypot(h[0] - el[0], h[1] - el[1]) || 1;
  const ux = (h[0] - el[0]) / len, uy = (h[1] - el[1]) / len;
  for (const a of [-0.6, 0, 0.6]) {
    const c = Math.cos(a), s = Math.sin(a);
    d += `M${f(h[0])},${f(h[1])}l${f((ux * c - uy * s) * 4.5)},${f((ux * s + uy * c) * 4.5)}`;
  }
  return d;
}

// Cut [a, b] out of a list of intervals.
function cut(spans: [number, number][], a: number, b: number): [number, number][] {
  return spans.flatMap(([s, e]): [number, number][] => {
    if (b <= s || a >= e) return [[s, e]];
    return [...(a > s ? [[s, a] as [number, number]] : []), ...(b < e ? [[b, e] as [number, number]] : [])];
  });
}

type State = {
  act: Act; sub: string; at: number; subAt: number; t: number;
  x: number; dir: 1 | -1; dy: number; vy: number; hopFrom: number;
  phase: number; legAmp: number; sit: number; lean: number; squash: number; erase: number;
  // The hole: near is the edge he walks off, far the other side; bx is how far the hand has drawn it back.
  gap: { g0: number; g1: number; near: number; far: number; bx: number } | null;
  hand: { on: boolean; x: number; y: number; eraser: boolean };
  pendingError: boolean; okSince: boolean; turnErrors: number; wasRunning: boolean; reset: boolean;
};

const initial = (): State => ({
  act: "sit", sub: "", at: 0, subAt: 0, t: 0, x: 60, dir: 1, dy: 0, vy: 0, hopFrom: 0,
  phase: 0, legAmp: 0, sit: 1, lean: 0, squash: 1, erase: 0, gap: null, hand: { on: false, x: 0, y: -60, eraser: false },
  pendingError: false, okSince: false, turnErrors: 0, wasRunning: false, reset: false,
});

export function Linea(props: Props) {
  const svg = useRef<SVGSVGElement>(null);
  const ground = useRef<SVGPathElement>(null);
  const hole = useRef<SVGPathElement>(null);
  const body = useRef<SVGPathElement>(null);
  const arms = useRef<SVGPathElement>(null);
  const hand = useRef<SVGGElement>(null);
  const pen = useRef<SVGGElement>(null);
  const eraser = useRef<SVGGElement>(null);
  const bubble = useRef<SVGGElement>(null);
  const bubbleBox = useRef<SVGPathElement>(null);
  const bubbleText = useRef<SVGTextElement>(null);
  const sfx = useRef<SVGTextElement>(null);
  const live = useRef(props);
  live.current = props;
  const st = useRef<State>(initial());

  // Count only tool results that arrive while the chat is open; a loaded history doesn't replay.
  const base = useRef({ chat: "", errors: 0, oks: 0 });
  useEffect(() => {
    const b = base.current;
    const s = st.current;
    if (!props.loaded || b.chat !== props.chat) {
      if (b.chat !== props.chat) s.reset = true;
      base.current = { chat: props.loaded ? props.chat : "", errors: props.errors, oks: props.oks };
      return;
    }
    if (props.errors > b.errors) s.pendingError = true;
    else if (props.oks > b.oks && (s.act === "fall" || s.act === "grumble")) s.okSince = true;
    base.current = { chat: props.chat, errors: props.errors, oks: props.oks };
  }, [props.chat, props.loaded, props.errors, props.oks]);

  useEffect(() => {
    let raf = 0;
    let last = performance.now();
    const frame = (now: number) => {
      raf = requestAnimationFrame(frame);
      const dt = Math.min(0.05, (now - last) / 1000);
      last = now;
      const W = svg.current?.clientWidth || 600;
      const p = live.current;
      const s = st.current;
      const hd = s.hand;
      s.t += dt; s.at += dt; s.subAt += dt;

      const go = (act: Act, sub = "") => { s.act = act; s.sub = sub; s.at = 0; s.subAt = 0; };
      const next = (sub: string) => { s.sub = sub; s.subAt = 0; };
      const toward = (v: number, to: number, rate: number) => v + Math.max(-rate * dt, Math.min(rate * dt, to - v));
      const moveHand = (x: number, y: number, rate: number) => { hd.x = toward(hd.x, x, rate); hd.y = toward(hd.y, y, rate); };
      let walking = false;
      const walkTo = (tx: number) => {
        const d = tx - s.x;
        if (Math.abs(d) < 1.5) return true;
        s.dir = d > 0 ? 1 : -1;
        s.x += s.dir * Math.min(Math.abs(d), SPEED * dt);
        walking = true;
        return false;
      };

      if (s.reset) {
        s.reset = false; s.gap = null; hd.on = false; s.erase = 0; s.dy = 0; s.pendingError = false;
        go(p.running ? "walk" : "sit");
      }
      if (p.running && !s.wasRunning) s.turnErrors = 0;
      s.wasRunning = p.running;

      // A failed tool opens a hole ahead of him (or behind, if there's no room ahead) and he walks into it.
      if (s.pendingError) {
        s.pendingError = false;
        s.turnErrors++;
        if (s.act === "walk" || s.act === "sit" || s.act === "celebrate") {
          const fits = (dir: number) => {
            const near = s.x + dir * 26, far = near + dir * GAP;
            return Math.min(near, far) > 8 && Math.max(near, far) < W - 8 ? { near, far } : null;
          };
          const ahead = fits(s.dir), behind = fits(-s.dir);
          const g = ahead ?? behind;
          if (g) {
            if (!ahead) s.dir = s.dir === 1 ? -1 : 1;
            s.gap = { g0: Math.min(g.near, g.far), g1: Math.max(g.near, g.far), near: g.near, far: g.far, bx: g.near };
            s.okSince = false;
            go("fall", "walkin");
          }
        }
      }

      let sitT = 0, leanT = 0, leanRate = 3, squashT = 1, jaw = 0, step = 0, bob = 0;
      const arm: Arm[] = [];
      let say: { text: string; angry?: number } | null = null;
      let boom: { text: string; x: number } | null = null;
      const bothUp = () => arm.push({ from: FRONT, elbow: [6, -32], hand: [8, -40], open: true }, { from: BACK, elbow: [-6, -32], hand: [-8, -40], open: true });

      switch (s.act) {
        case "sit": {
          sitT = 1;
          s.x = Math.max(20, Math.min(W - 30, s.x));
          if (p.waiting) go("bored", "tap");
          else if (p.running) go("walk");
          break;
        }
        case "walk": {
          if (s.sit < 0.05) {
            walking = true;
            s.x += s.dir * SPEED * dt;
            if (s.x > W - 30) s.dir = -1;
            if (s.x < 24) s.dir = 1;
          }
          if (p.waiting) go("bored", "tap");
          else if (!p.running) go("celebrate");
          break;
        }
        case "fall": {
          const g = s.gap!;
          if (s.sub === "walkin") {
            if (s.sit < 0.05) { walking = true; s.x += s.dir * SPEED * dt; }
            if ((s.x - g.near) * s.dir > 10) next("air");
          } else if (s.sub === "air") {
            // Still walking: he hasn't noticed.
            walking = true;
            s.x += s.dir * SPEED * dt;
            if (s.subAt > 0.55) next("hang");
          } else if (s.sub === "hang") {
            if (s.subAt > 0.45) next("look");
          } else if (s.sub === "look") {
            leanT = 0.45; leanRate = 4;
            if (s.subAt > 0.15) say = { text: "!" };
            if (s.subAt > 0.8) next("gulp");
          } else if (s.sub === "gulp") {
            squashT = 0.9;
            say = { text: "uh-oh" };
            if (s.subAt > 0.5) { s.vy = 0; next("drop"); }
          } else if (s.sub === "drop") {
            s.vy += 1400 * dt;
            s.dy += s.vy * dt;
            if (s.dy > 80) { s.dy = 80; next("under"); }
          } else if (s.sub === "under") {
            if (s.subAt < 0.7) boom = { text: "bonk", x: (g.g0 + g.g1) / 2 };
            if (s.subAt > 0.9) { s.x = g.near + s.dir * 12; s.lean = 0; s.squash = 1; next("climb"); }
          } else if (s.sub === "climb") {
            // Hands first, then the head, out of the hole.
            const u = smoothstep(clamp01(s.subAt / 1.1));
            s.dy = 60 * (1 - u);
            arm.push({ from: FRONT, elbow: [4, -36], hand: [4, -43], open: true }, { from: BACK, elbow: [-3, -36], hand: [-2, -43], open: true });
            if (s.subAt > 1.1) { s.hopFrom = s.x; next("hop"); }
          } else if (s.sub === "hop") {
            const u = clamp01(s.subAt / 0.4);
            s.x = s.hopFrom + (g.near - s.dir * 18 - s.hopFrom) * u;
            s.dy = -12 * Math.sin(Math.PI * u);
            if (u >= 1) { s.dy = 0; go("grumble"); }
          }
          break;
        }
        case "grumble": {
          const lvl = Math.min(3, Math.max(1, s.turnErrors));
          jaw = 0.5 + 0.5 * Math.sin(s.t * 22);
          bob = 0.5 * lvl * Math.sin(s.t * 31);
          const w = Math.sin(s.t * (10 + 4 * lvl));
          arm.push({ from: FRONT, elbow: [8, -23], hand: [11 + 1.5 * w, -30 + 1.5 * w], open: false });
          say = { text: ANGRY[lvl - 1][Math.floor(s.t * 3) % 2], angry: lvl };
          if (!s.gap) { if (s.at > 1.8) go(p.running ? "walk" : "celebrate"); }
          else if ((s.okSince && s.at > 1.8) || (!p.running && s.at > 1.8) || s.at > 8) go("fix", "descend");
          break;
        }
        case "fix": {
          const g = s.gap!;
          const foot = s.x + s.dir * 6 * S;
          if (s.sub === "descend") {
            hd.on = true; hd.eraser = false;
            const u = smoothstep(clamp01(s.subAt / 0.6));
            hd.x = g.near + s.dir * 8;
            hd.y = -40 + (G + 40) * u;
            if (u >= 1) next("shove");
          } else if (s.sub === "shove") {
            // He steps up and pushes the pencil away.
            if (s.subAt < 0.3) { walking = true; s.x += s.dir * 20 * dt; }
            else {
              arm.push({ from: FRONT, elbow: [9, -21], hand: [16, -21], open: true });
              say = { text: "hmph!" };
            }
            if (s.subAt > 0.35) moveHand(g.near + s.dir * 34, G - 50, 320);
            if (s.subAt > 0.9) next("regroup");
          } else if (s.sub === "regroup") {
            hd.eraser = s.subAt > 0.3;
            hd.y += Math.sin(s.t * 6) * 0.3;
            if (s.subAt > 0.6) next("erase");
          } else if (s.sub === "erase") {
            if (s.subAt < 0.3) moveHand(foot, G - 3, 420);
            else {
              hd.x = foot + 4 * Math.sin(s.subAt * 40);
              hd.y = G - 3;
              s.erase = clamp01((s.subAt - 0.3) / 0.7);
            }
            if (s.subAt > 1.1) next("wobble");
          } else if (s.sub === "wobble") {
            moveHand(foot + s.dir * 22, G - 45, 200);
            leanT = 0.12 * Math.sin(s.t * 9); leanRate = 10;
            say = { text: "?!" };
            if (s.subAt > 1) next("behave");
          } else if (s.sub === "behave") {
            squashT = 0.95; leanT = 0.12;
            say = { text: "…ok" };
            if (s.subAt > 0.8) next("redraw");
          } else if (s.sub === "redraw") {
            hd.eraser = false;
            const u = clamp01(s.subAt / 0.7);
            moveHand(foot, G - 3 - 12 * u, 420);
            s.erase = 1 - u;
            if (u >= 1) { g.bx = g.near; next("bridge"); }
          } else if (s.sub === "bridge") {
            if (s.subAt < 0.3) moveHand(g.near, G, 420);
            else {
              g.bx = toward(g.bx, g.far, 45);
              hd.x = g.bx;
              hd.y = G + Math.sin(s.t * 18) * 0.8;
              if (g.bx === g.far) next("leave");
            }
          } else if (s.sub === "leave") {
            moveHand(hd.x + s.dir * 20, -60, 500);
            if (s.subAt > 0.5) { hd.on = false; s.gap = null; go(p.running ? "walk" : "celebrate"); }
          }
          break;
        }
        case "bored": {
          if (!p.waiting) { go(p.running ? "walk" : "sit"); break; }
          s.x = Math.max(20, Math.min(W - 26, s.x));
          if (s.sub === "tap") {
            s.dir = 1;
            step = 0.5 * Math.max(0, Math.sin(s.t * 9));
            if (s.subAt > 3) next("sigh");
          } else if (s.sub === "sigh") {
            squashT = 0.9;
            say = { text: "sigh…" };
            if (s.subAt > 1.2) next("lie");
          } else if (s.sub === "lie") {
            leanT = -Math.PI / 2; leanRate = 4;
            if (s.subAt > 1) say = { text: ["z", "z z", "z z z"][Math.floor(s.t * 2) % 3] };
            if (s.subAt > 5) next("up");
          } else if (s.sub === "up") {
            leanRate = 4;
            if (s.lean > -0.05) next("goknock");
          } else if (s.sub === "goknock") {
            if (walkTo(W - 26)) { s.dir = 1; next("knock"); }
          } else if (s.sub === "knock") {
            // On the side where the question waits.
            const c = s.subAt % 3.5;
            if (c < 1.1) {
              const k = Math.abs(Math.sin(c * 9));
              arm.push({ from: FRONT, elbow: [8, -22], hand: [11 + 2 * k, -25], open: false });
              say = { text: "knock knock" };
            }
          }
          break;
        }
        case "celebrate": {
          if (p.running) { go("walk"); break; }
          if (p.waiting) { go("bored", "tap"); break; }
          const a = s.at;
          if (a < 0.15) squashT = 0.85;
          else if (a < 0.65) {
            s.dy = -16 * Math.sin(Math.PI * (a - 0.15) / 0.5);
            bothUp();
            say = { text: "ta-da!" };
          } else if (a < 0.8) { s.dy = 0; squashT = 0.85; }
          else if (a < 1.6) { leanT = 0.5; leanRate = 3; }
          else go("sit");
          break;
        }
      }

      s.sit = toward(s.sit, sitT, 2.5);
      s.lean = toward(s.lean, leanT, leanRate);
      s.squash = toward(s.squash, squashT, 4);
      s.legAmp = toward(s.legAmp, walking ? 1 : 0, 6);
      if (s.legAmp > 0.01) s.phase += dt * 6.5;
      step += s.legAmp * Math.sin(s.phase);

      // Pose to screen: lean about the feet, squash, keep the lowest point on the ledge, then place.
      const fig = figure(step, jaw, s.sit, s.erase);
      const c = Math.cos(s.lean), sn = Math.sin(s.lean);
      const rot = (q: Pt): Pt => [q[0] * c - q[1] * sn, (q[0] * sn + q[1] * c) * s.squash];
      const lift = Math.max(0, ...fig.body.map((q) => rot(q)[1]));
      const T = (q: Pt): Pt => { const r = rot(q); return [s.x + s.dir * r[0] * S, G + s.dy + bob + (r[1] - lift) * S]; };
      let d = smooth(fig.body.map(T), (i) => SHARP.has(i));
      if (fig.legs) d += smooth(fig.legs.map(T), (i) => i === 0 || i === 2 || i === 4);
      body.current?.setAttribute("d", d);
      arms.current?.setAttribute("d", arm.map((a) => armPath([a.from, a.elbow, a.hand].map(T), a.open)).join(""));

      // The ledge line, with the hole cut out of it and filled dark.
      let spans: [number, number][] = [[0, W]];
      let holeD = "";
      const g = s.gap;
      if (g) {
        const [a, b] = g.near < g.far ? [Math.max(g.g0, g.bx), g.g1] : [g.g0, Math.min(g.g1, g.bx)];
        if (b > a) {
          spans = cut(spans, a, b);
          holeD = `M${a.toFixed(1)},${G - 1}h${(b - a).toFixed(1)}v${DEEP + 1}h${(a - b).toFixed(1)}z`;
        }
      }
      ground.current?.setAttribute("d", spans.map(([a, b]) => `M${a.toFixed(1)},${G}L${b.toFixed(1)},${G}`).join(""));
      hole.current?.setAttribute("d", holeD);

      hand.current?.setAttribute("transform", `translate(${hd.x.toFixed(1)},${hd.y.toFixed(1)})`);
      hand.current?.setAttribute("visibility", hd.on ? "visible" : "hidden");
      pen.current?.setAttribute("visibility", hd.eraser ? "hidden" : "inherit");
      eraser.current?.setAttribute("visibility", hd.eraser ? "inherit" : "hidden");

      // Speech bubble over his head.
      if (say && bubble.current && bubbleBox.current && bubbleText.current) {
        const size = 13 + 2 * ((say.angry ?? 1) - 1);
        const w = say.text.length * size * 0.55 + 16, h = size + 10;
        const [hx, hy] = T([1, -40]);
        const bx = Math.max(2, Math.min(W - w - 2, hx - w / 2 + s.dir * 8));
        const by = Math.max(2, hy - h - 10);
        const tx = Math.max(bx + 10, Math.min(bx + w - 10, hx));
        bubbleBox.current.setAttribute("d",
          `M${bx + 6},${by}h${w - 12}q6,0 6,6v${h - 12}q0,6 -6,6H${tx + 5}L${hx},${by + h + 7}L${tx - 3},${by + h}H${bx + 6}q-6,0 -6,-6v${-(h - 12)}q0,-6 6,-6z`);
        bubbleText.current.setAttribute("x", (bx + w / 2).toFixed(1));
        bubbleText.current.setAttribute("y", (by + h - 7).toFixed(1));
        bubbleText.current.setAttribute("font-size", String(size));
        bubbleText.current.textContent = say.text;
        bubble.current.setAttribute("class", `bubble ${(say.angry ?? 0) >= 2 ? "angry" : ""}`);
        bubble.current.setAttribute("visibility", "visible");
      } else bubble.current?.setAttribute("visibility", "hidden");

      if (sfx.current) {
        sfx.current.setAttribute("visibility", boom ? "visible" : "hidden");
        if (boom) { sfx.current.setAttribute("x", boom.x.toFixed(1)); sfx.current.textContent = boom.text; }
      }
    };
    raf = requestAnimationFrame(frame);
    return () => cancelAnimationFrame(raf);
  }, []);

  return (
    <svg ref={svg} className="linea" height={H + DEEP} aria-hidden="true">
      <defs>
        {/* He disappears into the ledge when he falls. */}
        <clipPath id="linea-above">
          <rect x={-1000} y={-1000} width={20000} height={G + 1000} />
        </clipPath>
      </defs>
      <path ref={hole} className="hole" />
      <path ref={ground} className="line" />
      <g clipPath="url(#linea-above)">
        <path ref={body} className="line" />
        <path ref={arms} className="line" />
      </g>
      <text ref={sfx} className="sfx" y={G - 8} visibility="hidden" />
      <g ref={bubble} className="bubble" visibility="hidden">
        <path ref={bubbleBox} />
        <text ref={bubbleText} />
      </g>
      {/* The hand that draws him: a chalk hand with a pencil, eraser end down when it erases. */}
      <g ref={hand} className="hand" visibility="hidden">
        <g ref={pen}>
          <path className="pencil" d="M2,-4 L16,-34" />
          <path className="lead" d="M0,0 L2,-4.3" />
        </g>
        <g ref={eraser} visibility="hidden">
          <path className="pencil" d="M3,-6 L16,-34" />
          <path className="rubber" d="M0.5,-1 L3,-6.4" />
        </g>
        <path className="sleeve" d="M26,-46 L48,-140 M36,-40 L62,-140" />
        <path className="glove" d="M12,-30 c-6,-6 -2,-16 8,-16 c8,0 14,6 12,14 c-1,5 -6,8 -12,8 c-4,0 -6,-2 -8,-6 z" />
        <path className="sleeve" d="M15,-33 q5,3 10,0 M17,-39 q5,3 10,0" />
      </g>
    </svg>
  );
}
