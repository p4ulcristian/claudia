// Renders the named poses to PNGs, to check the person part by eye:
//   bun tools/person/try-poses.ts [outdir] [pose…]     (no poses: all of them, side by side, one file)
// With poses named, each gets its own close render (outdir/pose-<name>.png).
import { renderScene } from "../../src/render";
import { POSE_NAMES } from "../../web/scene3d-poses";

const dir = process.argv[2] ?? "/tmp";
const only = process.argv.slice(3);

const shot = async (parts: any[], file: string, extra: Record<string, unknown> = {}) => {
  const scene: any = { title: "poses", nodes: [], edges: [], groups: [], cards: [], blocks: [{ id: "s", kind: "form", form: "scene3d", name: "poses", data: { parts, ...extra } }] };
  const r = await renderScene(scene);
  if ("error" in r) throw new Error(r.error);
  await Bun.write(file, Buffer.from(r.data, "base64"));
  console.log(file);
};
const chair = (x: number, z: number) => ({ id: "chair", shape: "box", pos: [x, 0.22, z - 0.05], size: [0.5, 0.44, 0.5], color: "grey" });

if (!only.length) {
  const parts: any[] = POSE_NAMES.map((name, i) => ({ id: `p_${name}`, shape: "person", pos: [(i % 5) * 1.6 - 3.2, 0, -Math.floor(i / 5) * 2.6], pose: name, label: name }));
  const seat = parts[POSE_NAMES.indexOf("sit")];
  parts.push(chair(seat.pos[0], seat.pos[2]));
  await shot(parts, `${dir}/poses.png`);
} else {
  for (const name of only) {
    const parts: any[] = [{ id: "p", shape: "person", pos: [0, 0, 0], pose: name, label: name }];
    if (name === "sit") parts.push(chair(0, 0));
    // a three-quarter view from the front left
    await shot(parts, `${dir}/pose-${name}.png`, { story: [{ camera: { frame: ["p"], az: 35, el: 12 }, hold: 1 }] });
  }
}
process.exit(0);
