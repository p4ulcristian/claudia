// Which solid parts of a scene3d intersect right now. Exact on the surfaces (three-mesh-bvh triangle
// tests), after a cheap bounding-box pass; a part wholly inside another is found too (ray parity).
// Housings and ghosts are not solid (translucent, wire, plane, ring, or `ghost: true`); pairs that are
// meant to touch or overlap (a pin inside a rod end) are listed in the scene's `allow`.
import * as THREE from "three";
import { MeshBVH } from "three-mesh-bvh";

// Parts may overlap by up to this much (scene units) before they count: surfaces that merely touch pass.
const CLEARANCE = 0.02;

export type Overlap = [string, string];
type Item = { id: string; mesh: THREE.Mesh; bvh: MeshBVH; shrink: THREE.Matrix4 };

export const isSolid = (spec: Record<string, any>) =>
  !spec.ghost && !spec.wire && !(typeof spec.opacity === "number" && spec.opacity < 1) && !["plane", "ring", "group"].includes(spec.shape);

// "pin*" matches every id that starts with "pin".
const matches = (pattern: string, id: string) => (pattern.endsWith("*") ? id.startsWith(pattern.slice(0, -1)) : pattern === id);

const _a = new THREE.Matrix4(), _inv = new THREE.Matrix4(), _ray = new THREE.Ray();

export function makeChecker(root: THREE.Object3D, parts: { id: string; mesh: THREE.Mesh; spec: Record<string, any> }[], allow: unknown[]) {
  const items: Item[] = parts.filter((p) => isSolid(p.spec)).map((p) => {
    const g = p.mesh.geometry;
    g.boundsTree ??= new MeshBVH(g);
    g.computeBoundingBox();
    // shrink about the box centre by CLEARANCE on every side, so touching surfaces do not count
    const c = g.boundingBox!.getCenter(new THREE.Vector3());
    const half = g.boundingBox!.getSize(new THREE.Vector3()).multiplyScalar(0.5);
    const k = (h: number) => (h > 1e-6 ? Math.max(0.5, 1 - CLEARANCE / h) : 1);
    const shrink = new THREE.Matrix4()
      .makeTranslation(c.x, c.y, c.z)
      .multiply(new THREE.Matrix4().makeScale(k(half.x), k(half.y), k(half.z)))
      .multiply(new THREE.Matrix4().makeTranslation(-c.x, -c.y, -c.z));
    return { id: p.id, mesh: p.mesh, bvh: g.boundsTree as MeshBVH, shrink };
  });
  const pairs = list(allow).filter((p) => Array.isArray(p) && p.length === 2) as [string, string][];
  const allowed = (a: string, b: string) => pairs.some(([p, q]) => (matches(p, a) && matches(q, b)) || (matches(p, b) && matches(q, a)));
  const skip = new Set<string>();
  items.forEach((x, i) => items.forEach((y, j) => { if (i < j && allowed(x.id, y.id)) skip.add(`${i},${j}`); }));

  // true when the world point is inside the mesh (odd number of surface hits along a ray)
  const inside = (p: THREE.Vector3, it: Item) => {
    _inv.copy(it.mesh.matrixWorld).invert();
    _ray.origin.copy(p).applyMatrix4(_inv);
    _ray.direction.set(1, 0.0137, 0.0071).normalize();
    return it.bvh.raycast(_ray, THREE.DoubleSide).length % 2 === 1;
  };

  return {
    count: items.length,
    check(isOn?: (id: string) => boolean): Overlap[] {
      root.updateMatrixWorld(true);
      const boxes = items.map((it) => {
        return new THREE.Box3().setFromObject(it.mesh);
      });
      const out: Overlap[] = [];
      for (let i = 0; i < items.length; i++) {
        for (let j = i + 1; j < items.length; j++) {
          if (skip.has(`${i},${j}`) || !boxes[i].intersectsBox(boxes[j])) continue;
          if (isOn && (!isOn(items[i].id) || !isOn(items[j].id))) continue;
          const A = items[i], B = items[j];
          _a.copy(A.mesh.matrixWorld).invert().multiply(B.mesh.matrixWorld).multiply(B.shrink);
          let hit = A.bvh.intersectsGeometry(B.mesh.geometry, _a);
          if (!hit && boxes[i].containsBox(boxes[j])) hit = inside(boxes[j].getCenter(new THREE.Vector3()), A);
          if (!hit && boxes[j].containsBox(boxes[i])) hit = inside(boxes[i].getCenter(new THREE.Vector3()), B);
          if (hit) out.push([A.id, B.id]);
        }
      }
      return out;
    },
  };
}

const list = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
