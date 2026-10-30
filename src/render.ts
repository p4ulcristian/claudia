// A screenshot of Claude's picture, so it can see what it drew. A private page (web/render.html: the
// picture alone) is served on a loopback port nobody is told about; one headless Chromium, started on
// the first draw and closed after a minute without one, opens it, is handed the scene and shoots it.
// It needs no Claudia page open, so chats started from outside (Gus, delegated ones) see their picture too.
import { existsSync } from "node:fs";
import type { Browser } from "playwright-core";
import render from "../web/render.html";
import type { Scene } from "./scene";

const WIDTH = 1000;
const MAX_HEIGHT = 3200;
// After the picture is on the page its graph layout and 3D scenes still settle.
const SETTLE_MS = 3500;
const IDLE_MS = 60_000;
const TIMEOUT_MS = 20_000;
const SYSTEM_BROWSERS = "/opt/claudia-tools/ms-playwright";

let server: ReturnType<typeof Bun.serve> | null = null;
let browser: Promise<Browser> | null = null;
let idle: ReturnType<typeof setTimeout> | null = null;
let queue: Promise<unknown> = Promise.resolve();

function pageUrl(): string {
  server ??= Bun.serve({ hostname: "127.0.0.1", port: 0, development: false, routes: { "/": render }, fetch: () => new Response("Not found", { status: 404 }) });
  return `http://127.0.0.1:${server.port}/`;
}

function launch(): Promise<Browser> {
  browser ??= (async () => {
    if (!process.env.PLAYWRIGHT_BROWSERS_PATH && existsSync(SYSTEM_BROWSERS)) process.env.PLAYWRIGHT_BROWSERS_PATH = SYSTEM_BROWSERS;
    const { chromium } = await import("playwright-core");
    // Software GL: the box has no GPU for a headless browser, and the 3D scenes need WebGL.
    const b = await chromium.launch({ args: ["--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader"] });
    b.on("disconnected", () => { browser = null; });
    return b;
  })();
  browser.catch(() => { browser = null; });
  return browser;
}

function later() {
  if (idle) clearTimeout(idle);
  idle = setTimeout(async () => {
    const b = browser;
    browser = null;
    server?.stop(true);
    server = null;
    await (await b?.catch(() => null))?.close().catch(() => {});
  }, IDLE_MS);
}

async function shoot(scene: Scene): Promise<{ data: string } | { error: string }> {
  const b = await launch();
  const page = await b.newPage({ viewport: { width: WIDTH, height: 700 } });
  try {
    const logs: string[] = [];
    page.on("pageerror", (e) => logs.push(e.message));
    page.setDefaultTimeout(TIMEOUT_MS);
    await page.goto(pageUrl(), { waitUntil: "load" });
    await page.waitForFunction(() => typeof window.__draw === "function");
    await page.evaluate((s) => window.__draw(s), scene);
    await page.waitForFunction(() => window.__drawn === true);
    await page.evaluate(() => document.fonts.ready);
    await page.waitForTimeout(SETTLE_MS);
    const height = await page.evaluate(() => document.documentElement.scrollHeight);
    const png = await page.screenshot({ type: "png", fullPage: height <= MAX_HEIGHT, ...(height > MAX_HEIGHT ? { clip: { x: 0, y: 0, width: WIDTH, height: MAX_HEIGHT } } : {}) });
    if (logs.length) console.warn(`render: page errors: ${logs.join("; ")}`);
    return { data: png.toString("base64") };
  } catch (err) {
    return { error: String((err as Error)?.message ?? err).split("\n")[0]! };
  } finally {
    await page.close().catch(() => {});
    later();
  }
}

// One at a time: renders share one browser, and a burst of draws shouldn't pile up pages.
export function renderScene(scene: Scene): Promise<{ data: string } | { error: string }> {
  const run = queue.then(() => shoot(scene));
  queue = run.catch(() => {});
  return run;
}
