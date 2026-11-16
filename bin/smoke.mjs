#!/usr/bin/env node
// The smoke test (README decision 35): the built programs (out/hub.js, out/front.js, out/runner.js,
// out/web) run together on loopback with throwaway data and one seeded chat, and a headless browser
// uses the page as a person would, on a desktop and a phone screen: the chat list comes, the chat
// opens and shows its messages, the runner answers no frame with an error and the page throws
// nothing. Unit tests check each side of a wire; this checks that the page and the servers still
// understand each other (#171 shipped a page whose every first frame the runner refused).
//
//   node bin/smoke.mjs            after `npx shadow-cljs release host runner front hub web` and
//                                 `npm run build:web-files`
//
// The browser: SMOKE_BROWSER=webkit runs Playwright's WebKit (`npx playwright-core install webkit`;
// on a Mac it is Apple's WebKit, the engine of Safari and the iPhone app, README decision 36), else
// Chromium: SMOKE_CHROMIUM (a Chromium or Chrome binary), else the one playwright-core installed
// (`npx playwright-core install chromium-headless-shell`). No session host and no Claude token: the
// smoke test reads and opens chats, it never starts Claude.

import { spawn } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, existsSync, realpathSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { createServer } from 'node:net';
import { createRequire } from 'node:module';
import { DatabaseSync } from 'node:sqlite';

const repo = resolve(import.meta.dirname, '..');
const require = createRequire(join(repo, 'package.json'));
const { chromium, webkit } = require('playwright-core');
const engine = process.env.SMOKE_BROWSER === 'webkit' ? 'webkit' : 'chromium';

const email = 'smoke@example.invalid';
const server = 'smoke';
const chatId = '5a0e5a0e-0000-4000-8000-000000000001';
const title = 'Smoke chat';
const question = 'Is the smoke test reading this chat?';
const reply = 'Yes: the smoke test reads this reply.';
const waitMs = 15000;

const out = [];
const say = (...a) => console.log('smoke:', ...a);

function freePort() {
  return new Promise((ok, no) => {
    const s = createServer().listen(0, '127.0.0.1', () => {
      const { port } = s.address();
      s.close(() => ok(port));
    }).on('error', no);
  });
}

// start(name, file, env): one built program, its output kept for a failure's report.
function start(name, file, env) {
  const p = spawn(process.execPath, [join(repo, 'out', file)], {
    env: { PATH: process.env.PATH, NODE_ENV: 'production', ...env },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  const keep = (d) => out.push(`[${name}] ${d}`.trimEnd());
  p.stdout.on('data', keep);
  p.stderr.on('data', keep);
  p.on('exit', (code, sig) => { if (!stopping) out.push(`[${name}] exited ${code ?? sig}`); });
  return p;
}

async function until(what, ok, ms = waitMs) {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) {
    if (await ok()) return;
    await new Promise((r) => setTimeout(r, 100));
  }
  throw new Error(`${what}: not within ${ms / 1000} s`);
}

async function answers(url) {
  try { return (await fetch(url, { headers: { 'X-Auth-Request-Email': email } })).status < 500; } catch { return false; }
}

// The chat as Claude Code keeps it: one question and its reply, in the session file the runner reads.
function seedSession(claudeDir, cwd) {
  const dir = join(claudeDir, 'projects', cwd.replace(/[^a-zA-Z0-9]/g, '-'));
  mkdirSync(dir, { recursive: true });
  const at = new Date(Date.now() - 60000).toISOString();
  const base = { isSidechain: false, userType: 'external', cwd, sessionId: chatId, version: '2.0.0', timestamp: at };
  const lines = [
    { ...base, parentUuid: null, type: 'user', uuid: 'a0000000-0000-4000-8000-000000000001',
      message: { role: 'user', content: question } },
    { ...base, parentUuid: 'a0000000-0000-4000-8000-000000000001', type: 'assistant',
      uuid: 'a0000000-0000-4000-8000-000000000002',
      message: { id: 'msg_smoke_1', type: 'message', role: 'assistant', model: 'claude-smoke',
                 content: [{ type: 'text', text: reply }], stop_reason: 'end_turn',
                 usage: { input_tokens: 1, output_tokens: 1 } } },
  ];
  writeFileSync(join(dir, `${chatId}.jsonl`), lines.map((l) => JSON.stringify(l)).join('\n') + '\n');
}

// The chat's row, as the runner's store adds one (claudia.slave.store/add!), once its schema exists.
function seedRow(runnerData, cwd) {
  const db = new DatabaseSync(join(runnerData, 'claudia.db'));
  const at = Date.now() - 60000;
  db.prepare('INSERT OR IGNORE INTO chats (id, title, folder, color, created, at, seen) VALUES (?, ?, ?, ?, ?, ?, ?)')
    .run(chatId, title, cwd, null, at, at, at);
  db.close();
}

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

let stopping = false;
const procs = [];
// The real path: macOS's temp folder (/var/folders/…) is behind a symlink (/private/var/…), and the
// seeded chat's session file is found by the folder's real path.
const tmp = realpathSync(mkdtempSync(join(tmpdir(), 'claudia-smoke-')));
let failed = false;

try {
  for (const f of ['hub.js', 'front.js', 'runner.js', 'web/index.html'])
    if (!existsSync(join(repo, 'out', f))) throw new Error(`out/${f} is missing: build first`);
  const cwd = join(tmp, 'work');
  const claudeDir = join(tmp, 'claude');
  const runnerData = join(tmp, 'runner');
  for (const d of [cwd, claudeDir, runnerData, join(tmp, 'hub')]) mkdirSync(d, { recursive: true });
  seedSession(claudeDir, cwd);
  const runnerSock = join(tmp, 'runner.sock');
  const [frontPort, hubPort] = [await freePort(), await freePort()];
  writeFileSync(join(tmp, 'profiles'), `smoke ${email} ${runnerSock}\n`);
  writeFileSync(join(tmp, 'hub.json'), JSON.stringify({
    servers: [{ name: server, label: 'Smoke', address: `127.0.0.1:${frontPort}` }],
    people: [{ email, name: 'Smoke', admin: true, servers: [server] }],
  }));

  procs.push(start('runner', 'runner.js', {
    HOME: tmp, RUNNER_SOCKET: runnerSock, HOST_SOCKET: join(tmp, 'host.sock'), RUNNER_PROFILE: 'smoke',
    RUNNER_DATA: runnerData, CLAUDIA_CWD: cwd, CLAUDE_CONFIG_DIR: claudeDir,
    CLAUDIA_TOKEN_FILE: join(tmp, 'token'),
  }));
  await until('the runner\'s store', () => existsSync(join(runnerData, 'claudia.db')) && existsSync(runnerSock));
  seedRow(runnerData, cwd);
  procs.push(start('front', 'front.js', {
    HOME: tmp, FRONT_HOST: '127.0.0.1', FRONT_PORT: String(frontPort), FRONT_NAME: server,
    FRONT_PROFILES: join(tmp, 'profiles'),
  }));
  procs.push(start('hub', 'hub.js', {
    HOME: tmp, HUB_HOST: '127.0.0.1', HUB_PORT: String(hubPort), HUB_CONFIG: join(tmp, 'hub.json'),
    HUB_DATA: join(tmp, 'hub'), HUB_WEB: join(repo, 'out', 'web'), HUB_COMMIT: 'c'.repeat(40),
    HUB_UPDATE_REQUEST: join(tmp, 'update-requested'),
  }));
  const hubUrl = `http://127.0.0.1:${hubPort}/`;
  await until('the hub', () => answers(hubUrl));
  await until('the front, through the hub', () => answers(`${hubUrl}s/${server}/api/me`));

  const browser = engine === 'webkit'
    ? await webkit.launch()
    : await chromium.launch(process.env.SMOKE_CHROMIUM ? { executablePath: process.env.SMOKE_CHROMIUM } : {});
  say(`browser: ${engine} ${browser.version()}`);
  try {
    for (const s of screens) await visit(browser, hubUrl, s);
  } finally {
    await browser.close();
  }
  say('passed');
} catch (e) {
  failed = true;
  console.error(`smoke: FAILED: ${e.message}`);
  if (out.length) console.error('smoke: what the programs said:\n' + out.slice(-60).join('\n'));
} finally {
  stopping = true;
  for (const p of procs) p.kill('SIGKILL');
  rmSync(tmp, { recursive: true, force: true });
}
process.exit(failed ? 1 : 0);
