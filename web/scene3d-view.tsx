// The three.js side of the scene3d form (loaded on demand by scene3d.tsx). Parts and arrows come
// from the form's data; an optional `script` (run in QuickJS, web/scene3d-vm.ts) can add more of
// them and move parts every frame.
import React, { useContext, useEffect, useRef, useState } from "react";
import { PictureSend } from "./editor";
import * as THREE from "three";
import { OrbitControls } from "three/examples/jsm/controls/OrbitControls.js";
import { CSS2DObject, CSS2DRenderer } from "three/examples/jsm/renderers/CSS2DRenderer.js";
import { loadScript, type SceneScript } from "./scene3d-vm";
import { isSolid, makeChecker, type Overlap } from "./scene3d-overlap";
import { GAP_SECONDS, estimateSeconds, matchesAny, normalizeStory, visibleAt, type CameraSpec, type Step } from "./scene3d-story";
import { Voice } from "./scene3d-voice";
import { useApi } from "./api";

// Drawn icons (chalk strokes), not emoji: the same hand as the rest of the board.
const ICONS = {
  play: "M6.5 4.5v11l9-5.5z",
  pause: "M7 4.5v11M13 4.5v11",
  prev: "M5 4.5v11M15 4.5l-8 5.5 8 5.5z",
  next: "M15 4.5v11M5 4.5l8 5.5-8 5.5z",
  volume: "M3 8v4h3l4 3.5v-11L6 8zM13 7.5a3.5 3.5 0 0 1 0 5M15.2 5.3a6.5 6.5 0 0 1 0 9.4",
  mute: "M3 8v4h3l4 3.5v-11L6 8zM13.5 8l4 4M17.5 8l-4 4",
  reset: "M4.5 10a5.5 5.5 0 1 0 1.8-4.1M4.5 3.5v3.7h3.7",
  warn: "M10 3.5l7 12.2H3zM10 8.3v3.2M10 13.6v.4",
  check: "M4 10.5l4 4 8-9",
  close: "M5 5l10 10M15 5L5 15",
};
const Icon = ({ name }: { name: keyof typeof ICONS }) => (
  <svg className="s3-ic" viewBox="0 0 20 20" aria-hidden="true"><path d={ICONS[name]} /></svg>
);

type Data = Record<string, any>;
type Picked = { id: string; title: string; x: number; y: number; rows: [string, string][]; note?: string; shape: string; color: string; parent?: string };
type V3 = [number, number, number];
const list = (v: unknown): any[] => (Array.isArray(v) ? v : []);
const V = (v: unknown, d: V3 = [0, 0, 0]): V3 => (Array.isArray(v) && v.length >= 3 ? [Number(v[0]) || 0, Number(v[1]) || 0, Number(v[2]) || 0] : d);
const DEG = Math.PI / 180;
const SPEEDS = [0.25, 0.5, 1, 2];
const UP = new THREE.Vector3(0, 1, 0);
const SCAN_SECONDS = 12;
const HEIGHT_KEY = "claudia.scene3d.height";
const HEIGHT_MIN = 220;
const clampHeight = (h: number) => Math.round(Math.min(Math.max(h, HEIGHT_MIN), Math.max(innerHeight * 1.5, 600)));
const pairKey = ([a, b]: Overlap) => `${a} × ${b}`;

// A theme colour name (green, amber…) or any CSS colour.
function colorOf(host: HTMLElement, c: unknown, fallback = "blue"): THREE.Color {
  const s = typeof c === "string" && c ? c : fallback;
  const css = /^[a-z]+$/.test(s) ? getComputedStyle(host).getPropertyValue(`--${s}`).trim() || s : s;
  try {
    return new THREE.Color(css);
  } catch {
    return new THREE.Color(0x888888);
  }
}

// size: one number (the whole part) or [x, y, z].
function sizeOf(s: unknown): V3 {
  return typeof s === "number" ? [s, s, s] : V(s, [1, 1, 1]);
}

// Unit-sized geometry per shape; size scales it. Extrude / lathe / tube build from the part's points.
function geometryOf(p: Data): THREE.BufferGeometry {
  const pts2 = list(p.points).map((q) => new THREE.Vector2(Number(q[0]), Number(q[1])));
  switch (p.shape) {
    case "group": return new THREE.BufferGeometry();
    case "sphere": return new THREE.SphereGeometry(0.5, 40, 24);
    case "cylinder": return new THREE.CylinderGeometry(0.5, 0.5, 1, 40);
    case "rod": return new THREE.CylinderGeometry(1, 1, 1, 16);
    case "cone": return new THREE.ConeGeometry(0.5, 1, 40);
    case "pyramid": return new THREE.ConeGeometry(0.5 * Math.SQRT2, 1, 4).rotateY(Math.PI / 4);
    case "torus": return new THREE.TorusGeometry(0.4, 0.1, 20, 56);
    case "capsule": return new THREE.CapsuleGeometry(0.25, 0.5, 8, 24);
    case "plane": return new THREE.PlaneGeometry(1, 1).rotateX(-Math.PI / 2);
    case "ring": return new THREE.RingGeometry(0.3, 0.5, 48).rotateX(-Math.PI / 2);
    case "extrude":
      return pts2.length < 3 ? new THREE.BoxGeometry(1, 1, 1) : new THREE.ExtrudeGeometry(new THREE.Shape(pts2), { depth: 1, bevelEnabled: false }).center();
    case "lathe": return pts2.length >= 2 ? new THREE.LatheGeometry(pts2, 48) : new THREE.SphereGeometry(0.5, 32, 16);
    case "tube": {
      const path = list(p.points).map((q) => new THREE.Vector3(...V(q)));
      if (path.length < 2) return new THREE.BoxGeometry(1, 1, 1);
      return new THREE.TubeGeometry(new THREE.CatmullRomCurve3(path), path.length * 16, Number(p.radius) || 0.08, 12, false);
    }
    default: return new THREE.BoxGeometry(1, 1, 1);
  }
}

function labelEl(text: string, note: string | undefined, color: string): HTMLDivElement {
  const d = document.createElement("div");
  d.className = "s3-label";
  d.style.setProperty("--c", color);
  const b = document.createElement("b");
  b.textContent = text;
  d.append(b);
  if (note) {
    const n = document.createElement("span");
    n.textContent = note;
    d.append(n);
  }
  return d;
}

// A rod is a cylinder between two points (a connecting rod, a strut): it follows its ends.
function placeRod(m: THREE.Mesh, from: V3, to: V3, radius: number) {
  const a = new THREE.Vector3(...from), b = new THREE.Vector3(...to);
  const d = b.clone().sub(a);
  const len = Math.max(d.length(), 1e-6);
  m.position.copy(a).add(b).multiplyScalar(0.5);
  m.scale.set(radius, len, radius);
  m.quaternion.setFromUnitVectors(UP, d.divideScalar(len));
}

type Entry = { mesh: THREE.Mesh; spec: Data; radius: number; k: number };

function buildParts(host: HTMLElement, root: THREE.Group, parts: Data[]) {
  const byId = new Map<string, Entry>();
  for (const p of parts) {
    const color = colorOf(host, p.color);
    const mat = new THREE.MeshStandardMaterial({
      color, roughness: 0.55, metalness: 0.05, side: THREE.DoubleSide,
      transparent: p.opacity !== undefined || p.shape === "plane", opacity: p.opacity ?? (p.shape === "plane" ? 0.35 : 1),
      wireframe: !!p.wire,
    });
    mat.userData.op = p.opacity ?? (p.shape === "plane" ? 0.35 : 1);
    const mesh = new THREE.Mesh(geometryOf(p), mat);
    mesh.userData.id = p.id;
    if (p.shape === "group") mat.visible = false;
    const radius = Number(p.radius) || 0.08;
    if (p.shape === "rod") placeRod(mesh, V(p.from), V(p.to), radius);
    else {
      if (p.shape !== "tube") mesh.scale.set(...sizeOf(p.size));
      mesh.position.set(...V(p.pos));
      const r = V(p.rot);
      mesh.rotation.set(r[0] * DEG, r[1] * DEG, r[2] * DEG);
    }
    byId.set(String(p.id), { mesh, spec: p, radius, k: 1 });
    if (!p.wire && !["plane", "ring", "rod", "tube", "group"].includes(p.shape)) {
      mesh.add(new THREE.LineSegments(new THREE.EdgesGeometry(mesh.geometry, 30), new THREE.LineBasicMaterial({ color: 0x000000, transparent: true, opacity: 0.35 })));
    }
    if (p.label && p.shape !== "group") {
      mesh.geometry.computeBoundingBox();
      const l = new CSS2DObject(labelEl(String(p.label), p.note, `#${color.getHexString()}`));
      // above the part's top, in its own (scaled) units
      l.position.set(0, mesh.geometry.boundingBox!.max.y + 0.2 / (mesh.scale.y || 1), 0);
      mesh.add(l);
    }
  }
  // a part with a parent moves, turns and scales with it (a rotor and its blades)
  for (const { mesh, spec } of byId.values()) (byId.get(String(spec.parent))?.mesh ?? root).add(mesh);
  return byId;
}

// A part's centre now, or a point.
function centre(byId: Map<string, Entry>, e: unknown): THREE.Vector3 | null {
  if (typeof e === "string") {
    const o = byId.get(e);
    return o ? new THREE.Box3().setFromObject(o.mesh).getCenter(new THREE.Vector3()) : null;
  }
  return Array.isArray(e) ? new THREE.Vector3(...V(e)) : null;
}

// Where an arrow meets a part: the part's surface on the line towards the other end.
function surface(byId: Map<string, Entry>, e: unknown, toward: THREE.Vector3): THREE.Vector3 | null {
  if (typeof e !== "string") return centre(byId, e);
  const o = byId.get(e);
  if (!o) return null;
  const box = new THREE.Box3().setFromObject(o.mesh);
  const c = box.getCenter(new THREE.Vector3());
  const dir = toward.clone().sub(c);
  if (dir.lengthSq() < 1e-9) return c;
  return new THREE.Ray(c, dir.normalize()).intersectBox(box, new THREE.Vector3()) ?? c;
}

// An arrow follows its two ends: update() after parts moved.
function makeArrow(host: HTMLElement, root: THREE.Group, byId: Map<string, Entry>, a: Data) {
  let k = 1;
  const color = colorOf(host, a.color, "fg");
  const mat = new THREE.MeshBasicMaterial({ color });
  const shaft = new THREE.Mesh(new THREE.CylinderGeometry(0.035, 0.035, 1, 10), mat);
  const tip = new THREE.Mesh(new THREE.ConeGeometry(0.35, 1, 16), mat);
  root.add(shaft, tip);
  let label: CSS2DObject | null = null;
  if (a.label) {
    label = new CSS2DObject(labelEl(String(a.label), undefined, `#${color.getHexString()}`));
    label.element.classList.add("arrow");
    root.add(label);
  }
  const update = () => {
    const fc = centre(byId, a.from), tc = centre(byId, a.to);
    const from = fc && tc && surface(byId, a.from, tc), to = fc && tc && surface(byId, a.to, fc);
    const len = from && to ? from.distanceTo(to) : 0;
    const show = len > 0.05 && k > 0.003;
    shaft.visible = tip.visible = show;
    if (label) label.visible = show;
    if (!show || !from || !to) return;
    const unit = to.clone().sub(from).normalize();
    const head = Math.min(0.4, len * 0.35);
    shaft.scale.set(1, len - head, 1);
    shaft.position.copy(from).addScaledVector(unit, (len - head) / 2);
    shaft.quaternion.setFromUnitVectors(UP, unit);
    tip.scale.set(head, head, head);
    tip.position.copy(to).addScaledVector(unit, -head / 2);
    tip.quaternion.copy(shaft.quaternion);
    label?.position.copy(from).add(to).multiplyScalar(0.5);
  };
  // k: how far the arrow has appeared (a fade)
  const set = (v: number) => {
    k = v;
    mat.transparent = v < 1;
    mat.opacity = v;
    if (label) label.element.style.opacity = String(v);
    update();
  };
  update();
  return { id: String(a.id ?? ""), update, set };
}

// What a script's frame() says about one part.
function applyOverride(host: HTMLElement, e: Entry, o: Data) {
  const m = e.mesh;
  if (e.spec.shape === "rod") {
    if (o.from || o.to) placeRod(m, V(o.from ?? e.spec.from), V(o.to ?? e.spec.to), e.radius);
  } else {
    if (o.pos) m.position.set(...V(o.pos));
    if (o.rot) {
      const r = V(o.rot);
      m.rotation.set(r[0] * DEG, r[1] * DEG, r[2] * DEG);
    }
    if (o.size !== undefined && e.spec.shape !== "tube") m.scale.set(...sizeOf(o.size));
  }
  const mat = m.material as THREE.MeshStandardMaterial;
  if (o.color) mat.color.copy(colorOf(host, o.color));
  if (typeof o.opacity === "number") mat.userData.op = o.opacity;
  applyReveal(e, e.k);
}

// How far a part has appeared (0 hidden .. 1 fully there): a fade, with its label and outline.
function applyReveal(e: Entry, k: number) {
  e.k = k;
  const m = e.mesh;
  if (e.spec.shape === "group") return;
  const mat = m.material as THREE.MeshStandardMaterial;
  const op = typeof mat.userData.op === "number" ? mat.userData.op : 1;
  m.visible = k > 0.003;
  mat.opacity = op * k;
  mat.transparent = op < 1 || k < 1;
  for (const c of m.children) {
    if (c instanceof CSS2DObject) { c.visible = k > 0.05; c.element.style.opacity = String(k); }
    else if ((c as THREE.LineSegments).isLineSegments) ((c as THREE.LineSegments).material as THREE.LineBasicMaterial).opacity = 0.35 * k;
  }
}

function dispose(root: THREE.Group) {
  root.traverse((o) => {
    const m = o as THREE.Mesh;
    m.geometry?.dispose?.();
    const mat = m.material as THREE.Material | THREE.Material[] | undefined;
    if (Array.isArray(mat)) mat.forEach((x) => x.dispose());
    else mat?.dispose?.();
    if (o instanceof CSS2DObject) o.element.remove();
  });
  root.clear();
}

export default function SceneView({ data, chat, block, live }: { data: Data; chat?: string; block?: string; live?: boolean }) {
  const send = useContext(PictureSend);
  const server = useApi();
  const host = useRef<HTMLDivElement>(null);
  const api = useRef<{ set: (d: Data) => void; fit: () => void; play: (p: boolean) => void; speed: (s: number) => void; scan: () => void; select: (id: string | null) => void; story: { play: () => void; pause: () => void; next: () => void; prev: () => void; goto: (n: number) => void; mute: (m: boolean) => void } } | null>(null);
  const [animated, setAnimated] = useState(false);
  const [running, setRunning] = useState(true);
  const [speed, setSpeed] = useState(2);
  const [error, setError] = useState("");
  const [solids, setSolids] = useState(0);
  const [now, setNow] = useState<string[]>([]);
  const [seen, setSeen] = useState<string[]>([]);
  const [panel, setPanel] = useState(false);
  const [height, setHeight] = useState(() => clampHeight(Number(localStorage.getItem(HEIGHT_KEY)) || 440));
  const [scanned, setScanned] = useState(false);
  const [picked, setPicked] = useState<Picked | null>(null);
  const [hint, setHint] = useState("");
  const [storyLen, setStoryLen] = useState(0);
  const [idx, setIdx] = useState(-1);
  const [playing, setPlayingUi] = useState(false);
  const [sub, setSub] = useState("");
  const [voiceState, setVoiceState] = useState("");
  const [muted, setMuted] = useState(false);
  useEffect(() => {
    if (!hint) return;
    const t = setTimeout(() => setHint(""), 1500);
    return () => clearTimeout(t);
  }, [hint]);

  useEffect(() => {
    const el = host.current!;
    const scene = new THREE.Scene();
    const camera = new THREE.PerspectiveCamera(45, 1, 0.05, 500);
    const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
    renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
    const labels = new CSS2DRenderer();
    labels.domElement.className = "s3-labels";
    const stage = el.querySelector<HTMLElement>(".s3-stage")!;
    stage.append(renderer.domElement, labels.domElement);

    scene.add(new THREE.HemisphereLight(0xffffff, 0x444444, 1.6));
    const sun = new THREE.DirectionalLight(0xffffff, 2.2);
    sun.position.set(4, 8, 6);
    scene.add(sun);
    const grid = new THREE.GridHelper(20, 20, 0x888888, 0x555555);
    (grid.material as THREE.Material).transparent = true;
    (grid.material as THREE.Material).opacity = 0.35;
    scene.add(grid);
    const root = new THREE.Group();
    scene.add(root);

    const controls = new OrbitControls(camera, labels.domElement);
    controls.enableDamping = true;
    controls.dampingFactor = 0.12;
    controls.zoomSpeed = 0.9;

    let box = new THREE.Box3();
    let fitted = false;
    const fit = () => {
      if (box.isEmpty()) box.set(new THREE.Vector3(-2, 0, -2), new THREE.Vector3(2, 2, 2));
      const c = box.getCenter(new THREE.Vector3());
      const r = Math.max(box.getSize(new THREE.Vector3()).length() / 2, 1);
      const dist = (r / Math.sin((camera.fov * DEG) / 2)) * 0.95;
      controls.target.copy(c);
      camera.position.copy(c).add(new THREE.Vector3(0.7, 0.55, 1).normalize().multiplyScalar(dist));
      controls.update();
    };

    // animation state
    let script: SceneScript | null = null;
    let byId = new Map<string, Entry>();
    let arrows: ReturnType<typeof makeArrow>[] = [];
    // the story: steps, which part is how far in (0..1), fades and camera moves under way
    let steps: Step[] = [];
    let sIdx = -1, sPlaying = false, sElapsed = 0, sDur = 0, sToken = 0, sTick = 0, sLast = 0, sMuted = false, sInstruction: string | undefined, sAutoplay = false;
    const kOf = new Map<string, number>();
    const fades: { id: string; from: number; to: number; at: number; dur: number }[] = [];
    let focus = new Set<string>();
    let cam: { at: number; dur: number; p0: THREE.Vector3; t0: THREE.Vector3; p1: THREE.Vector3; t1: THREE.Vector3 } | null = null;
    const voice = new Voice((path, init) => server.fetch(path, init));
    const voiceReady = voice.available();
    let checker: ReturnType<typeof makeChecker> | null = null;
    let flagged = '';
    const everSeen = new Set<string>();
    let frames = 0, scanSeconds = SCAN_SECONDS;
    let t = 0, last = 0, rate = SPEEDS[2], on = true, visible = true, token = 0;
    let raf = 0;

    const stop = () => { script?.close(); script = null; };

    // red glow for overlapping parts, a blue one for the part the user clicked
    let bad = new Set<string>();
    let chosen: string | null = null;
    const paint = (pulse = 0.5) => {
      for (const [id, e] of byId) {
        const m = e.mesh.material as THREE.MeshStandardMaterial;
        const lit = focus.has(id) && !bad.has(id) && id !== chosen;
        m.emissive.setHex(bad.has(id) ? 0xff2a2a : id === chosen ? 0x2f6dff : lit ? 0xffc247 : 0x000000);
        m.emissiveIntensity = bad.has(id) ? 0.9 : lit ? 0.2 + 0.6 * pulse : 0.7;
      }
      want();
    };
    // flag the solid parts that overlap now (red glow) and remember every pair seen
    const runCheck = () => {
      if (!checker) return;
      const hits = checker.check((id) => (kOf.get(id) ?? 1) > 0.99);
      const key = hits.map(pairKey).join("|");
      if (key === flagged) return;
      flagged = key;
      bad = new Set(hits.flat());
      paint();
      let more = false;
      for (const h of hits) if (!everSeen.has(pairKey(h))) { everSeen.add(pairKey(h)); more = true; }
      setNow(hits.map(pairKey));
      if (more) setSeen([...everSeen]);
      want();
    };

    // run a whole stretch of the script without drawing, to catch overlaps that only happen later
    const scan = () => {
      if (!checker || !script?.frame) return runCheck();
      const was = t;
      try {
        for (let s = 0; s <= scanSeconds; s += 1 / 30) {
          const o = script.frame(s, sIdx);
          for (const [id, v] of Object.entries(o ?? {})) {
            const e = byId.get(id);
            if (e && v) applyOverride(el, e, v as Data);
          }
          runCheck();
        }
      } catch (e) {
        setError((e as Error).message);
      }
      t = was;
      moveParts();
      runCheck();
      setScanned(true);
    };

    const set = async (d: Data) => {
      const mine = ++token;
      let extra: { parts?: any[]; arrows?: any[]; allow?: any[] } = {};
      setError("");
      stop();
      if (typeof d.script === "string" && d.script.trim()) {
        try {
          const s = await loadScript(d.script);
          if (mine !== token) return s.close();
          script = s;
          extra = s.build() ?? {};
        } catch (e) {
          if (mine !== token) return;
          setError((e as Error).message);
        }
      }
      if (mine !== token) return;
      dispose(root);
      byId = buildParts(el, root, [...list(d.parts), ...list(extra.parts)]);
      root.updateMatrixWorld(true);
      arrows = [...list(d.arrows), ...list(extra.arrows)].map((a) => makeArrow(el, root, byId, a));
      kOf.clear();
      fades.length = 0;
      checker = makeChecker(root, [...byId].map(([id, e]) => ({ id, mesh: e.mesh, spec: e.spec })), [...list(d.allow), ...list(extra.allow)]);
      scanSeconds = Number(d.scan) > 0 ? Number(d.scan) : SCAN_SECONDS;
      everSeen.clear();
      setSolids(checker.count);
      setSeen([]);
      setScanned(false);
      flagged = "";
      runCheck();
      box = new THREE.Box3().setFromObject(root);
      if (!fitted) { fit(); fitted = true; }
      setAnimated(!!script?.frame);
      last = 0;
      newStory(d);
      want();
    };

    const moveParts = () => {
      if (!script?.frame) return;
      try {
        const o = script.frame(t, sIdx);
        for (const [id, v] of Object.entries(o ?? {})) {
          const e = byId.get(id);
          if (e && v) applyOverride(el, e, v as Data);
        }
        root.updateMatrixWorld(true);
        for (const u of arrows) u.update();
        if (++frames % 2 === 0) runCheck();
      } catch (e) {
        setError((e as Error).message);
        stop();
        setAnimated(false);
      }
    };


    // ---------------------------------------------------------------- the story
    const setReveal = (id: string, k: number) => {
      kOf.set(id, k);
      const e = byId.get(id);
      if (e) applyReveal(e, k);
      else arrows.find((a) => a.id === id)?.set(k);
    };
    const allIds = () => [...byId.keys(), ...arrows.map((a) => a.id).filter(Boolean)];

    // which parts are on after step n (n = -1: before it starts); the ones that just came on fade in
    const applyStoryState = (n: number, animate: boolean) => {
      const ids = allIds();
      const on = visibleAt(steps, n, ids);
      let order = 0;
      const wall = performance.now();
      for (const id of ids) {
        const to = on.has(id) ? 1 : 0, now = kOf.get(id) ?? 1;
        const queued = fades.findIndex((f) => f.id === id);
        if (queued >= 0) fades.splice(queued, 1);
        if (to === now && queued < 0) { kOf.set(id, to); continue; }
        if (animate) {
          fades.push({ id, from: now, to, at: wall + Math.min(order++ * 35, 700), dur: to ? 700 : 400 });
        } else setReveal(id, to);
      }
      root.updateMatrixWorld(true);
      for (const u of arrows) u.update();
      want();
    };

    const boxOf = (patterns: string[]) => {
      const b = new THREE.Box3();
      for (const [id, e] of byId) if (e.spec.shape !== "group" && matchesAny(patterns, id)) b.union(new THREE.Box3().setFromObject(e.mesh));
      return b;
    };
    const moveCamera = (c: CameraSpec) => {
      root.updateMatrixWorld(true);
      const target = new THREE.Vector3();
      let dist = camera.position.distanceTo(controls.target);
      const fb = c.frame?.length ? boxOf(c.frame) : null;
      if (fb && !fb.isEmpty()) {
        fb.getCenter(target);
        dist = (Math.max(fb.getSize(new THREE.Vector3()).length() / 2, 0.6) / Math.sin((camera.fov * DEG) / 2)) * 0.95;
      } else if (typeof c.target === "string") {
        const b = boxOf([c.target]);
        if (b.isEmpty()) target.copy(controls.target); else b.getCenter(target);
      } else if (Array.isArray(c.target)) target.set(...V(c.target));
      else target.copy(controls.target);
      if (c.dist) dist = c.dist;
      let dir = camera.position.clone().sub(controls.target);
      if (dir.lengthSq() < 1e-6) dir.set(0.7, 0.55, 1);
      if (c.az !== undefined || c.el !== undefined) {
        const az = (c.az ?? 30) * DEG, el = (c.el ?? 25) * DEG;
        dir.set(Math.sin(az) * Math.cos(el), Math.sin(el), Math.cos(az) * Math.cos(el));
      }
      dir.normalize();
      cam = { at: performance.now(), dur: 1200, p0: camera.position.clone(), t0: controls.target.clone(), p1: target.clone().addScaledVector(dir, dist), t1: target };
      want();
    };
    controls.addEventListener("start", () => { cam = null; });

    const stopTick = () => { clearInterval(sTick); sTick = 0; };
    const endStory = () => { sPlaying = false; stopTick(); setPlayingUi(false); };
    const startStep = async (n: number, animate: boolean) => {
      if (n < 0 || n >= steps.length) return;
      const mine = ++sToken;
      voice.stop();
      sIdx = n; sElapsed = 0; sDur = 0;
      setIdx(n);
      const st = steps[n];
      setSub(st.say);
      applyStoryState(n, animate);
      focus = new Set([...byId.keys()].filter((id) => matchesAny(st.focus, id)));
      if (!focus.size) paint();
      if (st.camera) moveCamera(st.camera);
      let seconds = estimateSeconds(st);
      if (st.say && !sMuted) {
        const ready = await voiceReady;
        if (mine !== sToken) return;
        if (ready) {
          const clip = await Promise.race([voice.load(st.say, sInstruction), new Promise<null>((r) => setTimeout(() => r(null), 12000))]);
          if (mine !== sToken) return;
          if (clip) {
            seconds = Math.max(clip.seconds, st.hold);
            if (sPlaying) await voice.play(clip);
          }
        }
      }
      if (mine !== sToken) return;
      sDur = seconds + GAP_SECONDS;
      sLast = performance.now();
      if (sPlaying && !sTick) sTick = window.setInterval(tick, 100);
      want();
    };
    const tick = () => {
      const now = performance.now();
      if (sPlaying && sDur) sElapsed += (now - sLast) / 1000;
      sLast = now;
      if (sPlaying && sDur && sElapsed >= sDur) {
        if (sIdx + 1 < steps.length) void startStep(sIdx + 1, true);
        else endStory();
      }
    };
    const storyApi = {
      play: () => {
        if (!steps.length) return;
        sPlaying = true;
        setPlayingUi(true);
        if (sIdx < 0 || (sIdx >= steps.length - 1 && sDur && sElapsed >= sDur)) {
          applyStoryState(-1, false);
          void startStep(0, true);
        } else {
          voice.resume();
          sLast = performance.now();
          if (!sTick) sTick = window.setInterval(tick, 100);
          if (!sDur) void startStep(sIdx, false);
        }
      },
      pause: () => { sPlaying = false; setPlayingUi(false); voice.pause(); stopTick(); },
      next: () => { if (sIdx + 1 < steps.length) { sPlaying = true; setPlayingUi(true); voice.resume(); void startStep(sIdx + 1, true); } },
      prev: () => { sPlaying = true; setPlayingUi(true); voice.resume(); void startStep(Math.max(0, sIdx - 1), false); },
      goto: (n: number) => { sPlaying = true; setPlayingUi(true); voice.resume(); void startStep(n, n === sIdx + 1); },
      mute: (m: boolean) => { sMuted = m; setMuted(m); if (m) voice.stop(); },
    };
    // called after every build: the steps may have changed (Claude adds to the story while it plays)
    const newStory = (d: Data) => {
      steps = normalizeStory(d.story);
      sInstruction = typeof d.voice === "string" ? d.voice : undefined;
      setStoryLen(steps.length);
      if (!steps.length) { sIdx = -1; setIdx(-1); setSub(""); focus = new Set(); paint(); return; }
      if (sIdx >= steps.length) sIdx = steps.length - 1;
      applyStoryState(sIdx, false);
      focus = sIdx >= 0 ? new Set([...byId.keys()].filter((id) => matchesAny(steps[sIdx].focus, id))) : new Set();
      // the voice: every line is asked for in order while the user looks around
      if (d.narrate !== false && steps.some((x) => x.say)) {
        void voiceReady.then(async (ok) => {
          if (!ok) { setVoiceState("no voice on this server"); return; }
          const lines = [...new Set(steps.map((x) => x.say).filter(Boolean))];
          let n = 0;
          for (const line of lines) {
            setVoiceState(`voice ${n}/${lines.length}`);
            if (!(await voice.load(line, sInstruction))) { setVoiceState(`no voice: ${voice.error}`); return; }
            n++;
          }
          setVoiceState("voice ready");
        });
      } else setVoiceState("");
      if (d.autoplay === true && !sAutoplay && sIdx < 0) { sAutoplay = true; storyApi.play(); }
    };

    const draw = (now: number) => {
      raf = 0;
      let again = false;
      if (script?.frame) {
        if (on && visible) {
          t += last ? Math.min((now - last) / 1000, 0.1) * rate : 0;
          again = true;
        }
        moveParts();
      }
      last = again ? now : 0;
      const wall = performance.now();
      if (fades.length) {
        for (let i = fades.length - 1; i >= 0; i--) {
          const f = fades[i];
          const p = Math.min(1, Math.max(0, (wall - f.at) / f.dur));
          setReveal(f.id, f.from + (f.to - f.from) * (1 - Math.pow(1 - p, 3)));
          if (p >= 1) fades.splice(i, 1);
        }
        for (const u of arrows) u.update();
        again = true;
      }
      if (cam) {
        const p = Math.min(1, (wall - cam.at) / cam.dur), e = p < 0.5 ? 2 * p * p : 1 - Math.pow(-2 * p + 2, 2) / 2;
        camera.position.lerpVectors(cam.p0, cam.p1, e);
        controls.target.lerpVectors(cam.t0, cam.t1, e);
        if (p >= 1) cam = null;
        again = true;
      }
      if (focus.size) {
        paint(0.5 + 0.5 * Math.sin(wall / 260));
        again = true;
      }
      again = controls.update() || again;
      renderer.render(scene, camera);
      labels.render(scene, camera);
      if (again) want();
    };
    const want = () => { if (!raf) raf = requestAnimationFrame(draw); };
    controls.addEventListener("change", want);

    // A trackpad: two fingers sliding move the view (pan), a pinch zooms (it arrives as ctrl + wheel);
    // a mouse wheel (whole notches) still zooms. Runs before OrbitControls' own wheel zoom.
    const dollyBy = (factor: number) => {
      const offset = camera.position.clone().sub(controls.target);
      const dist = Math.min(80, Math.max(0.5, offset.length() * factor));
      camera.position.copy(controls.target).addScaledVector(offset.normalize(), dist);
    };
    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      e.stopPropagation();
      cam = null;
      const lines = e.deltaMode === 1;
      const mouse = e.deltaMode !== 0 || (e.deltaX === 0 && Number.isInteger(e.deltaY) && Math.abs(e.deltaY) >= 50);
      if (e.ctrlKey) dollyBy(Math.exp(e.deltaY * 0.012));
      else if (mouse) dollyBy(Math.exp(Math.sign(e.deltaY) * 0.1));
      else {
        const unit = lines ? 16 : 1;
        const perPixel = (2 * camera.position.distanceTo(controls.target) * Math.tan((camera.fov * DEG) / 2)) / Math.max(stage.clientHeight, 1);
        const right = new THREE.Vector3().setFromMatrixColumn(camera.matrix, 0);
        const up = new THREE.Vector3().setFromMatrixColumn(camera.matrix, 1);
        const move = right.multiplyScalar(e.deltaX * unit * perPixel).addScaledVector(up, -e.deltaY * unit * perPixel);
        camera.position.add(move);
        controls.target.add(move);
      }
      controls.update();
      want();
    };
    stage.addEventListener("wheel", onWheel, { passive: false, capture: true });

    // a click (not a drag) on a part: the nearest solid one along the ray, else the nearest ghost
    const ray = new THREE.Raycaster();
    let down: { x: number; y: number } | null = null;
    const pick = (cx: number, cy: number) => {
      const r = labels.domElement.getBoundingClientRect();
      ray.setFromCamera(new THREE.Vector2(((cx - r.left) / r.width) * 2 - 1, -((cy - r.top) / r.height) * 2 + 1), camera);
      root.updateMatrixWorld(true);
      const meshes = [...byId.values()].filter((e) => e.spec.shape !== "group" && e.k > 0.05).map((e) => e.mesh);
      // thin parts (a rod, a tube) are hard to hit: if the exact click finds nothing, try a few pixels around it
      let hits = ray.intersectObjects(meshes, false);
      for (const [dx, dy] of hits.length ? [] : [[6, 0], [-6, 0], [0, 6], [0, -6], [10, 10], [-10, 10], [10, -10], [-10, -10]]) {
        ray.setFromCamera(new THREE.Vector2(((cx + dx - r.left) / r.width) * 2 - 1, -((cy + dy - r.top) / r.height) * 2 + 1), camera);
        hits = ray.intersectObjects(meshes, false);
        if (hits.length) break;
      }
      const solid = hits.find((h) => isSolid(byId.get(String(h.object.userData.id))!.spec));
      const hit = solid ?? hits[0];
      if (!hit) {
        select(null);
        setHint("no part there");
        return;
      }
      const id = String(hit.object.userData.id);
      select(id);
      const e = byId.get(id)!;
      const w = e.mesh.getWorldPosition(new THREE.Vector3());
      const sz = e.spec.shape === "rod" ? `length ${new THREE.Vector3(...V(e.spec.from)).distanceTo(new THREE.Vector3(...V(e.spec.to))).toFixed(2)}` : JSON.stringify(e.spec.size ?? "—");
      const rows: [string, string][] = [
        ["id", id],
        ["shape", String(e.spec.shape ?? "box")],
        ["color", String(e.spec.color ?? "blue")],
        ["size", sz],
        ["at", `${w.x.toFixed(2)}, ${w.y.toFixed(2)}, ${w.z.toFixed(2)}`],
      ];
      if (e.spec.parent) rows.push(["part of", String(e.spec.parent)]);
      rows.push(["solid", isSolid(e.spec) ? (bad.has(id) ? "yes, overlapping now" : "yes") : "no (ghost/housing)"]);
      const box = el.getBoundingClientRect();
      setPicked({ id, title: String(e.spec.label ?? id), x: cx - box.left, y: cy - box.top, rows, note: e.spec.note, shape: String(e.spec.shape ?? "box"), color: String(e.spec.color ?? "blue"), parent: e.spec.parent });
    };
    const select = (id: string | null) => {
      chosen = id;
      if (!id) setPicked(null);
      paint();
    };
    const onDown = (e: PointerEvent) => { down = { x: e.clientX, y: e.clientY }; };
    // a click is a press and release in place; dragging to rotate is not
    const onClick = (e: MouseEvent) => {
      const d = down;
      down = null;
      if (!d || e.button !== 0 || Math.hypot(e.clientX - d.x, e.clientY - d.y) >= 5) return;
      pick(e.clientX, e.clientY);
    };
    labels.domElement.addEventListener("pointerdown", onDown);
    labels.domElement.addEventListener("click", onClick);

    api.current = {
      set: (d) => void set(d),
      fit: () => { fit(); want(); },
      play: (p) => { on = p; want(); },
      speed: (s) => { rate = s; },
      scan,
      select,
      story: storyApi,
    };

    const size = () => {
      const w = stage.clientWidth, h = stage.clientHeight;
      renderer.setSize(w, h);
      labels.setSize(w, h);
      camera.aspect = w / Math.max(h, 1);
      camera.updateProjectionMatrix();
      want();
    };
    const ro = new ResizeObserver(size);
    ro.observe(el);
    // no frames while scrolled out of view
    const io = new IntersectionObserver(([en]) => { visible = en.isIntersecting; want(); });
    io.observe(el);
    size();
    void set(data);

    return () => {
      token++;
      cancelAnimationFrame(raf);
      ro.disconnect();
      io.disconnect();
      labels.domElement.removeEventListener("pointerdown", onDown);
      labels.domElement.removeEventListener("click", onClick);
      stage.removeEventListener("wheel", onWheel, { capture: true });
      controls.dispose();
      stopTick();
      voice.close();
      stop();
      dispose(root);
      renderer.dispose();
      renderer.domElement.remove();
      labels.domElement.remove();
      api.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => { api.current?.set(data); }, [data]);

  return (
    <div className="f-scene3d" ref={host}>
      <div className="s3-stage" style={{ height }} />
      <div
        className="s3-grip"
        role="separator"
        aria-orientation="horizontal"
        title="Drag to resize the height"
        onPointerDown={(e) => {
          const y0 = e.clientY, h0 = height;
          const grip = e.currentTarget;
          grip.setPointerCapture(e.pointerId);
          const move = (m: PointerEvent) => setHeight(clampHeight(h0 + m.clientY - y0));
          const up = (u: PointerEvent) => {
            grip.removeEventListener("pointermove", move);
            grip.removeEventListener("pointerup", up);
            localStorage.setItem(HEIGHT_KEY, String(clampHeight(h0 + u.clientY - y0)));
          };
          grip.addEventListener("pointermove", move);
          grip.addEventListener("pointerup", up);
        }}
      />
      {picked && (
        <div className="s3-pop" style={{ left: Math.min(picked.x + 12, (host.current?.clientWidth ?? 400) - 250), top: Math.max(4, Math.min(picked.y - 10, height - 250)) }} onPointerDown={(e) => e.stopPropagation()}>
          <div className="s3-pop-head">
            <b>{picked.title}</b>
            <button type="button" className="s3-x" onClick={() => { setPicked(null); api.current?.select(null); }} aria-label="Close"><Icon name="close" /></button>
          </div>
          {picked.note && <p className="s3-pop-note">{picked.note}</p>}
          <dl>
            {picked.rows.map(([k, v]) => (<React.Fragment key={k}><dt>{k}</dt><dd>{v}</dd></React.Fragment>))}
          </dl>
          <button
            type="button"
            className="primary"
            disabled={!(live && send && chat)}
            title={live && send && chat ? "Ask Claude to explain this part" : "Open this chat to ask Claude"}
            onClick={() => {
              const what = `${picked.title}${picked.title !== picked.id ? ` (id ${picked.id})` : ""}`;
              send!({
                t: "send",
                chat,
                text: `Explain the "${what}" in the 3D view${block ? ` (block ${block})` : ""}: a ${picked.color} ${picked.shape}${picked.parent ? `, part of ${picked.parent}` : ""}${picked.note ? `, noted as "${picked.note}"` : ""}. What it is, what it does, how it works, and how it connects to the parts around it.`,
              });
              setPicked(null);
              api.current?.select(null);
            }}
          >
            Explain
          </button>
        </div>
      )}
      {storyLen > 0 && idx < 0 && !playing && (
        <button type="button" className="primary s3-bigplay" onClick={() => api.current?.story.play()}><Icon name="play" /> Play the story</button>
      )}
      {sub && idx >= 0 && <div className="s3-sub">{sub}</div>}
      {error && <p className="s3-error">script: {error}</p>}
      {panel && solids > 1 && (
        <div className="s3-panel">
          <b>Overlapping now</b>
          {now.length ? now.map((x) => <div key={x} className="bad">{x}</div>) : <div className="f-note">none</div>}
          <b>Seen while it ran</b>
          {seen.length ? seen.map((x) => <div key={x}>{x}</div>) : <div className="f-note">none</div>}
          {animated && <button type="button" className="f-tool" onClick={() => api.current?.scan()}>scan the whole run</button>}
        </div>
      )}
      <div className="s3-bar">
        <span className="s3-left">
          {storyLen > 0 && (
            <span className="s3-story">
              <button type="button" className="f-tool" disabled={idx < 1} onClick={() => api.current?.story.prev()} title="Previous step"><Icon name="prev" /></button>
              <button type="button" className="f-tool" onClick={() => (playing ? api.current?.story.pause() : api.current?.story.play())}>{playing ? <Icon name="pause" /> : <Icon name="play" />}</button>
              <button type="button" className="f-tool" disabled={idx >= storyLen - 1} onClick={() => api.current?.story.next()} title="Next step"><Icon name="next" /></button>
              <span className="s3-dots">
                {Array.from({ length: storyLen }, (_, i) => (
                  <button key={i} type="button" className={`s3-dot ${i === idx ? "on" : i < idx ? "past" : ""}`} onClick={() => api.current?.story.goto(i)} title={`Step ${i + 1} of ${storyLen}`} />
                ))}
              </span>
              {voiceState.startsWith("voice") && (
                <button type="button" className="f-tool" onClick={() => api.current?.story.mute(!muted)} title={muted ? "Voice off: click to hear it" : "Mute the voice"}>{muted ? <Icon name="mute" /> : <Icon name="volume" />}</button>
              )}
              {voiceState && <span className="f-note">{voiceState}</span>}
            </span>
          )}
          {solids > 1 && (
            <button type="button" className={`f-tool s3-check ${now.length ? "bad" : seen.length ? "warn" : "ok"}`} onClick={() => setPanel(!panel)} title="Solid parts that pass through each other">
              {now.length ? <><Icon name="warn" /> {`${now.length} overlap${now.length > 1 ? "s" : ""}`}</> : <><Icon name="check" /> {seen.length ? `clear now · ${seen.length} seen` : scanned ? "no overlaps in the run" : "no overlaps"}</>}
            </button>
          )}
          <span className="f-note">{hint || "drag: rotate · wheel: zoom · click: inspect"}</span>
        </span>
        <span className="s3-tools">
          {animated && (
            <>
              <button type="button" className="f-tool" onClick={() => { setRunning(!running); api.current?.play(!running); }}>{running ? <><Icon name="pause" /> pause</> : <><Icon name="play" /> play</>}</button>
              <button type="button" className="f-tool" onClick={() => { const n = (speed + 1) % SPEEDS.length; setSpeed(n); api.current?.speed(SPEEDS[n]); }}>{SPEEDS[speed]}×</button>
            </>
          )}
          <button type="button" className="f-tool" onClick={() => api.current?.fit()}><Icon name="reset" /> reset view</button>
        </span>
      </div>
    </div>
  );
}
