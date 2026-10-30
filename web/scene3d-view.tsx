// The three.js side of the scene3d form (loaded on demand by scene3d.tsx).
import React, { useEffect, useRef } from "react";
import * as THREE from "three";
import { OrbitControls } from "three/examples/jsm/controls/OrbitControls.js";
import { CSS2DObject, CSS2DRenderer } from "three/examples/jsm/renderers/CSS2DRenderer.js";

type Data = Record<string, any>;
type V3 = [number, number, number];
const list = (v: unknown): any[] => (Array.isArray(v) ? v : []);
const V = (v: unknown, d: V3 = [0, 0, 0]): V3 => (Array.isArray(v) && v.length >= 3 ? [Number(v[0]), Number(v[1]), Number(v[2])] : d);
const DEG = Math.PI / 180;

// A theme colour name (green, amber…) or any CSS colour.
function colorOf(host: HTMLElement, c: unknown, fallback = "blue"): THREE.Color {
  const s = typeof c === "string" && c ? c : fallback;
  const css = /^[a-z]+$/.test(s) ? getComputedStyle(host).getPropertyValue(`--${s}`).trim() || s : s;
  return new THREE.Color(css);
}

// size: one number (the whole part) or [x, y, z].
function sizeOf(p: Data): V3 {
  const s = p.size;
  if (typeof s === "number") return [s, s, s];
  return V(s, [1, 1, 1]);
}

// Unit-sized geometry per shape; size scales it. Extrude / lathe / tube build from the part's points.
function geometryOf(p: Data): THREE.BufferGeometry {
  const pts2 = list(p.points).map((q) => new THREE.Vector2(Number(q[0]), Number(q[1])));
  switch (p.shape) {
    case "sphere": return new THREE.SphereGeometry(0.5, 40, 24);
    case "cylinder": return new THREE.CylinderGeometry(0.5, 0.5, 1, 40);
    case "cone": return new THREE.ConeGeometry(0.5, 1, 40);
    case "pyramid": return new THREE.ConeGeometry(0.5 * Math.SQRT2, 1, 4).rotateY(Math.PI / 4);
    case "torus": return new THREE.TorusGeometry(0.4, 0.1, 20, 56);
    case "capsule": return new THREE.CapsuleGeometry(0.25, 0.5, 8, 24);
    case "plane": return new THREE.PlaneGeometry(1, 1).rotateX(-Math.PI / 2);
    case "ring": return new THREE.RingGeometry(0.3, 0.5, 48).rotateX(-Math.PI / 2);
    case "extrude": {
      if (pts2.length < 3) return new THREE.BoxGeometry(1, 1, 1);
      const g = new THREE.ExtrudeGeometry(new THREE.Shape(pts2), { depth: 1, bevelEnabled: false }).center();
      return g;
    }
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

// Builds the objects for the data into `root`; returns what is needed to find a part by id.
function build(host: HTMLElement, root: THREE.Group, data: Data) {
  const byId = new Map<string, THREE.Object3D>();
  for (const p of list(data.parts)) {
    const color = colorOf(host, p.color);
    const mat = new THREE.MeshStandardMaterial({
      color, roughness: 0.55, metalness: 0.05, side: THREE.DoubleSide,
      transparent: p.opacity !== undefined || p.shape === "plane", opacity: p.opacity ?? (p.shape === "plane" ? 0.35 : 1),
      wireframe: !!p.wire,
    });
    const mesh = new THREE.Mesh(geometryOf(p), mat);
    // tube keeps its radius in the geometry, the rest scale by size
    if (p.shape !== "tube") mesh.scale.set(...sizeOf(p));
    mesh.position.set(...V(p.pos));
    const r = V(p.rot);
    mesh.rotation.set(r[0] * DEG, r[1] * DEG, r[2] * DEG);
    mesh.userData.id = p.id;
    root.add(mesh);
    byId.set(p.id, mesh);
    if (!p.wire && p.shape !== "plane" && p.shape !== "ring") {
      const edges = new THREE.LineSegments(new THREE.EdgesGeometry(mesh.geometry, 30), new THREE.LineBasicMaterial({ color: 0x000000, transparent: true, opacity: 0.35 }));
      mesh.add(edges);
    }
    if (p.label) {
      mesh.geometry.computeBoundingBox();
      const l = new CSS2DObject(labelEl(String(p.label), p.note, `#${color.getHexString()}`));
      // above the part's top, in its own (scaled) units
      l.position.set(0, mesh.geometry.boundingBox!.max.y + 0.2 / (mesh.scale.y || 1), 0);
      mesh.add(l);
    }
  }
  root.updateMatrixWorld(true);

  // an arrow end: a part id (stops at the part's surface) or a point
  const end = (e: unknown, toward: THREE.Vector3): THREE.Vector3 | null => {
    if (typeof e === "string") {
      const o = byId.get(e);
      if (!o) return null;
      const box = new THREE.Box3().setFromObject(o, true);
      const c = box.getCenter(new THREE.Vector3());
      const dir = toward.clone().sub(c);
      if (dir.lengthSq() < 1e-9) return c;
      const hit = new THREE.Ray(c, dir.normalize()).intersectBox(box, new THREE.Vector3());
      return hit ?? c;
    }
    return Array.isArray(e) ? new THREE.Vector3(...V(e)) : null;
  };
  const centre = (e: unknown) => {
    if (typeof e === "string") {
      const o = byId.get(e);
      return o ? new THREE.Box3().setFromObject(o, true).getCenter(new THREE.Vector3()) : null;
    }
    return Array.isArray(e) ? new THREE.Vector3(...V(e)) : null;
  };
  for (const a of list(data.arrows)) {
    const fc = centre(a.from), tc = centre(a.to);
    if (!fc || !tc) continue;
    const from = end(a.from, tc)!, to = end(a.to, fc)!;
    const dir = to.clone().sub(from);
    const len = dir.length();
    if (len < 0.05) continue;
    const color = colorOf(host, a.color, "fg");
    const head = Math.min(0.4, len * 0.35);
    const unit = dir.clone().normalize();
    const arrow = new THREE.Group();
    const mat = new THREE.MeshBasicMaterial({ color });
    const shaft = new THREE.Mesh(new THREE.CylinderGeometry(0.035, 0.035, len - head, 10), mat);
    shaft.position.copy(from).addScaledVector(unit, (len - head) / 2);
    shaft.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), unit);
    const tip = new THREE.Mesh(new THREE.ConeGeometry(head * 0.35, head, 16), mat);
    tip.position.copy(to).addScaledVector(unit, -head / 2);
    tip.quaternion.copy(shaft.quaternion);
    arrow.add(shaft, tip);
    root.add(arrow);
    if (a.label) {
      const l = new CSS2DObject(labelEl(String(a.label), undefined, `#${color.getHexString()}`));
      l.element.classList.add("arrow");
      l.position.copy(from).add(to).multiplyScalar(0.5);
      root.add(l);
    }
  }
  return new THREE.Box3().setFromObject(root, true);
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

export default function SceneView({ data }: { data: Data }) {
  const host = useRef<HTMLDivElement>(null);
  const api = useRef<{ set: (d: Data) => void; fit: () => void } | null>(null);

  useEffect(() => {
    const el = host.current!;
    const scene = new THREE.Scene();
    const camera = new THREE.PerspectiveCamera(45, 1, 0.05, 500);
    const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
    renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
    const labels = new CSS2DRenderer();
    labels.domElement.className = "s3-labels";
    el.querySelector(".s3-stage")!.append(renderer.domElement, labels.domElement);

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
      const dist = r / Math.sin((camera.fov * DEG) / 2) * 0.95;
      controls.target.copy(c);
      camera.position.copy(c).add(new THREE.Vector3(0.7, 0.55, 1).normalize().multiplyScalar(dist));
      controls.update();
    };
    const set = (d: Data) => {
      dispose(root);
      box = build(el, root, d);
      if (!fitted) { fit(); fitted = true; }
      want();
    };
    api.current = { set, fit: () => { fit(); want(); } };

    // draw on demand: only while the camera moves or the scene changes
    let raf = 0;
    const draw = () => {
      raf = 0;
      const moving = controls.update();
      renderer.render(scene, camera);
      labels.render(scene, camera);
      if (moving) want();
    };
    const want = () => { if (!raf) raf = requestAnimationFrame(draw); };
    controls.addEventListener("change", want);

    const size = () => {
      const stage = el.querySelector<HTMLElement>(".s3-stage")!;
      const w = stage.clientWidth, h = stage.clientHeight;
      renderer.setSize(w, h);
      labels.setSize(w, h);
      camera.aspect = w / Math.max(h, 1);
      camera.updateProjectionMatrix();
      want();
    };
    const ro = new ResizeObserver(size);
    ro.observe(el);
    size();
    set(data);

    return () => {
      cancelAnimationFrame(raf);
      ro.disconnect();
      controls.dispose();
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
      <div className="s3-stage" />
      <div className="s3-bar">
        <span className="f-note">drag: rotate · wheel: zoom · right drag: pan</span>
        <button type="button" className="f-tool" onClick={() => api.current?.fit()}>↺ reset view</button>
      </div>
    </div>
  );
}
