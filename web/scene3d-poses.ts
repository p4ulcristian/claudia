// Named poses for the scene3d person: each is where the hips, chest, hands and feet go, worked out from the
// body's own measurements (so a pose fits the model whatever its proportions). Coordinates are the person's
// own: x + is their left, y up, z + in front of them, metres for a 1.75 m person, the floor at y = 0.
// A pose given in a scene starts from one of these (`base`) and overrides any of its fields.

export type V3 = [number, number, number];
export type Target = V3 | string;   // a point, or a part id (the hand goes to that part's centre)

// What a pose may say. Unsaid fields come from the base pose; poles (elbow, knee) and the toes get defaults.
export type Pose = {
  hips?: V3;            // where the pelvis sits
  chest?: V3;           // where the base of the neck goes: the spine bends towards it
  pelvis?: V3;          // the pelvis tilt in degrees [x: + forward, − back; y: turn to the left; z: side]; unsaid = follows the spine a little
  twist?: number;       // the chest turned against the hips, degrees (+ to the person's left)
  look?: Target;        // what the face turns to
  handL?: Target; handR?: Target;
  elbowL?: V3; elbowR?: V3;   // which way an elbow points (a point it bends towards)
  footL?: Target; footR?: Target;
  kneeL?: V3; kneeR?: V3;
  toesL?: V3; toesR?: V3;     // where the toes point; unsaid = flat on the floor, or following the shin when lifted
};

// The body's joints in its rest pose, measured from the model (left side; the right is the mirror image).
export type Rig = {
  pelvis: V3; neck: V3; head: V3; top: number;
  shoulder: V3; elbow: V3; wrist: V3; hip: V3; knee: V3; ankle: V3; ball: V3;
  arm: number; upper: number; fore: number; thigh: number; shin: number;
};

const add = (a: V3, b: V3): V3 => [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
export const mirror = (v: V3): V3 => [-v[0], v[1], v[2]];

function stand(r: Rig): Pose {
  const hand = add(r.shoulder, [0.07, -r.arm * 0.93, 0.04]);
  const foot: V3 = [r.hip[0] * 0.95, r.ankle[1], r.ankle[2]];
  return { hips: [0, r.pelvis[1] - 0.005, r.pelvis[2]], chest: r.neck, handL: hand, handR: mirror(hand), footL: foot, footR: mirror(foot) };
}

function sit(r: Rig): Pose {
  const seat = 0.46;
  const hipY = seat + 0.09;
  const back = -0.05;
  const knee: V3 = [r.hip[0], hipY - 0.02, back + r.thigh * 0.98];
  const foot: V3 = [r.hip[0] * 1.05, r.ankle[1], knee[2] + 0.05];
  const hand: V3 = [0.16, hipY + 0.1, back + r.thigh * 0.6];
  return { hips: [0, hipY, back], chest: [0, hipY + (r.neck[1] - r.pelvis[1]), back - 0.02], footL: foot, footR: mirror(foot), kneeL: add(knee, [0, 0.2, 0.4]), kneeR: mirror(add(knee, [0, 0.2, 0.4])), handL: hand, handR: mirror(hand) };
}

const POSES: Record<string, (r: Rig) => Pose> = {
  stand,
  // arms straight out to the sides
  t: (r) => {
    const hand = add(r.shoulder, [r.arm * 0.98, 0, 0]);
    return { ...stand(r), handL: hand, handR: mirror(hand) };
  },
  // both hands up over the head
  reach: (r) => {
    const hand = add(r.shoulder, [0.1, r.arm * 0.96, 0.06]);
    return { ...stand(r), handL: hand, handR: mirror(hand), look: [0, 3, 1.5] };
  },
  // the right hand raised beside the head
  wave: (r) => {
    const s = mirror(r.shoulder);
    return { ...stand(r), handR: add(s, [-0.28, 0.38, 0.08]), elbowR: add(s, [-0.6, -0.1, 0]) };
  },
  // the right arm straight out in front
  point: (r) => {
    const s = mirror(r.shoulder);
    return { ...stand(r), handR: add(s, [0.04, 0.02, r.arm * 0.97]), look: [0, 1.5, 3] };
  },
  hands_on_hips: (r) => {
    const hand: V3 = [r.hip[0] + 0.07, r.pelvis[1] + 0.1, 0.02];
    return { ...stand(r), handL: hand, handR: mirror(hand), elbowL: [0.7, r.pelvis[1] + 0.15, -0.1], elbowR: [-0.7, r.pelvis[1] + 0.15, -0.1] };
  },
  // hands in front at waist height, as if carrying a box
  hold: (r) => {
    const hand: V3 = [0.17, r.pelvis[1] + 0.22, 0.36];
    return { ...stand(r), handL: hand, handR: mirror(hand), elbowL: [0.5, r.pelvis[1], -0.2], elbowR: [-0.5, r.pelvis[1], -0.2], look: [0, 0.9, 1.5] };
  },
  // mid-step: the left foot forward, arms swinging the other way
  walk: (r) => {
    const s = stand(r);
    return {
      ...s,
      hips: add(s.hips!, [0, -0.03, 0]),
      footL: [r.hip[0], r.ankle[1], 0.3],
      footR: [-r.hip[0], r.ankle[1] + 0.06, -0.3],
      toesR: [-r.hip[0], 0.0, -0.18],
      handL: add(r.shoulder, [0.06, -r.arm * 0.9, -0.18]),
      handR: add(mirror(r.shoulder), [-0.06, -r.arm * 0.9, 0.2]),
      look: [0, 1.6, 4],
    };
  },
  // sitting on a chair (seat 0.46 high), hands on the thighs
  sit,
  // sitting on the floor, legs out in front, leaning back on the hands
  sit_floor: (r) => {
    const hipY = 0.11;
    const foot: V3 = [r.hip[0] * 1.1, 0.08, (r.thigh + r.shin) * 0.97];
    const hand: V3 = [0.24, 0.03, -0.24];
    return { hips: [0, hipY, 0], chest: [0, hipY + (r.neck[1] - r.pelvis[1]) * 0.97, -0.1], pelvis: [-5, 0, 0], footL: foot, footR: mirror(foot), toesL: add(foot, [0, 0.25, 0.05]), toesR: mirror(add(foot, [0, 0.25, 0.05])), kneeL: [r.hip[0], 1, 0.4], kneeR: [-r.hip[0], 1, 0.4], handL: hand, handR: mirror(hand), elbowL: [0.5, 0.3, -0.6], elbowR: [-0.5, 0.3, -0.6] };
  },
  // upright on both knees
  kneel: (r) => {
    const knee = 0.06;
    const hipY = knee + r.thigh * 0.97;
    const foot: V3 = [r.hip[0], 0.09, -r.shin * 0.92];
    const s = stand(r);
    return { ...s, hips: [0, hipY, 0], chest: [0, hipY + (r.neck[1] - r.pelvis[1]), 0], footL: foot, footR: mirror(foot), toesL: add(foot, [0, -0.06, -0.2]), toesR: mirror(add(foot, [0, -0.06, -0.2])), kneeL: [r.hip[0], 0.3, 1], kneeR: [-r.hip[0], 0.3, 1], handL: add(s.handL as V3, [0, hipY - r.pelvis[1], 0]), handR: add(s.handR as V3, [0, hipY - r.pelvis[1], 0]) };
  },
  // a deep squat, heels down, arms forward for balance
  squat: (r) => {
    const hipY = 0.5, back = -0.22;
    const foot: V3 = [r.hip[0] * 1.3, r.ankle[1], r.ankle[2]];
    const hand: V3 = [0.17, 0.95, 0.5];
    return { hips: [0, hipY, back], chest: [0, hipY + (r.neck[1] - r.pelvis[1]) * 0.95, back + 0.3], footL: foot, footR: mirror(foot), kneeL: [0.4, 0.6, 1], kneeR: [-0.4, 0.6, 1], handL: hand, handR: mirror(hand), look: [0, 1.2, 3] };
  },
  // bent forward at the hips, hands hanging towards the floor
  bend: (r) => {
    const s = stand(r);
    const hip = r.pelvis[1] - 0.04;
    const hand: V3 = [0.15, 0.2, 0.36];
    return { ...s, hips: [0, hip, -0.16], chest: [0, hip - 0.1, 0.38], pelvis: [55, 0, 0], handL: hand, handR: mirror(hand), look: [0, 0, 1] };
  },
  // lying on the back, head towards -z, feet towards +z
  lie: (r) => {
    const y = 0.11;
    const torso = r.neck[1] - r.pelvis[1];
    const foot: V3 = [r.hip[0] * 1.15, 0.09, r.thigh + r.shin];
    const hand: V3 = [0.3, 0.06, 0.05];
    return { hips: [0, y, 0], chest: [0, y + 0.02, -torso], pelvis: [-90, 0, 0], footL: foot, footR: mirror(foot), toesL: add(foot, [0, 0.25, 0.04]), toesR: mirror(add(foot, [0, 0.25, 0.04])), kneeL: [r.hip[0], 1, 0.4], kneeR: [-r.hip[0], 1, 0.4], handL: hand, handR: mirror(hand), elbowL: [0.6, 0, -0.25], elbowR: [-0.6, 0, -0.25], look: [0, 3, -torso] };
  },
};

export const POSE_NAMES = Object.keys(POSES);

// A pose as a scene gives it (a name, or an object with an optional base) made whole: every field the
// base pose has, with the scene's overrides on top.
export function poseOf(spec: unknown, r: Rig): Pose {
  if (typeof spec === "string") return (POSES[spec] ?? stand)(r);
  if (!spec || typeof spec !== "object") return stand(r);
  const s = spec as Record<string, unknown>;
  const base = (POSES[String(s.base ?? "stand")] ?? stand)(r);
  const out: Record<string, unknown> = { ...base };
  for (const k of ["hips", "chest", "pelvis", "elbowL", "elbowR", "kneeL", "kneeR", "toesL", "toesR"]) {
    const v = s[k];
    if (Array.isArray(v) && v.length >= 3) out[k] = [Number(v[0]) || 0, Number(v[1]) || 0, Number(v[2]) || 0];
  }
  for (const k of ["look", "handL", "handR", "footL", "footR"]) {
    const v = s[k];
    if (typeof v === "string" && v) out[k] = v;
    else if (Array.isArray(v) && v.length >= 3) out[k] = [Number(v[0]) || 0, Number(v[1]) || 0, Number(v[2]) || 0];
  }
  if (Number.isFinite(Number(s.twist)) && s.twist !== undefined) out.twist = Number(s.twist);
  return out as Pose;
}
