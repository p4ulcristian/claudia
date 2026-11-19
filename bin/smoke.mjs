#!/usr/bin/env node
// The smoke test (README decision 35): the built programs (out/hub.js, out/front.js, out/runner.js,
// out/web) run together on loopback with throwaway data and one seeded chat, and a headless browser
// uses the page as a person would, on a desktop and a phone screen: the chat list comes, the chat
// opens and shows its messages, the runner answers no frame with an error and the page throws
// nothing. Unit tests check each side of a wire; this checks that the page and the servers still
// understand each other (#171 shipped a page whose every first frame the runner refused).
//
//   node bin/smoke.mjs            after `npx shadow-cljs release host runner front hub broker root sudo web` and
//                                 `npm run build:web-files`
//
// The browser: SMOKE_BROWSER=webkit runs Playwright's WebKit (`npx playwright-core install webkit`;
// on a Mac it is Apple's WebKit, the engine of Safari and the iPhone app, README decision 36), else
// Chromium: SMOKE_CHROMIUM (a Chromium or Chrome binary), else the one playwright-core installed
// (`npx playwright-core install chromium-headless-shell`). No session host and no Claude token: the
// smoke test reads and opens chats, it never starts Claude.

import { createRequire } from 'node:module';
import { join } from 'node:path';
import { repo, email, chatId, title, question, reply, waitMs, until, startStack } from './stack.mjs';

const require = createRequire(join(repo, 'package.json'));
const { chromium, webkit } = require('playwright-core');
const engine = process.env.SMOKE_BROWSER === 'webkit' ? 'webkit' : 'chromium';

const say = (...a) => console.log('smoke:', ...a);

// What the page sent and heard on its sockets, and what went wrong on the way.
function watch(page) {
  const seen = { sent: [], heard: [], problems: [], health: [] };
  page.on('pageerror', (e) => seen.problems.push(`page error: ${e.message}`));
  page.on('request', (r) => {
    if (r.url().endsWith('/hub/health') && r.method() === 'POST') seen.health.push(JSON.parse(r.postData() || '{}'));
  });
  page.on('websocket', (ws) => {
    ws.on('framesent', ({ payload }) => {
      let f;
      try { f = JSON.parse(String(payload)); } catch { seen.problems.push(`sent a frame that is not JSON: ${String(payload).slice(0, 80)}`); return; }
      if (f === null || typeof f !== 'object' || Array.isArray(f) || typeof f.type !== 'string')
        seen.problems.push(`sent a frame with no type: ${String(payload).slice(0, 80)}`);
      seen.sent.push(f);
    });
    ws.on('framereceived', ({ payload }) => {
      let f;
      try { f = JSON.parse(String(payload)); } catch { return; }
      if (f?.type === 'error') seen.problems.push(`the runner answered an error: ${f.text}`);
      seen.heard.push(f);
    });
  });
  return seen;
}

// One person's visit on a screen: the list, the chat, its messages.
async function visit(browser, hubUrl, screen) {
  const ctx = await browser.newContext({ extraHTTPHeaders: { 'X-Auth-Request-Email': email }, ...screen.options });
  const page = await ctx.newPage();
  const seen = watch(page);
  const step = async (what, fn) => {
    try { await fn(); } catch (e) { throw new Error(`${screen.name}: ${what}: ${e.message.split('\n')[0]}`); }
    if (seen.problems.length) throw new Error(`${screen.name}: ${what}: ${seen.problems.join('; ')}`);
  };
  try {
    await step('the page loads', () => page.goto(hubUrl, { waitUntil: 'load' }));
    await step('the socket says hello first', () => until('a hello frame', () => seen.sent.length > 0)
      .then(() => { if (seen.sent[0].type !== 'hello') throw new Error(`the first frame was ${JSON.stringify(seen.sent[0])}`); }));
    await step('the chat list comes', () => until('the chats frame with the seeded chat',
      () => seen.heard.some((f) => f.type === 'chats' && f.chats.some((c) => c.id === chatId))));
    await step('the chat is in the list', () => page.getByText(title, { exact: true }).first().waitFor({ timeout: waitMs }));
    await step('Settings opens as a popup and Escape closes it (README decision 58)', async () => {
      await page.locator('button.mn-face').first().click({ timeout: waitMs });
      await page.getByRole('menuitem', { name: /More settings/ }).click({ timeout: waitMs });
      const pop = page.locator('.st-pop');
      await pop.getByText('Claude subscriptions').waitFor({ timeout: waitMs });
      await pop.evaluate((e) => Promise.all(e.getAnimations().map((a) => a.finished)));
      const box = await pop.boundingBox();
      const view = page.viewportSize();
      const sheet = Math.abs(box.y + box.height - view.height) < 2 && Math.abs(box.width - view.width) < 2;
      if (sheet !== (screen.name === 'phone')) throw new Error(`placed at ${JSON.stringify(box)} in ${JSON.stringify(view)}`);
      await page.keyboard.press('Escape');
      await page.locator('.st-backdrop').waitFor({ state: 'detached', timeout: waitMs });
    });
    if (screen.name === 'desktop') await step('the header has three rows: the title; one look for every pill and the model · effort popup, no project pill and no select; the colour button first among the round buttons (README decision 22)', async () => {
      const head = page.locator('.chat-head');
      await head.locator('.head-row.r2 .head-pill').first().waitFor({ timeout: waitMs });
      if (await head.locator('select, .proj-btn, .r1 .icon').count()) throw new Error('a select, the project pill or a row-1 button is still there');
      const acts = await head.locator('.head-acts > *').evaluateAll((els) => els.map((e) => e.className));
      if (!/head-act/.test(acts[0] ?? '')) throw new Error(`the first button is ${acts[0]}`);
      await head.locator('.head-pill[aria-label="Model · effort"]').click({ timeout: waitMs });
      await head.locator('.head-pop.model-pop').getByText('Model', { exact: true }).waitFor({ timeout: waitMs });
      await page.keyboard.press('Escape');
      await head.locator('.head-pop').waitFor({ state: 'detached', timeout: waitMs });
    });
    if (screen.name === 'desktop') await step('a new chat starts with no folder: Send opens Folders and keeps the draft, a bookmark\'s Select picks the folder and sends (README decisions 61 to 65)', async () => {
      const row = (n) => page.locator('.fd-node').filter({ has: page.locator('.fd-name', { hasText: new RegExp(`^${n}$`) }) });
      const pill = page.locator('.chat-head .folder-pill');
      await pill.locator('.pill-empty', { hasText: 'Pick a folder' }).waitFor({ timeout: waitMs });
      const box = page.getByPlaceholder('Message Claude…');
      await box.fill('Start here please');
      await page.getByRole('button', { name: 'Send' }).click({ timeout: waitMs });
      await page.locator('.fd-detail .fd-title', { hasText: 'work' }).waitFor({ timeout: waitMs });
      if (seen.sent.some((f) => f.type === 'new')) throw new Error('a new chat went out without a folder');
      await row('claude').click({ timeout: waitMs });
      await row('claude').locator('.fd-pin').click({ timeout: waitMs });
      await page.locator('.fd-card', { hasText: 'claude' }).getByRole('button', { name: 'Select' }).click({ timeout: waitMs });
      await page.locator('.fd-screen').waitFor({ state: 'detached', timeout: waitMs });
      await until('the new chat goes with the picked folder', () => seen.sent.some((f) => f.type === 'new' && /\/claude$/.test(f.folder ?? '') && f.text === 'Start here please'));
      // The smoke stack has no Claude token: the runner's answer to that `new` is this error, expected here.
      const noToken = (p) => /No Claude subscription for this chat yet/.test(p);
      await until('the runner answers the new chat', () => seen.problems.some(noToken));
      seen.problems = seen.problems.filter((p) => !noToken(p));
    });
    await step('the chat opens', async () => {
      await page.getByText(title, { exact: true }).first().click({ timeout: waitMs });
      await until('the chat frame', () => seen.heard.some((f) => f.type === 'chat' && f.chat === chatId));
    });
    await step('its messages show', async () => {
      await page.getByText(question).first().waitFor({ timeout: waitMs });
      await page.getByText(reply).first().waitFor({ timeout: waitMs });
    });
    await step('the page tells the hub it works (README decision 35)', () => until('POST /hub/health {ok: true}',
      () => seen.health.length > 0).then(() => {
        if (seen.health.length !== 1 || seen.health[0].ok !== true) throw new Error(`it said ${JSON.stringify(seen.health)}`);
      }));
    say(`${screen.name}: list, chat and messages fine (${seen.sent.length} frames sent, ${seen.heard.length} heard)`);
  } finally {
    await ctx.close();
  }
}

const screens = [
  { name: 'desktop', options: { viewport: { width: 1400, height: 900 } } },
  { name: 'phone', options: { viewport: { width: 390, height: 844 }, deviceScaleFactor: 3, isMobile: true, hasTouch: true } },
];

let failed = false;
let stack = null;

try {
  stack = await startStack();
  const browser = engine === 'webkit'
    ? await webkit.launch()
    : await chromium.launch(process.env.SMOKE_CHROMIUM ? { executablePath: process.env.SMOKE_CHROMIUM } : {});
  say(`browser: ${engine} ${browser.version()}`);
  try {
    for (const s of screens) await visit(browser, stack.hubUrl, s);
  } finally {
    await browser.close();
  }
  say('passed');
} catch (e) {
  failed = true;
  console.error(`smoke: FAILED: ${e.message}`);
  const out = stack?.out ?? e.out ?? [];
  if (out.length) console.error('smoke: what the programs said:\n' + out.slice(-60).join('\n'));
} finally {
  stack?.stop();
}
process.exit(failed ? 1 : 0);
