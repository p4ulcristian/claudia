import { expect, test } from "bun:test";
import { applyDraw, emptyScene, type Scene } from "./scene";

const draw = (scene: Scene, blocks: any[]) => applyDraw(scene, { blocks }).scene;
const ids = (scene: Scene) => (scene.blocks ?? []).map((b) => b.id);

test("a new 3D scene takes the old one's place instead of going last", () => {
  let s = draw(emptyScene(), [{ id: "top", html: "<p>a</p>" }, { id: "a", form: "scene3d", data: { parts: [{ id: "x" }] } }, { id: "end", html: "<p>b</p>" }]);
  s = draw(s, [{ id: "b", form: "scene3d", data: { parts: [{ id: "y" }] } }]);
  expect(ids(s)).toEqual(["top", "b", "end"]);
});

test("a new 3D scene with before goes there, and the old one is gone", () => {
  let s = draw(emptyScene(), [{ id: "top", html: "<p>a</p>" }, { id: "a", form: "scene3d", data: {} }]);
  s = draw(s, [{ id: "b", form: "scene3d", data: {}, before: "top" }]);
  expect(ids(s)).toEqual(["b", "top"]);
});

test("redrawing the same 3D scene keeps its place and merges its data", () => {
  let s = draw(emptyScene(), [{ id: "a", form: "scene3d", data: { parts: [{ id: "x", color: "red" }] } }, { id: "end", html: "<p>b</p>" }]);
  s = draw(s, [{ id: "a", data: { parts: [{ id: "y" }] } }]);
  expect(ids(s)).toEqual(["a", "end"]);
  const a = s.blocks!.find((b) => b.id === "a") as any;
  expect(a.data.parts.map((p: any) => p.id)).toEqual(["x", "y"]);
});

test("two 3D scenes in one call leave the last", () => {
  const s = draw(emptyScene(), [{ id: "a", form: "scene3d", data: {} }, { id: "b", form: "scene3d", data: {} }]);
  expect(ids(s)).toEqual(["b"]);
});

test("other forms still go last", () => {
  let s = draw(emptyScene(), [{ id: "a", form: "scene3d", data: {} }, { id: "c", form: "stats", data: {} }]);
  s = draw(s, [{ id: "d", form: "stats", data: {} }]);
  expect(ids(s)).toEqual(["a", "c", "d"]);
});
