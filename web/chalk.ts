// Chalk, baked (DESIGN.md "Chalk technique"). Every chalk edge on the page (outlines, circles,
// lines, dots) is a mask image drawn once, when the page loads, by this file: the shape with its
// edge roughed up by noise, the way the old live SVG filters (feTurbulence + feDisplacementMap)
// did it on every repaint. The CSS paints the chalk's colour through these masks, which costs
// nothing per frame; Safari redrew the live filters in software on every frame (13 fps scrolling
// the chat, 1.6 s to switch chats).
//
// The noise is the SVG spec's own turbulence (fractalNoise, the spec's reference code), and each
// pixel takes the shape's coverage at its displaced point, so an edge looks as the filters drew it.
// Images are drawn at the screen's pixel ratio, so the grain is as fine as it was.
//
// Two kinds of image:
// - shapes (`--chalk-<name>`): a rounded box, ring or solid, as a nine-slice mask
//   (`-webkit-mask-box-image`), so one image fits a box of any size: corners as they are, edges
//   repeated. A chalk outline is a ::before with `inset: -2px` (room for the rough edge outside the
//   box), the chalk colour as its background and the ring as its mask; lines and dots are solid shapes.
// - grain (`--chalk-grain`, and `grainUrl()` for SVG <mask>s): a tile of the same noise for what is
//   drawn as SVG (the picture's graph, icons, charts), whose shapes the page doesn't know ahead.

// The three noises the filters had: name → [baseFrequency, seed, displacement scale].
const NOISES = {
  edge: [0.85, 3, 2.4], // outlines and circles (was #tip-chalk)
  soft: [0.9, 3, 1.3], // small strokes (was #chalk-soft)
  line: [0.7, 5, 3], // thin lines (was #chalk-line)
} as const;
type NoiseName = keyof typeof NOISES;

// Room outside a shape for its rough edge, in CSS px (the largest displacement is scale / 2 = 1.5).
// The CSS insets each chalk ::before by this much.
export const SPREAD = 2;
// Length of the repeated edge piece of a nine-slice, in CSS px.
const TILE = 64;

type Shape = {
  r: number; // corner radius (half the size for a circle or a line)
  b?: number; // ring width; none = solid
  dash?: number; // dashed ring: dash length (gaps the same)
  noise?: NoiseName;
};

// Every shape the CSS uses, by name: var(--chalk-<name>).
const SHAPES: Record<string, Shape> = {
  // outlines: r<radius>-<width × 10>
  "r5-15": { r: 5, b: 1.5 },
  "r6-15": { r: 6, b: 1.5 },
  "r7-15": { r: 7, b: 1.5 },
  "r7-2": { r: 7, b: 2 },
  "r8-15": { r: 8, b: 1.5 },
  "r8-15-dash": { r: 8, b: 1.5, dash: 4 },
  "r8-2": { r: 8, b: 2 },
  "r9-15": { r: 9, b: 1.5 },
  "r9-2": { r: 9, b: 2 },
  "r10-2": { r: 10, b: 2 },
  "r12-15": { r: 12, b: 1.5 },
  "r12-2": { r: 12, b: 2 },
  "r12-2-dash": { r: 12, b: 2, dash: 6 },
  "r14-2": { r: 14, b: 2 },
  "r18-2": { r: 18, b: 2 },
  // circles: c<diameter>-<width × 10>
  "c30-18": { r: 15, b: 1.8 },
  "c30-22": { r: 15, b: 2.2 },
  "c32-2": { r: 16, b: 2 },
  "c34-22": { r: 17, b: 2.2 },
  "c34-18-dash": { r: 17, b: 1.8, dash: 2.6 },
  // solid: lines (a line's ::before is the line plus SPREAD each side), dots, bars
  "line2": { r: 1, noise: "line" },
  "line3": { r: 1.5, noise: "line" },
  "line3-soft": { r: 1.5, noise: "soft" },
  "dot9-soft": { r: 4.5, noise: "soft" },
  "dot11": { r: 5.5 },
  "dot18": { r: 9 },
  "dot56": { r: 28 },
  "bar3": { r: 3 },
  "bar35": { r: 3.5 },
};

// ---- The SVG spec's turbulence (Filter Effects, feTurbulence reference code) ----
const B = 0x100, BM = 0xff, PERLIN_N = 0x1000;

class Turbulence {
  private lattice = new Int32Array(B + B + 2);
  private gradient = [0, 1, 2, 3].map(() => new Float64Array((B + B + 2) * 2));
  constructor(seed: number) {
    const M = 2147483647, A = 16807, Q = 127773, R = 2836;
    const random = (s: number) => {
      const r = A * (s % Q) - R * Math.trunc(s / Q);
      return r <= 0 ? r + M : r;
    };
    if (seed <= 0) seed = -(seed % (M - 1)) + 1;
    if (seed > M - 1) seed = M - 1;
    let i = 0;
    for (let k = 0; k < 4; k++) {
      const g = this.gradient[k];
      for (i = 0; i < B; i++) {
        this.lattice[i] = i;
        for (let j = 0; j < 2; j++) g[i * 2 + j] = ((seed = random(seed)) % (B + B) - B) / B;
        const s = Math.hypot(g[i * 2], g[i * 2 + 1]);
        g[i * 2] /= s;
        g[i * 2 + 1] /= s;
      }
    }
    while (--i) {
      const k = this.lattice[i];
      const j = (seed = random(seed)) % B;
      this.lattice[i] = this.lattice[j];
      this.lattice[j] = k;
    }
    for (i = 0; i < B + 2; i++) {
      this.lattice[B + i] = this.lattice[i];
      for (let k = 0; k < 4; k++) for (let j = 0; j < 2; j++) this.gradient[k][(B + i) * 2 + j] = this.gradient[k][i * 2 + j];
    }
  }
  private noise2(ch: number, x: number, y: number) {
    const sCurve = (t: number) => t * t * (3 - 2 * t);
    let t = x + PERLIN_N;
    const bx0 = Math.trunc(t) & BM, bx1 = (bx0 + 1) & BM;
    const rx0 = t - Math.trunc(t), rx1 = rx0 - 1;
    t = y + PERLIN_N;
    const by0 = Math.trunc(t) & BM, by1 = (by0 + 1) & BM;
    const ry0 = t - Math.trunc(t), ry1 = ry0 - 1;
    const i = this.lattice[bx0], j = this.lattice[bx1];
    const b00 = this.lattice[i + by0], b10 = this.lattice[j + by0], b01 = this.lattice[i + by1], b11 = this.lattice[j + by1];
    const sx = sCurve(rx0), sy = sCurve(ry0);
    const g = this.gradient[ch];
    let u = rx0 * g[b00 * 2] + ry0 * g[b00 * 2 + 1];
    let v = rx1 * g[b10 * 2] + ry0 * g[b10 * 2 + 1];
    const a = u + sx * (v - u);
    u = rx0 * g[b01 * 2] + ry1 * g[b01 * 2 + 1];
    v = rx1 * g[b11 * 2] + ry1 * g[b11 * 2 + 1];
    const b = u + sx * (v - u);
    return a + sy * (b - a);
  }
  // fractalNoise, two octaves, as a colour value 0..1.
  fractal(ch: number, x: number, y: number, freq: number) {
    const sum = this.noise2(ch, x * freq, y * freq) + this.noise2(ch, x * freq * 2, y * freq * 2) / 2;
    return Math.min(1, Math.max(0, (sum + 1) / 2));
  }
}

const turbulences = new Map<number, Turbulence>();
const turbulence = (seed: number) => {
  let t = turbulences.get(seed);
  if (!t) turbulences.set(seed, (t = new Turbulence(seed)));
  return t;
};

// Signed distance from p to a box centred at 0 with half sizes hx, hy and corner radius r (< 0 inside).
function roundBox(px: number, py: number, hx: number, hy: number, r: number) {
  const qx = Math.abs(px) - hx + r, qy = Math.abs(py) - hy + r;
  return Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) + Math.min(Math.max(qx, qy), 0) - r;
}

// The screen's pixel ratio, whole (1, 2 or 3): images drawn at it keep the grain one pixel fine.
const DPR = Math.min(3, Math.max(1, Math.ceil(globalThis.devicePixelRatio || 1)));

// One shape as a white image with the chalk's alpha: size × size CSS px, the shape inset SPREAD.
function bake(size: number, shape: Shape): string {
  const [freq, seed, scale] = NOISES[shape.noise ?? "edge"];
  const noise = turbulence(seed);
  const px = Math.round(size * DPR);
  const canvas = document.createElement("canvas");
  canvas.width = canvas.height = px;
  const ctx = canvas.getContext("2d")!;
  const img = ctx.createImageData(px, px);
  const half = size / 2 - SPREAD, r = Math.min(shape.r, half);
  const aa = 1 / DPR;
  const cover = (d: number) => Math.min(1, Math.max(0, 0.5 - d / aa));
  for (let y = 0; y < px; y++) {
    for (let x = 0; x < px; x++) {
      // CSS px from the centre; the noise is read at the pixel's own place, as the filter did.
      const cx = (x + 0.5) / DPR, cy = (y + 0.5) / DPR;
      const ox = cx - size / 2, oy = cy - size / 2;
      // Far from the edge: nothing to roughen (inside a ring, or out of a solid shape's reach).
      const d0 = roundBox(ox, oy, half, half, r);
      if (d0 > scale) continue;
      if (shape.b !== undefined && d0 < -shape.b - scale) continue;
      const dx = scale * (noise.fractal(0, cx, cy, freq) - 0.5);
      const dy = scale * (noise.fractal(1, cx, cy, freq) - 0.5);
      const sx = ox + dx, sy = oy + dy;
      const d = roundBox(sx, sy, half, half, r);
      let a = cover(d);
      if (shape.b !== undefined) a -= cover(d + shape.b);
      if (shape.dash && a > 0) a *= dashAt(sx, sy, half, r, shape.b ?? 0, shape.dash);
      const i = (y * px + x) * 4;
      img.data[i] = img.data[i + 1] = img.data[i + 2] = 255;
      img.data[i + 3] = Math.round(a * 255);
    }
  }
  ctx.putImageData(img, 0, 0);
  return canvas.toDataURL("image/png");
}

// A dashed ring: 1 on a dash, 0 in a gap, by the distance along the ring's middle line. Straight
// edges count from the edge's middle, so the repeated edge piece (TILE long) stays in step.
function dashAt(x: number, y: number, half: number, r: number, b: number, dash: number) {
  const straight = half - r;
  const ax = Math.abs(x), ay = Math.abs(y);
  let along: number;
  if (ax <= straight) along = ax; // top or bottom edge
  else if (ay <= straight) along = ay; // left or right edge
  else along = straight + Math.atan2(ay - straight, ax - straight) * Math.max(0.5, r - b / 2); // a corner
  const period = TILE / Math.max(1, Math.round(TILE / (dash * 2)));
  const p = (((along + period / 4) % period) + period) % period;
  return p < period / 2 ? 1 : 0;
}

// A shape as a nine-slice mask: corners K wide, a TILE long edge between them, repeated.
function nineSlice(shape: Shape) {
  // The corner: the radius and the room outside it (a circle or a line is all corners: its box is 2K).
  const k = SPREAD + Math.max(shape.r, shape.b ?? 0);
  const size = 2 * k + TILE;
  const url = bake(size, shape);
  const fill = shape.b === undefined ? " fill" : "";
  return `url(${url}) ${k * DPR}${fill} / ${k}px repeat`;
}

// A grain tile: the noise as alpha, a soft step from gap to solid at `cut`.
function grain(size: number, noiseName: NoiseName, cut: number, soft: number): string {
  const [freq, seed] = NOISES[noiseName];
  const noise = turbulence(seed + 11);
  const px = Math.round(size * DPR);
  const canvas = document.createElement("canvas");
  canvas.width = canvas.height = px;
  const ctx = canvas.getContext("2d")!;
  const img = ctx.createImageData(px, px);
  for (let y = 0; y < px; y++) {
    for (let x = 0; x < px; x++) {
      const n = noise.fractal(0, (x + 0.5) / DPR, (y + 0.5) / DPR, freq * 1.4);
      // Bright noise stays, dark noise is a gap: a soft step around `cut`.
      const a = Math.min(1, Math.max(0, (n - cut) / soft));
      const i = (y * px + x) * 4;
      img.data[i] = img.data[i + 1] = img.data[i + 2] = 255;
      img.data[i + 3] = Math.round(a * 255);
    }
  }
  ctx.putImageData(img, 0, 0);
  return canvas.toDataURL("image/png");
}

const GRAIN_TILE = 48;
// edge, soft: mostly solid with chalk's gaps (icons, small marks); fray: about half gaps, for a
// wider copy of a stroke drawn with it, so the stroke's edge frays the way the displacement made it.
const GRAINS = { edge: ["edge", 0.24, 0.06], soft: ["soft", 0.22, 0.06], fray: ["edge", 0.46, 0.1] } as const;
export type GrainKind = keyof typeof GRAINS;
const grains = new Map<GrainKind, string>();
// The grain tile as a data URL (SVG <pattern> images: the picture's graph, the git and clojure maps).
export function grainUrl(kind: GrainKind = "edge") {
  let url = grains.get(kind);
  if (!url) grains.set(kind, (url = grain(GRAIN_TILE, GRAINS[kind][0], GRAINS[kind][1], GRAINS[kind][2])));
  return url;
}
export const GRAIN_SIZE = GRAIN_TILE;

// Bake every shape once and hand them to the CSS as custom properties on :root.
export function bakeChalk() {
  const root = document.documentElement.style;
  for (const [name, shape] of Object.entries(SHAPES)) root.setProperty(`--chalk-${name}`, nineSlice(shape));
  root.setProperty("--chalk-grain", `url(${grainUrl("edge")})`);
  root.setProperty("--chalk-grain-soft", `url(${grainUrl("soft")})`);
  root.setProperty("--chalk-grain-size", `${GRAIN_TILE}px`);
}
