#!/usr/bin/env node
// The smoke test (README decision 35): the built programs (out/hub.js, out/front.js, out/runner.js,
// out/web) run together on loopback with throwaway data and one seeded chat, and a headless browser
// uses the page as a person would, on a desktop and a phone screen: the chat list comes, the chat
// opens and shows its messages, the runner answers no frame with an error and the page throws
// nothing. Unit tests check each side of a wire; this checks that the page and the servers still
// understand each other (#171 shipped a page whose every first frame the runner refused).
//
//   node bin/smoke.mjs            after `npx shadow-cljs release host runner front hub broker root sudo web sdk` and
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
    if (screen.name === 'desktop') await step('views open as sheets on the Drawing board: Settings from the menu, Search on top of it (Ctrl+K), a peeking sheet comes up, × closes, and the pile is kept on the hub (README decisions 58, 91)', async () => {
      const pile = () => page.evaluate(() => fetch('/hub/right-view').then((r) => r.json()));
      const kept = (views) => until(`PUT /hub/right-view ${views.join(' ') || 'none'}`, async () => {
        const p = await pile();
        return JSON.stringify(p.views) === JSON.stringify(views) && p.view === (views.at(-1) ?? 'picture');
      });
      const sheet = (name) => page.locator('.sheet', { has: page.locator('.sheet-title', { hasText: name }) });
      if (await page.locator('.rp-button').count()) throw new Error('the Drawing board dropdown is still there');
      await page.locator('button.mn-face').first().click({ timeout: waitMs });
      await page.getByRole('menuitem', { name: /More settings/ }).click({ timeout: waitMs });
      await sheet('Settings').locator('.settings-pane').getByText('Claude subscriptions').waitFor({ timeout: waitMs });
      await kept(['settings']);
      await page.keyboard.press('Control+k');
      await sheet('Search').locator('.sp-panel').waitFor({ timeout: waitMs });
      await page.locator('.sheet.under', { hasText: 'Settings' }).waitFor({ timeout: waitMs });
      if (await page.locator('.settings-pane').count()) throw new Error('a sheet under the top one is drawn in full');
      await kept(['settings', 'search']);
      await sheet('Settings').click({ position: { x: 60, y: 10 }, timeout: waitMs });
      await sheet('Settings').locator('.settings-pane').waitFor({ timeout: waitMs });
      await kept(['search', 'settings']);
      await page.getByRole('button', { name: 'Close Settings' }).click({ timeout: waitMs });
      await page.locator('.settings-pane').waitFor({ state: 'detached', timeout: waitMs });
      await page.getByRole('button', { name: 'Close Search' }).click({ timeout: waitMs });
      await page.locator('.sheet').first().waitFor({ state: 'detached', timeout: waitMs });
      await kept([]);
    });
    if (screen.name === 'desktop') await step('the header has three rows: the title; one look for every pill (server, project, notify and colour among them) and the model · effort popup, no select; Files first among the worded buttons (README decisions 22, 90)', async () => {
      const head = page.locator('.chat-head');
      await head.locator('.head-row.r2 .head-pill').first().waitFor({ timeout: waitMs });
      if (await head.locator('select, .proj-btn, .r1 .icon, .r3 .icon').count()) throw new Error('a select, the old project button or a round button is still there');
      for (const name of ['Server', 'Notify', 'Colour']) {
        if (!(await head.locator(`.r2 .head-pill[aria-label="${name}"]`).count())) throw new Error(`no ${name} pill in row 2`);
      }
      const acts = await head.locator('.head-acts > *').evaluateAll((els) => els.map((e) => `${e.className} ${e.textContent}`));
      if (!/head-btn/.test(acts[0] ?? '') || !/Files/.test(acts[0] ?? '')) throw new Error(`the first button is ${acts[0]}`);
      await head.locator('.head-pill[aria-label="Model · effort"]').click({ timeout: waitMs });
      await head.locator('.head-pop.model-pop').getByText('Model', { exact: true }).waitFor({ timeout: waitMs });
      await page.keyboard.press('Escape');
      await head.locator('.head-pop').waitFor({ state: 'detached', timeout: waitMs });
    });
    if (screen.name === 'desktop') await step('a new chat starts with no folder: Send opens Files in the right half and keeps the draft, a bookmark\'s Select picks the folder, sends and closes the File Browser\'s sheet (README decisions 61 to 65, 70, 90, 91)', async () => {
      const row = (n) => page.locator('.fd-node').filter({ has: page.locator('.fd-name', { hasText: new RegExp(`^${n}$`) }) });
      const pill = page.locator('.chat-head .project-pill');
      await pill.locator('.pill-empty', { hasText: 'Pick a project' }).waitFor({ timeout: waitMs });
      if (!(await page.locator('.chat-head .head-pill[aria-label="Server"] .pill-val').innerText()).trim()) throw new Error('the server pill names no server (README decision 90)');
      const box = page.getByPlaceholder('Message Claude…');
      await box.fill('Start here please');
      await page.getByRole('button', { name: 'Send' }).click({ timeout: waitMs });
      await page.locator('.sheet', { has: page.locator('.sheet-title', { hasText: 'File Browser' }) }).locator('.fd-files').waitFor({ timeout: waitMs });
      if (seen.sent.some((f) => f.type === 'new')) throw new Error('a new chat went out without a folder');
      await row('claude').click({ timeout: waitMs });
      await row('claude').locator('.fd-pin').click({ timeout: waitMs });
      await page.locator('.fd-card', { hasText: 'claude' }).getByRole('button', { name: 'Select' }).click({ timeout: waitMs });
      await page.locator('.fd-files').waitFor({ state: 'detached', timeout: waitMs });
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
    if (engine === 'chromium' && screen.name === 'desktop') {
      await step('a wheel past the end stretches the messages and they spring back (README decision 118)', async () => {
        const col = page.locator('.messages');
        await col.hover();
        const seenBand = col.evaluate((el) => new Promise((done) => {
          el.scrollTop = el.scrollHeight;
          const marks = [];
          const mo = new MutationObserver(() => {
            marks.push(el.classList.contains('stretch'));
            if (marks.includes(true) && !el.classList.contains('stretch')) { mo.disconnect(); done(marks); }
          });
          mo.observe(el, { attributes: true, attributeFilter: ['class'] });
          setTimeout(() => { mo.disconnect(); done(marks); }, 3000);
        }));
        await page.mouse.wheel(0, 300);
        const marks = await seenBand;
        if (!marks.includes(true)) throw new Error('the messages never stretched');
        if (marks[marks.length - 1]) throw new Error('the messages did not spring back');
      });
    }
    await step('the page tells the hub it works (README decision 35)', () => until('POST /hub/health {ok: true}',
      () => seen.health.length > 0).then(() => {
        if (seen.health.length !== 1 || seen.health[0].ok !== true) throw new Error(`it said ${JSON.stringify(seen.health)}`);
      }));
    await step('a lost login covers the whole app: the gate\'s redirect to sign in, seen by the login check on the page\'s return to the front', async () => {
      await page.route('**/hub/**', (r) => r.fulfill({ status: 302, headers: { location: 'https://sign-in.invalid/' } }));
      await page.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));
      await page.locator('.login-cover').getByRole('button', { name: 'Log in again' }).waitFor({ timeout: waitMs });
      const top = await page.evaluate(() => document.elementFromPoint(innerWidth / 2, 5)?.closest('.login-cover') != null);
      if (!top) throw new Error('the cover is not on top of the app');
      await page.unroute('**/hub/**');
    });
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
