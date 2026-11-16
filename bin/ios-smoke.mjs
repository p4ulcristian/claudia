#!/usr/bin/env node
// The smoke test in iOS (README decision 36), on a Mac with Xcode, an iOS Simulator runtime and
// Appium with its XCUITest driver (`npm i appium && npx appium driver install xcuitest`, then
// `npx appium` on port 4723): the test stack (bin/stack.mjs) with a stand-in listen service, and
// Safari in the Simulator, driven by plain WebDriver calls. The chat list comes, the chat opens
// and shows its messages, the page tells the hub it works, and the mic is pressed and held by a
// real touch (a native tap, not a script's click): it must start recording and be heard.
//
//   node bin/ios-smoke.mjs <simulator udid>...   (`xcrun simctl list devices available`)
//
// APPIUM (default http://127.0.0.1:4723) is Appium's address. The Simulator's microphone is the
// Mac's; nothing is said, the clip is silence, and the stand-in hears it as text anyway.

import { writeFileSync } from 'node:fs';
import { title, question, reply, startStack, startProxy, until } from './stack.mjs';

const appium = process.env.APPIUM || 'http://127.0.0.1:4723';
const say = (...a) => console.log('ios-smoke:', ...a);
const sleep = (ms) => new Promise((ok) => setTimeout(ok, ms));

async function call(method, path, body) {
  const r = await fetch(appium + path, {
    method, headers: { 'content-type': 'application/json' }, body: body && JSON.stringify(body),
    signal: AbortSignal.timeout(90000),
  });
  const j = await r.json();
  if (j.value?.error) throw new Error(`${path}: ${j.value.error}: ${String(j.value.message).split('\n')[0]}`);
  return j.value;
}

// A WebDriver session on Safari in the Simulator udid: {id, s(path) -> its path}.
async function session(udid) {
  const v = await call('POST', '/session', { capabilities: { alwaysMatch: {
    platformName: 'iOS', browserName: 'Safari', 'appium:automationName': 'XCUITest', 'appium:udid': udid,
    'appium:autoAcceptAlerts': true, 'appium:newCommandTimeout': 300, 'appium:wdaLaunchTimeout': 240000,
  } } });
  const id = v.sessionId;
  return { id, s: (p) => `/session/${id}${p}` };
}

const run = (d, script) => call('POST', d.s('/execute/sync'), { script, args: [] });
const context = (d, name) => call('POST', d.s('/context'), { name });

// Back to Safari's page (its WEBVIEW context, which can take a moment to be listed again).
async function webContext(d) {
  let web = null;
  await until('Safari\'s page context', async () => {
    const all = await call('GET', d.s('/contexts'));
    web = all.map((c) => (typeof c === 'string' ? c : c.id)).find((c) => String(c).startsWith('WEBVIEW'));
    return Boolean(web);
  });
  await context(d, web);
}

// The first load in a fresh Simulator can be slow (5 MB of script): 40 s.
const shows = (d, text) => until(`"${text}" on screen`,
  async () => (await run(d, 'return document.body ? document.body.innerText : ""')).includes(text), 40000);

// The chat list, the chat, its messages, the page's word to the hub.
async function readChat(d, url, reports) {
  await call('POST', d.s('/url'), { url });
  await shows(d, title);
  const el = await call('POST', d.s('/element'), { using: 'xpath', value: `//*[text()="${title}"]` });
  await call('POST', d.s(`/element/${Object.values(el)[0]}/click`), {});
  await shows(d, question);
  await shows(d, reply);
  await until('POST /hub/health {ok: true}', () => reports.some((r) => r.path === '/hub/health' && r.ok === true), 40000);
}

// A system alert's buttons ([] when none shows): Appium's own look, quick (a search of the screen
// for a button that is not there walks the whole page and takes minutes).
async function alertButtons(d) {
  try { return await call('POST', d.s('/execute/sync'), { script: 'mobile: alert', args: [{ action: 'getButtons' }] }) ?? []; } catch { return []; }
}

// Where the mic is on the screen, in points: its middle on the page, moved by where Safari's web
// view sits (a search of the native screen for the button walks Claudia's whole page: minutes).
async function micPoint(d) {
  const r = await run(d, `const b = document.querySelector('.mic-button'); if (!b) return null;
    const r = b.getBoundingClientRect(); return { x: r.x + r.width / 2, y: r.y + r.height / 2 };`);
  if (!r) throw new Error('no mic on the page');
  await context(d, 'NATIVE_APP');
  // Where the page sits on the screen (screenshot pixels) and how many pixels a point is.
  const view = await call('POST', d.s('/execute/sync'), { script: 'mobile: viewportRect', args: [] });
  const { scale } = await call('POST', d.s('/execute/sync'), { script: 'mobile: deviceScreenInfo', args: [] });
  return { x: Math.round(view.left / scale + r.x), y: Math.round(view.top / scale + r.y) };
}

// A press of holdS seconds on the mic by a native touch; a microphone question is allowed.
async function holdMic(d, holdS) {
  const p = await micPoint(d);
  await call('POST', d.s('/execute/sync'), { script: 'mobile: touchAndHold', args: [{ x: p.x, y: p.y, duration: holdS }] });
  const buttons = await alertButtons(d);
  const allow = buttons.find((b) => /^(Allow|OK)$/.test(b));
  if (allow) await call('POST', d.s('/execute/sync'), { script: 'mobile: alert', args: [{ action: 'accept', buttonLabel: allow }] });
  await webContext(d);
}

// The mic: pressed until a press has started and been heard (the first may stop at Safari's question).
async function useMic(d, reports) {
  const events = () => reports.filter((r) => r.path === '/hub/listen/log').map((r) => r.event);
  for (let i = 0; i < 3 && !events().includes('heard'); i++) {
    await holdMic(d, 2.5);
    await sleep(3000);
  }
  const ev = events();
  if (!ev.includes('started') || !ev.includes('heard')) throw new Error(`the mic said ${JSON.stringify(reports.filter((r) => r.path === '/hub/listen/log'))}`);
  const started = reports.filter((r) => r.event === 'started').map((r) => `${r.ms} ms${r.warm ? ' warm' : ' cold'}`);
  return `mic started (${started.join(', ')}) and was heard`;
}

async function onDevice(udid, url, reports) {
  const d = await session(udid);
  try {
    await readChat(d, url, reports);
    const ua = await run(d, 'return navigator.userAgent');
    const mic = await useMic(d, reports);
    say(`${udid}: list, chat, messages and health fine; ${mic} (${ua.match(/OS [\d_]+|Version\/[\d.]+/g)?.join(' ')})`);
  } catch (e) {
    await evidence(d, udid).catch(() => {});
    throw e;
  } finally {
    await call('DELETE', d.s('')).catch(() => {});
  }
}

// On a failure: the screen as a picture (/tmp/ios-smoke-<udid>.png) and the page's text.
async function evidence(d, udid) {
  const png = await call('GET', d.s('/screenshot'));
  writeFileSync(`/tmp/ios-smoke-${udid}.png`, Buffer.from(png, 'base64'));
  await webContext(d).catch(() => {});
  const text = await run(d, 'return (document.body ? document.body.innerText : "(no body)").slice(0, 600)');
  say(`${udid}: screen in /tmp/ios-smoke-${udid}.png; the page said: ${JSON.stringify(text)}`);
}

const udids = process.argv.slice(2);
if (!udids.length) { console.error('usage: node bin/ios-smoke.mjs <simulator udid>...'); process.exit(2); }
let failed = false, stack = null, proxy = null;
try {
  stack = await startStack({ listen: true });
  proxy = await startProxy(stack.hubPort);
  for (const udid of udids) {
    proxy.reports.length = 0;
    await onDevice(udid, proxy.url, proxy.reports);
  }
  say('passed');
} catch (e) {
  failed = true;
  console.error(`ios-smoke: FAILED: ${e.message}`);
  const out = stack?.out ?? e.out ?? [];
  if (out.length) console.error('ios-smoke: what the programs said:\n' + out.slice(-40).join('\n'));
} finally {
  proxy?.close();
  stack?.stop();
}
process.exit(failed ? 1 : 0);
