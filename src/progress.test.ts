import { expect, test } from "bun:test";
import { lastProgress } from "./progress";

test("the last progress line wins, with its label", () => {
  expect(lastProgress("a\n##progress 1/4 tests\nb\n##progress 3/4 tests\nc\n")).toEqual({ done: 3, total: 4, label: "tests" });
});
test("a line redrawn in place counts, and a line without a label is fine", () => {
  expect(lastProgress("x\r##progress 2/5\r")).toEqual({ done: 2, total: 5, label: "" });
});
test("done is capped at total; a zero total or no line is none", () => {
  expect(lastProgress("##progress 9/4")).toEqual({ done: 4, total: 4, label: "" });
  expect(lastProgress("##progress 1/0")).toBeNull();
  expect(lastProgress("nothing here\n")).toBeNull();
  expect(lastProgress("say ##progress 1/2 inside a line")).toBeNull();
});
