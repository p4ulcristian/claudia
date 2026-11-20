// The test stack the smoke tests share (bin/smoke.mjs, bin/ios-smoke.mjs): the built programs
// (out/hub.js, out/front.js, out/runner.js, out/web) on loopback with throwaway data and one seeded
// chat. No session host and no Claude token: the page reads and opens chats, never starts Claude.
// With listen: a stand-in listen service that hears every clip as `heardText`, so the mic shows.
// With a proxy: an address that adds the sign-in header the gate would (for a browser that cannot
// set headers, like Safari on an iPhone) and keeps what the page reports (/hub/health,
// /hub/listen/log).

import { spawn } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, existsSync, realpathSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { createServer, connect } from 'node:net';
import http from 'node:http';
import { createHmac } from 'node:crypto';
import { DatabaseSync } from 'node:sqlite';

export const repo = resolve(import.meta.dirname, '..');
export const email = 'smoke@example.invalid';
export const server = 'smoke';
export const chatId = '5a0e5a0e-0000-4000-8000-000000000001';
export const title = 'Smoke chat';
export const question = 'Is the smoke test reading this chat?';
export const reply = 'Yes: the smoke test reads this reply.';
export const heardText = 'the stand-in listen service heard this';
export const waitMs = 15000;

export function freePort() {
  return new Promise((ok, no) => {
    const s = createServer().listen(0, '127.0.0.1', () => {
      const { port } = s.address();
      s.close(() => ok(port));
    }).on('error', no);
  });
}

export async function until(what, ok, ms = waitMs) {
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

// The chat as Claude Code keeps it: one question and its reply, in the session file the runner reads,
// and its title as a rename leaves it (a custom-title line). The title must be the session's own: at
// its start the runner sets every chat's title from its session (claudia.slave.chats/refresh-titles!,
// an async read of the session list), and seedRow's row goes in while that read runs. A session
// without a custom title is titled by its first prompt, so the chat was "Smoke chat" or the question
// by which came first (under load the question: 15 failed builder runs on 2026-11-20).
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
    { type: 'custom-title', sessionId: chatId, customTitle: title },
  ];
  writeFileSync(join(dir, `${chatId}.jsonl`), lines.map((l) => JSON.stringify(l)).join('\n') + '\n');
}

// The chat's row, as the runner's store adds one (claudia.slave.store/add!), once its schema exists;
// and `more` rows besides it (titles "Chat 1"…, started a minute apart, no session: list rows only).
function seedRow(runnerData, cwd, more = 0) {
  const db = new DatabaseSync(join(runnerData, 'claudia.db'));
  const at = Date.now() - 60000;
  const add = db.prepare('INSERT OR IGNORE INTO chats (id, title, folder, color, created, at, seen) VALUES (?, ?, ?, ?, ?, ?, ?)');
  add.run(chatId, title, cwd, null, at, at, at);
  for (let i = 1; i <= more; i++) {
    const t = at - i * 60000;
    add.run(`b0000000-0000-4000-8000-${String(i).padStart(12, '0')}`, `Chat ${i}`, cwd, null, t, t, t);
  }
  db.close();
}

// A stand-in for the listen service (OpenAI's transcription shape): every clip is heard as heardText,
// after the first `fails` clips are answered 500 (to try what a failure does).
function startListen(port, fails = 0) {
  let left = fails;
  const s = http.createServer((req, res) => {
    req.resume();
    req.on('end', () => {
      if (left > 0) { left--; res.writeHead(500); res.end('{"error":"stand-in failure"}'); return; }
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ text: heardText }));
    });
  });
  return new Promise((ok) => s.listen(port, '127.0.0.1', () => ok(s)));
}

function program(out, state, name, file, env) {
  const p = spawn(process.execPath, [join(repo, 'out', file)], {
    env: { PATH: process.env.PATH, NODE_ENV: 'production', ...env },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  const keep = (d) => out.push(`[${name}] ${d}`.trimEnd());
  p.stdout.on('data', keep);
  p.stderr.on('data', keep);
  p.on('exit', (code, sig) => { if (!state.stopping) out.push(`[${name}] exited ${code ?? sig}`); });
  return p;
}

// The programs' settings: runner, front, hub (and the hub's listen service when given).
function settings(tmp, ports, runnerSock, listenUrl) {
  return {
    runner: { HOME: tmp, RUNNER_SOCKET: runnerSock, HOST_SOCKET: join(tmp, 'host.sock'), RUNNER_PROFILE: 'smoke',
              RUNNER_DATA: join(tmp, 'runner'), CLAUDIA_CWD: join(tmp, 'work'), CLAUDE_CONFIG_DIR: join(tmp, 'claude'),
              CLAUDIA_TOKEN_FILE: join(tmp, 'token') },
    front: { HOME: tmp, FRONT_HOST: '127.0.0.1', FRONT_PORT: String(ports.front), FRONT_NAME: server,
             FRONT_PROFILES: join(tmp, 'profiles'), FRONT_HUB_KEY_FILE: join(tmp, 'front-hub-key'),
             CLAUDIA_TOKEN_FILE: join(tmp, 'token') },
    hub: { HOME: tmp, HUB_HOST: '127.0.0.1', HUB_PORT: String(ports.hub), HUB_CONFIG: join(tmp, 'hub.json'),
           HUB_DATA: join(tmp, 'hub'), HUB_WEB: join(repo, 'out', 'web'), HUB_COMMIT: 'c'.repeat(40),
           HUB_UPDATE_REQUEST: join(tmp, 'update-requested'), HUB_KEY_FILE: join(tmp, 'hub-secret'),
           ...(listenUrl ? { HUB_LISTEN_URL: listenUrl, HUB_LISTEN_TOKEN: 'smoke' } : {}) },
  };
}

// The hub's secret and the front's key from it, as claudia.node.crypto/server-key makes it (README decision 47).
const hubSecret = 'smoke-hub-secret-0123456789012345678901234567';
const frontKey = (name) => createHmac('sha256', hubSecret).update(`claudia-front:${name}`).digest('base64url');

function writeConfig(tmp, frontPort, runnerSock) {
  writeFileSync(join(tmp, 'hub-secret'), `${hubSecret}\n`);
  writeFileSync(join(tmp, 'front-hub-key'), `${frontKey(server)}\n`);
  writeFileSync(join(tmp, 'profiles'), `smoke ${email} ${runnerSock}\n`);
  writeFileSync(join(tmp, 'hub.json'), JSON.stringify({
    servers: [{ name: server, label: 'Smoke', address: `127.0.0.1:${frontPort}` }],
    people: [{ email, name: 'Smoke', admin: true, servers: [server] }],
  }));
}

// startStack({listen, listenFails, moreChats, hubEnv}): the programs up and answering (hubEnv: more of
// the hub's settings). -> {hubUrl, hubPort, tmp, out, stop()}
export async function startStack({ listen = false, listenFails = 0, moreChats = 0, hubEnv = {} } = {}) {
  for (const f of ['hub.js', 'front.js', 'runner.js', 'web/index.html'])
    if (!existsSync(join(repo, 'out', f))) throw new Error(`out/${f} is missing: build first`);
  // The real path: macOS's temp folder (/var/folders/…) is behind a symlink (/private/var/…), and the
  // seeded chat's session file is found by the folder's real path.
  const tmp = realpathSync(mkdtempSync(join(tmpdir(), 'claudia-smoke-')));
  const out = [], state = { stopping: false }, procs = [], servers = [];
  const stop = () => {
    state.stopping = true;
    for (const p of procs) p.kill('SIGKILL');
    for (const s of servers) s.close();
    rmSync(tmp, { recursive: true, force: true });
  };
  try {
    for (const d of ['work', 'claude', 'runner', 'hub']) mkdirSync(join(tmp, d), { recursive: true });
    seedSession(join(tmp, 'claude'), join(tmp, 'work'));
    const runnerSock = join(tmp, 'runner.sock');
    const ports = { front: await freePort(), hub: await freePort(), listen: await freePort() };
    writeConfig(tmp, ports.front, runnerSock);
    if (listen) servers.push(await startListen(ports.listen, listenFails));
    const env = settings(tmp, ports, runnerSock, listen && `http://127.0.0.1:${ports.listen}`);
    procs.push(program(out, state, 'runner', 'runner.js', env.runner));
    await until('the runner\'s store', () => existsSync(join(tmp, 'runner', 'claudia.db')) && existsSync(runnerSock));
    seedRow(join(tmp, 'runner'), join(tmp, 'work'), moreChats);
    procs.push(program(out, state, 'front', 'front.js', env.front));
    procs.push(program(out, state, 'hub', 'hub.js', { ...env.hub, ...hubEnv }));
    const hubUrl = `http://127.0.0.1:${ports.hub}/`;
    await until('the hub', () => answers(hubUrl));
    await until('the front, through the hub', () => answers(`${hubUrl}s/${server}/api/me`));
    return { hubUrl, hubPort: ports.hub, tmp, out, stop };
  } catch (e) {
    stop();
    e.out = out;
    throw e;
  }
}

// The proxy's handling of one call: the sign-in header added; the page's reports kept.
function proxied(hubPort, reports) {
  return (req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      const body = Buffer.concat(chunks);
      if (req.method === 'POST' && ['/hub/health', '/hub/listen/log'].includes(req.url))
        try { reports.push({ path: req.url, ...JSON.parse(body.toString() || '{}') }); } catch { /* not JSON */ }
      const up = http.request({ host: '127.0.0.1', port: hubPort, method: req.method, path: req.url,
                                headers: { ...req.headers, 'x-auth-request-email': email } },
                              (r) => { res.writeHead(r.statusCode, r.headers); r.pipe(res); });
      up.on('error', () => { res.writeHead(502); res.end(); });
      up.end(body);
    });
  };
}

// WebSockets through the proxy: the upgrade request written to the hub with the header added.
function proxiedUpgrade(hubPort) {
  return (req, sock, head) => {
    const up = connect(hubPort, '127.0.0.1', () => {
      const lines = [`${req.method} ${req.url} HTTP/1.1`];
      for (let i = 0; i < req.rawHeaders.length; i += 2)
        if (req.rawHeaders[i].toLowerCase() !== 'x-auth-request-email') lines.push(`${req.rawHeaders[i]}: ${req.rawHeaders[i + 1]}`);
      lines.push(`X-Auth-Request-Email: ${email}`);
      up.write(lines.join('\r\n') + '\r\n\r\n');
      if (head.length) up.write(head);
      up.pipe(sock);
      sock.pipe(up);
    });
    up.on('error', () => sock.destroy());
    sock.on('error', () => up.destroy());
  };
}

// startProxy(hubPort, port?): -> {url, reports, close()}; reports holds the page's /hub/health and
// /hub/listen/log bodies, each with its path. A fixed port is for a build that has the address
// baked in (the iPhone app in the Simulator).
export async function startProxy(hubPort, fixedPort) {
  const reports = [];
  const port = fixedPort ?? await freePort();
  const s = http.createServer(proxied(hubPort, reports));
  s.on('upgrade', proxiedUpgrade(hubPort));
  await new Promise((ok) => s.listen(port, '127.0.0.1', ok));
  return { url: `http://127.0.0.1:${port}/`, reports, close: () => s.close() };
}
