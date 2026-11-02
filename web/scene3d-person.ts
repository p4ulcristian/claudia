// A person in a scene3d: a rigged human (a CC0 MakeHuman body, web/person/person.glb, made by
// tools/person/make-person.py) posed live. A pose says where the hips, chest, hands and feet go
// (web/scene3d-poses.ts); this file bends the skeleton to reach them: the spine leans towards the chest
// point, arms and legs are two-bone chains solved exactly (the elbow or knee bends towards its pole),
// the head turns to what it looks at, feet lie flat on the floor unless told where the toes point.
// A new pose glides: every target moves from where it is to where it goes, the solver runs each frame.
import * as THREE from "three";
import { GLTFLoader } from "three/examples/jsm/loaders/GLTFLoader.js";
import { clone as cloneSkinned } from "three/examples/jsm/utils/SkeletonUtils.js";
import personUrl from "./person/person.glb" with { type: "file" };
import { mirror, poseOf, type Pose, type Rig, type V3 } from "./scene3d-poses";
import { outlineMaterial, toonMaterial } from "./scene3d-ink";

export const PERSON_HEIGHT = 1.75;
export const JOINTS = ["hips", "chest", "head", "handL", "handR", "footL", "footR"] as const;
export type Joint = (typeof JOINTS)[number];

let asset: Promise<THREE.Object3D> | null = null;
export function loadPerson(): Promise<THREE.Object3D> {
  asset ??= new GLTFLoader().loadAsync(personUrl).then((g) => g.scene);
  asset.catch(() => { asset = null; });
  return asset;
}

// Every target made explicit, so two poses can be blended field by field.
type Solved = {
  hips: V3; chest: V3; pelvis: V3; twist: number; look: V3 | null;
  handL: V3; handR: V3; elbowL: V3; elbowR: V3; footL: V3; footR: V3; kneeL: V3; kneeR: V3; toesL: V3 | null; toesR: V3 | null;
};

const v = (a: V3) => new THREE.Vector3(a[0], a[1], a[2]);
const arr = (p: THREE.Vector3): V3 => [p.x, p.y, p.z];
const I = new THREE.Quaternion();
const DEG = Math.PI / 180;
const ease = (p: number) => (p < 0.5 ? 4 * p * p * p : 1 - Math.pow(-2 * p + 2, 3) / 2);
const lerp3 = (a: V3, b: V3, t: number): V3 => [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t];
const perp = (a: THREE.Vector3, d: THREE.Vector3) => a.clone().addScaledVector(d, -a.dot(d));
// The rotation taking frame (d0, b0, d0 × b0) to (d1, b1, d1 × b1); b is made square to d first.
function frameTurn(d0: THREE.Vector3, b0: THREE.Vector3, d1: THREE.Vector3, b1: THREE.Vector3): THREE.Quaternion {
  const basis = (d: THREE.Vector3, b: THREE.Vector3) => {
    const bb = perp(b, d).normalize();
    return new THREE.Matrix4().makeBasis(d, bb, d.clone().cross(bb).normalize());
  };
  const m = basis(d1, b1).multiply(basis(d0, b0).transpose());
  return new THREE.Quaternion().setFromRotationMatrix(m);
}
function clampTurn(q: THREE.Quaternion, max: number) {
  const angle = 2 * Math.acos(Math.min(1, Math.abs(q.w)));
  return angle > max ? I.clone().slerp(q, max / angle) : q;
}

type Look = Record<"skin" | "top" | "bottom" | "shoes" | "hair" | "eyes", THREE.Color>;
type Body = { mesh: THREE.SkinnedMesh; ink: THREE.SkinnedMesh | null; slot: string };

export class Person {
  readonly meshes: THREE.SkinnedMesh[] = [];
  private bodies: Body[] = [];
  private bones = new Map<string, THREE.Bone>();
  private order: THREE.Bone[] = [];
  private restM = new Map<THREE.Bone, THREE.Matrix4>();
  private baseM = new THREE.Matrix4();   // the skeleton's top bone's parent, in the person's space
  private rig: Rig;
  private spec: unknown = "stand";
  private cur: Solved | null = null;
  private from: Solved | null = null;
  private t0 = 0;
  private dur = 0;
  private dirty = true;
  private attach = new Map<Joint, THREE.Object3D>();
  private look: Look;

  constructor(private proxy: THREE.Object3D, source: THREE.Object3D, private id: string, style: "diagram" | "real", private tint: (c: unknown) => THREE.Color, private find: (id: string) => THREE.Vector3 | null, spec: Record<string, any>) {
    const model = cloneSkinned(source);
    model.name = "person-model";
    proxy.add(model);
    model.traverse((o) => {
      if ((o as THREE.Bone).isBone) { this.bones.set(o.name, o as THREE.Bone); this.order.push(o as THREE.Bone); }
    });
    // face +z (the person's front), whatever way the model was exported
    proxy.updateMatrixWorld(true);
    const P0 = (name: string) => this.bone(name).getWorldPosition(new THREE.Vector3());
    if (P0("ball_l").z < P0("foot_l").z) model.rotation.y = Math.PI;
    this.measure();
    const defaults = this.defaultLook();
    this.look = defaults;
    this.recolour(spec);
    // own materials (and geometry: the asset's is shared by every person and must outlive this one)
    model.traverse((o) => {
      const m = o as THREE.SkinnedMesh;
      if (!m.isSkinnedMesh) return;
      m.geometry = m.geometry.clone();
      const slot = this.slotOf(m);
      const color = this.look[slot as keyof Look] ?? this.look.top;
      m.material = style === "diagram" ? toonMaterial(color.clone(), 1, false) : new THREE.MeshStandardMaterial({ color: color.clone(), roughness: 0.8, metalness: 0 });
      m.frustumCulled = false;
      m.castShadow = style === "real";
      m.receiveShadow = style === "real";
      m.userData.id = id;
      this.meshes.push(m);
      let ink: THREE.SkinnedMesh | null = null;
      if (style === "diagram" && spec.outline !== false) {
        ink = new THREE.SkinnedMesh(m.geometry, outlineMaterial(true));
        ink.bind(m.skeleton, m.bindMatrix);
        ink.frustumCulled = false;
        ink.userData.outline = true;
        ink.position.copy(m.position); ink.quaternion.copy(m.quaternion); ink.scale.copy(m.scale);
        m.parent!.add(ink);
      }
      this.bodies.push({ mesh: m, ink, slot });
    });
    this.rig = this.measureRig();
    this.setPose(spec.pose ?? "stand", 0);
    this.update(0);
  }

  private bone(name: string): THREE.Bone {
    const b = this.bones.get(name);
    if (!b) throw new Error(`person model has no bone ${name}`);
    return b;
  }

  // Each bone's rest matrix in the person's own space (the proxy's, without its scale).
  private measure() {
    this.proxy.updateMatrixWorld(true);
    const inv = this.proxy.matrixWorld.clone().invert();
    for (const b of this.order) this.restM.set(b, inv.clone().multiply(b.matrixWorld));
    const top = this.order[0];
    this.baseM = inv.clone().multiply(top.parent!.matrixWorld);
    for (const b of this.order) b.userData.rest = { q: b.quaternion.clone(), p: b.position.clone() };
  }
  private restP(name: string): THREE.Vector3 { return new THREE.Vector3().setFromMatrixPosition(this.restM.get(this.bone(name))!); }
  private restQ(name: string): THREE.Quaternion { return new THREE.Quaternion().setFromRotationMatrix(this.restM.get(this.bone(name))!.clone().extractRotation(this.restM.get(this.bone(name))!)); }

  private measureRig(): Rig {
    const P = (n: string) => arr(this.restP(n));
    const d = (a: string, b: string) => this.restP(a).distanceTo(this.restP(b));
    const L = (p: V3): V3 => (p[0] < 0 ? mirror(p) : p);   // the left side is +x
    let top = 0;
    this.proxy.updateMatrixWorld(true);
    const box = new THREE.Box3();
    for (const m of this.meshes) { m.skeleton.update(); m.computeBoundingBox(); box.union(m.boundingBox!.clone().applyMatrix4(this.proxy.matrixWorld.clone().invert().multiply(m.matrixWorld))); }
    top = box.isEmpty() ? PERSON_HEIGHT : box.max.y;
    return {
      pelvis: P("pelvis"), neck: P("neck_01"), head: P("head"), top,
      shoulder: L(P("upperarm_l")), elbow: L(P("lowerarm_l")), wrist: L(P("hand_l")), hip: L(P("thigh_l")), knee: L(P("calf_l")), ankle: L(P("foot_l")), ball: L(P("ball_l")),
      upper: d("upperarm_l", "lowerarm_l"), fore: d("lowerarm_l", "hand_l"), arm: d("upperarm_l", "lowerarm_l") + d("lowerarm_l", "hand_l"),
      thigh: d("thigh_l", "calf_l"), shin: d("calf_l", "foot_l"),
    };
  }

  private slotOf(m: THREE.SkinnedMesh): string {
    const mat = (Array.isArray(m.material) ? m.material[0] : m.material) as THREE.Material;
    const n = `${mat?.name ?? ""} ${m.name}`.toLowerCase();
    for (const s of ["skin", "top", "bottom", "shoes", "hair", "eyes"]) if (n.includes(s)) return s;
    return "skin";
  }
  private defaultLook(): Look {
    const c = (h: string) => new THREE.Color(h);
    const look: Look = { skin: c("#d9a78b"), top: c("#5b7fa6"), bottom: c("#3d4250"), shoes: c("#2b2b2b"), hair: c("#3b2a20"), eyes: c("#2b2b2b") };
    this.proxy.traverse((o) => {
      const m = o as THREE.SkinnedMesh;
      if (!m.isSkinnedMesh) return;
      const mat = (Array.isArray(m.material) ? m.material[0] : m.material) as THREE.MeshStandardMaterial;
      const s = this.slotOf(m) as keyof Look;
      if (mat?.color && s !== "top" && s !== "bottom") look[s] = mat.color.clone();
    });
    return look;
  }
  // color: the top; look: { skin, top, bottom, shoes, hair } for any of them
  recolour(spec: Record<string, any>) {
    if (spec.color) this.look.top = this.tint(spec.color);
    const l = spec.look && typeof spec.look === "object" ? spec.look : {};
    for (const k of Object.keys(this.look) as (keyof Look)[]) if (l[k]) this.look[k] = this.tint(l[k]);
    for (const b of this.bodies) (b.mesh.material as THREE.MeshStandardMaterial).color.copy(this.look[b.slot as keyof Look] ?? this.look.top);
  }

  // ------------------------------------------------------------------ poses

  setPose(spec: unknown, seconds: number) {
    this.spec = spec;
    this.from = seconds > 0 && this.cur ? this.cur : null;
    this.t0 = performance.now();
    this.dur = seconds * 1000;
    this.dirty = true;
  }
  get moving() { return !!this.from; }
  // true while the person still moves (or follows a part that may), so the view keeps drawing
  update(now = performance.now()): boolean {
    if (!this.dirty && !this.from && !this.refs) return false;
    const goal = this.resolve(poseOf(this.spec, this.rig));
    if (this.from) {
      const p = Math.min(1, (now - this.t0) / Math.max(this.dur, 1));
      this.cur = this.blend(this.from, goal, ease(p));
      if (p >= 1) this.from = null;
    } else this.cur = goal;
    this.dirty = false;
    this.solve(this.cur);
    return !!this.from;
  }
  private get refs() {
    const p = poseOf(this.spec, this.rig) as Record<string, unknown>;
    return ["look", "handL", "handR", "footL", "footR"].some((k) => typeof p[k] === "string");
  }

  // a point, or a part's centre turned into the person's space
  private point(t: unknown, fallback: V3): V3 {
    if (Array.isArray(t)) return t as V3;
    if (typeof t === "string") {
      const w = this.find(t);
      if (w) {
        this.proxy.updateMatrixWorld(true);
        return arr(this.proxy.worldToLocal(w.clone()));
      }
    }
    return fallback;
  }

  private resolve(p: Pose): Solved {
    const r = this.rig;
    const hips = (p.hips ?? [0, r.pelvis[1], 0]) as V3;
    const chest = (p.chest ?? [hips[0], hips[1] + (r.neck[1] - r.pelvis[1]), hips[2]]) as V3;
    const s0 = this.restP("neck_01").sub(this.restP("pelvis")).normalize();
    const s1 = v(chest).sub(v(hips)).normalize();
    const full = new THREE.Quaternion().setFromUnitVectors(s0, s1);
    let pelvis = p.pelvis;
    if (!pelvis) {
      const e = new THREE.Euler().setFromQuaternion(I.clone().slerp(full, 0.35), "XYZ");
      pelvis = [e.x / DEG, e.y / DEG, e.z / DEG];
    }
    const Rp = new THREE.Quaternion().setFromEuler(new THREE.Euler(pelvis[0] * DEG, pelvis[1] * DEG, pelvis[2] * DEG, "XYZ"));
    const pRest = this.restP("pelvis");
    const at = (name: string, R: THREE.Quaternion) => v(hips).add(this.restP(name).sub(pRest).applyQuaternion(R));
    const handL = this.point(p.handL, r.wrist), handR = this.point(p.handR, mirror(r.wrist));
    const footL = this.point(p.footL, r.ankle), footR = this.point(p.footR, mirror(r.ankle));
    const elbowPole = (side: 1 | -1, hand: V3) => {
      const s = at(side > 0 ? "upperarm_l" : "upperarm_r", full);
      return arr(s.add(v(hand)).multiplyScalar(0.5).add(new THREE.Vector3(side * 0.3, -0.25, -0.35).applyQuaternion(full)));
    };
    const kneePole = (side: 1 | -1, foot: V3) => {
      const h = at(side > 0 ? "thigh_l" : "thigh_r", Rp);
      return arr(h.add(v(foot)).multiplyScalar(0.5).add(new THREE.Vector3(side * 0.05, 0, 0.6).applyQuaternion(Rp)));
    };
    const footLen = r.ball[2] - r.ankle[2], footDrop = r.ball[1] - r.ankle[1];
    const toes = (t: V3 | undefined, foot: V3): V3 | null => t ?? (foot[1] < 0.25 ? [foot[0], foot[1] + footDrop, foot[2] + Math.max(footLen, 0.1)] : null);
    const lookAt = p.look === undefined ? null : this.point(p.look, [0, 1.6, 3]);
    return {
      hips, chest, pelvis, twist: p.twist ?? 0, look: lookAt,
      handL, handR, footL, footR,
      elbowL: p.elbowL ?? elbowPole(1, handL), elbowR: p.elbowR ?? elbowPole(-1, handR),
      kneeL: p.kneeL ?? kneePole(1, footL), kneeR: p.kneeR ?? kneePole(-1, footR),
      toesL: toes(p.toesL, footL), toesR: toes(p.toesR, footR),
    };
  }

  private blend(a: Solved, b: Solved, t: number): Solved {
    const o: Record<string, unknown> = {};
    for (const k of Object.keys(b) as (keyof Solved)[]) {
      const x = a[k], y = b[k];
      if (typeof x === "number" && typeof y === "number") o[k] = x + (y - x) * t;
      else if (Array.isArray(x) && Array.isArray(y)) o[k] = lerp3(x as V3, y as V3, t);
      else o[k] = t < 0.5 ? x : y;
    }
    return o as Solved;
  }

  // ------------------------------------------------------------------ the solver

  private solve(s: Solved) {
    const want = new Map<THREE.Bone, { q?: THREE.Quaternion; p?: THREE.Vector3 }>();
    const M = new Map<THREE.Bone, THREE.Matrix4>();
    const rq = (n: string) => this.restQ(n);
    // the hips and the spine
    const s0 = this.restP("neck_01").sub(this.restP("pelvis")).normalize();
    const s1 = v(s.chest).sub(v(s.hips)).normalize();
    const Rp = new THREE.Quaternion().setFromEuler(new THREE.Euler(s.pelvis[0] * DEG, s.pelvis[1] * DEG, s.pelvis[2] * DEG, "XYZ"));
    const rem = new THREE.Quaternion().setFromUnitVectors(s0.clone().applyQuaternion(Rp), s1);
    want.set(this.bone("pelvis"), { q: Rp.clone().multiply(rq("pelvis")), p: v(s.hips) });
    const spine = ["spine_01", "spine_02", "spine_03"];
    const chestQ = (k: number) => new THREE.Quaternion().setFromAxisAngle(s1, s.twist * DEG * k).multiply(I.clone().slerp(rem, k)).multiply(Rp);
    spine.forEach((n, i) => want.set(this.bone(n), { q: chestQ((i + 1) / 3).multiply(rq(n)) }));
    const Rc = chestQ(1);

    const posOf = (b: THREE.Bone, parentM: THREE.Matrix4) => (b.userData.rest.p as THREE.Vector3).clone().applyMatrix4(parentM);
    const rotOf = (m: THREE.Matrix4) => new THREE.Quaternion().setFromRotationMatrix(new THREE.Matrix4().extractRotation(m));
    const limb = (upper: string, lower: string, end: string, target: V3, pole: V3, swing: THREE.Vector3, S: THREE.Vector3) => {
      const A0 = this.restP(upper), B0 = this.restP(lower), C0 = this.restP(end);
      const l1 = A0.distanceTo(B0), l2 = B0.distanceTo(C0);
      const d0 = B0.clone().sub(A0).normalize(), d0b = C0.clone().sub(B0).normalize();
      // the way the lower bone swings when the joint bends, at rest
      let b0 = perp(d0b, d0);
      if (b0.length() < 0.05) b0 = perp(swing, d0);
      b0.normalize();
      const T = v(target);
      let dir = T.clone().sub(S);
      const dist = Math.min(Math.max(dir.length(), Math.abs(l1 - l2) + 1e-3), (l1 + l2) * 0.9995);
      if (dir.lengthSq() < 1e-9) dir = d0.clone(); else dir.normalize();
      let u = perp(v(pole).sub(S), dir);
      if (u.length() < 1e-4) u = perp(b0.clone().negate(), dir);
      if (u.length() < 1e-4) u = perp(new THREE.Vector3(0, 0, 1), dir);
      u.normalize();
      const cosA = Math.min(1, Math.max(-1, (l1 * l1 + dist * dist - l2 * l2) / (2 * l1 * dist)));
      const a = Math.acos(cosA);
      const E = S.clone().addScaledVector(dir, Math.cos(a) * l1).addScaledVector(u, Math.sin(a) * l1);
      const Tc = S.clone().addScaledVector(dir, dist);
      const d1 = E.clone().sub(S).normalize(), d2 = Tc.clone().sub(E).normalize();
      let b1 = perp(d2, d1);
      if (b1.length() < 1e-3) b1 = perp(u.clone().negate(), d1);
      b1.normalize();
      const up = frameTurn(d0, b0, d1, b1);
      const h0 = d0.clone().cross(b0).normalize(), h1 = d1.clone().cross(b1).normalize();
      const lo = frameTurn(d0b, h0.clone().cross(d0b), d2, h1.clone().cross(d2));
      want.set(this.bone(upper), { q: up.multiply(rq(upper)) });
      want.set(this.bone(lower), { q: lo.multiply(rq(lower)) });
    };

    // walk the skeleton from the top: each bone's matrix in the person's space, from its parent's
    for (const b of this.order) {
      const parentM = b.parent && (b.parent as THREE.Bone).isBone ? M.get(b.parent as THREE.Bone)! : this.baseM;
      const name = b.name;
      // targets that need where this bone's head is now
      if (name === "neck_01" && s.look) {
        const neckPos = posOf(b, parentM);
        const headPos = neckPos.clone().add(this.restP("head").sub(this.restP("neck_01")).applyQuaternion(Rc)).add(new THREE.Vector3(0, 0.08, 0));
        const f = new THREE.Vector3(0, 0, 1).applyQuaternion(Rc);
        const d = v(s.look).sub(headPos).normalize();
        const R = clampTurn(new THREE.Quaternion().setFromUnitVectors(f, d), 75 * DEG);
        want.set(b, { q: I.clone().slerp(R, 0.4).multiply(Rc).multiply(rq("neck_01")) });
        want.set(this.bone("head"), { q: R.clone().multiply(Rc).multiply(rq("head")) });
      }
      if (name === "upperarm_l") limb("upperarm_l", "lowerarm_l", "hand_l", s.handL, s.elbowL, new THREE.Vector3(0, 0, 1), posOf(b, parentM));
      if (name === "upperarm_r") limb("upperarm_r", "lowerarm_r", "hand_r", s.handR, s.elbowR, new THREE.Vector3(0, 0, 1), posOf(b, parentM));
      if (name === "thigh_l") limb("thigh_l", "calf_l", "foot_l", s.footL, s.kneeL, new THREE.Vector3(0, 0, -1), posOf(b, parentM));
      if (name === "thigh_r") limb("thigh_r", "calf_r", "foot_r", s.footR, s.kneeR, new THREE.Vector3(0, 0, -1), posOf(b, parentM));
      for (const [foot, ball, toes] of [["foot_l", "ball_l", s.toesL], ["foot_r", "ball_r", s.toesR]] as const) {
        if (name !== foot || !toes) continue;
        const A = posOf(b, parentM);
        const fd0 = this.restP(ball).sub(this.restP(foot)).normalize();
        const fd1 = v(toes).sub(A);
        if (fd1.lengthSq() < 1e-8) continue;
        fd1.normalize();
        const up0 = new THREE.Vector3(0, 1, 0);
        const up1 = up0.clone().applyQuaternion(new THREE.Quaternion().setFromUnitVectors(fd0, fd1));
        want.set(b, { q: frameTurn(fd0, up0, fd1, up1).multiply(rq(foot)) });
      }
      const w = want.get(b);
      const rest = b.userData.rest as { q: THREE.Quaternion; p: THREE.Vector3 };
      const q = w?.q ? rotOf(parentM).invert().multiply(w.q) : rest.q.clone();
      const p = w?.p ? w.p.clone().applyMatrix4(parentM.clone().invert()) : rest.p.clone();
      b.quaternion.copy(q);
      b.position.copy(p);
      M.set(b, parentM.clone().multiply(new THREE.Matrix4().compose(p, q, b.scale)));
    }
    this.proxy.updateMatrixWorld(true);
    for (const m of this.meshes) { m.skeleton.update(); m.computeBoundingBox(); m.computeBoundingSphere(); }
  }

  // ------------------------------------------------------------------ for the view

  // A frame that rides on a joint, its axes the person's own at rest (x left, y up, z front), its origin
  // on the joint: a part with parent "<person>.<joint>" hangs from it.
  joint(name: string): THREE.Object3D | null {
    if (!(JOINTS as readonly string[]).includes(name)) return null;
    const j = name as Joint;
    let o = this.attach.get(j);
    if (o) return o;
    const P = (n: string) => this.restP(n);
    const at: Record<Joint, [string, THREE.Vector3]> = {
      hips: ["pelvis", P("pelvis")],
      chest: ["spine_03", P("spine_03").lerp(P("neck_01"), 0.4)],
      head: ["head", P("head").add(new THREE.Vector3(0, 0.1, 0.02))],
      handL: ["hand_l", this.palm("l")],
      handR: ["hand_r", this.palm("r")],
      footL: ["foot_l", P("foot_l")],
      footR: ["foot_r", P("foot_r")],
    };
    const [boneName, point] = at[j];
    const b = this.bone(boneName);
    const local = this.restM.get(b)!.clone().invert().multiply(new THREE.Matrix4().makeTranslation(point.x, point.y, point.z));
    o = new THREE.Object3D();
    local.decompose(o.position, o.quaternion, o.scale);
    b.add(o);
    this.attach.set(j, o);
    return o;
  }
  private palm(side: "l" | "r"): THREE.Vector3 {
    const h = this.restP(`hand_${side}`);
    const mid = this.bones.has(`middle_01_${side}`) ? this.restP(`middle_01_${side}`) : h.clone().add(h.clone().sub(this.restP(`lowerarm_${side}`)).normalize().multiplyScalar(0.09));
    return h.lerp(mid, 0.6);
  }

  // where its label goes: just over the head (world)
  labelPoint(out: THREE.Vector3): THREE.Vector3 {
    const head = this.bone("head");
    head.getWorldPosition(out);
    const s = this.proxy.getWorldScale(new THREE.Vector3()).y;
    out.y += 0.32 * s;
    return out;
  }

  // how far it has appeared (k), how far faded back (dim), outlined thick (lit), its own opacity (op)
  reveal(k: number, dim: number, lit: boolean, op: number, ink: { color: THREE.Color; thick: number; thin: number; focusInk: THREE.Color }) {
    for (const b of this.bodies) {
      const mat = b.mesh.material as THREE.MeshStandardMaterial;
      mat.opacity = op * k * dim;
      mat.transparent = op < 1 || k < 1 || dim < 1;
      mat.depthWrite = !mat.transparent || op * k * dim > 0.5;
      b.mesh.visible = k > 0.003;
      b.mesh.castShadow = b.mesh.userData.casts !== false && k > 0.5 && dim > 0.5;
      if (b.ink) {
        const u = (b.ink.material as THREE.ShaderMaterial).uniforms;
        b.ink.visible = k > 0.003;
        u.opacity.value = k * (dim < 1 ? 0.28 : op < 1 ? 0.6 : 1);
        u.thickness.value = lit ? ink.thick : ink.thin;
        (u.color.value as THREE.Color).copy(lit ? ink.focusInk : ink.color);
      }
    }
  }
  emissive(hex: number, intensity: number) {
    for (const b of this.bodies) {
      const m = b.mesh.material as THREE.MeshStandardMaterial;
      m.emissive?.setHex(hex);
      if (m.emissive) m.emissiveIntensity = intensity;
    }
  }
}
