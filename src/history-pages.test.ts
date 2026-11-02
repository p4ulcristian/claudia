import { expect, test } from "bun:test";
import { PAGE, olderPage, pageStart, itemKey } from "./history-pages";
import type { Item } from "./chat";

// n turns: a user message and a few of Claude's texts each.
const turns = (n: number, each: number): Item[] =>
  Array.from({ length: n }, (_, t) => [
    { k: "user", uuid: `u${t}`, text: `ask ${t}`, images: 0 } as Item,
    ...Array.from({ length: each }, (_, i) => ({ k: "text", id: `m${t}-${i}:0`, text: "x" }) as Item),
  ]).flat();

test("a page starts at a user message, at least PAGE items back", () => {
  const items = turns(100, 9);
  const start = pageStart(items, items.length);
  expect(items[start]!.k).toBe("user");
  expect(items.length - start).toBeGreaterThanOrEqual(PAGE);
  expect(items.length - start).toBeLessThan(PAGE + 10);
});

test("a short chat is one page", () => {
  const items = turns(3, 2);
  expect(pageStart(items, items.length)).toBe(0);
});

test("older pages join up with no gap and end with more = false", () => {
  const items = turns(100, 9);
  let have = items.slice(pageStart(items, items.length));
  let more = true;
  while (more) {
    const page = olderPage(items, itemKey(have[0]!));
    have = [...page.items, ...have];
    more = page.more;
  }
  expect(have.map(itemKey)).toEqual(items.map(itemKey));
});

test("until reaches back to a search hit's message", () => {
  const items = turns(100, 9);
  const first = pageStart(items, items.length);
  const page = olderPage(items, itemKey(items[first]!), "m2-3");
  expect(page.items[0]).toEqual(items[20]); // turn 2's user message
});
