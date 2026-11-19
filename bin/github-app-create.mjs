#!/usr/bin/env node
// Creates the hub's GitHub App the way GitHub offers for it (a manifest: the app's whole setup in
// one form post), on the admin's own machine, once. A page on 127.0.0.1 holds a Create button; it
// posts the manifest to GitHub, where the admin, signed in, checks the name and presses Create;
// GitHub sends the browser back here with a one-time code, which this trades for the app's id,
// client id, client secret and private key, written straight into the gate's sops file (never
// printed, never in a plain file). The hub reads them from there like its other secrets.
//
//   node bin/github-app-create.mjs --hub https://<hub address> --sops <gate.sops.yaml> [--org <org>] [--port 4597]
//
// The app: public (anyone can install it; only the hub's people can use it), user tokens that
// expire (GitHub's default for new apps), no webhook. Permissions: contents, pull requests, issues
// and workflows read/write; actions and checks read; metadata read.

import http from 'node:http';
import { randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';

const arg = (name, dflt) => {
  const i = process.argv.indexOf(`--${name}`);
  return i > 0 ? process.argv[i + 1] : dflt;
};
const hub = arg('hub')?.replace(/\/+$/, '');
const sopsFile = arg('sops');
const org = arg('org');
const port = Number(arg('port', '4597'));
if (!hub || !/^https:\/\//.test(hub) || !sopsFile) {
  console.error('usage: node bin/github-app-create.mjs --hub https://<hub> --sops <gate.sops.yaml> [--org <org>]');
  process.exit(2);
}
const state = randomBytes(16).toString('hex');
const here = `http://127.0.0.1:${port}`;

const manifest = (name) => ({
  name,
  url: hub,
  description: "Claudia's hub: each person's GitHub, with short-lived tokens, on their Claudia servers.",
  redirect_url: `${here}/created`,
  callback_urls: [`${hub}/hub/github/callback`],
  setup_url: `${hub}/hub/github/installed`,
  setup_on_update: true,
  public: true,
  request_oauth_on_install: false,
  hook_attributes: { url: `${hub}/hub/github/app-hook`, active: false },
  default_permissions: {
    contents: 'write', pull_requests: 'write', issues: 'write', workflows: 'write',
    actions: 'write', checks: 'read', statuses: 'read', metadata: 'read',
    // Services' installation tokens (README decision 78): runs cancelled and dispatched, self-hosted
    // runner registrations, secret names.
    administration: 'write', secrets: 'read',
  },
  default_events: [],
});

const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);
const target = org ? `https://github.com/organizations/${encodeURIComponent(org)}/settings/apps/new` : 'https://github.com/settings/apps/new';

const page = (body) => `<!doctype html><meta charset=utf-8><title>Claudia's GitHub App</title>
<style>body{font:16px/1.5 system-ui;max-width:560px;margin:60px auto;padding:0 20px;background:#2b2321;color:#f3e6e1}
input,button{font:inherit;padding:8px 12px;border-radius:8px;border:1px solid #8a6a62;background:#3b2f2c;color:inherit}
button{background:#e2725b;border:0;color:#fff;cursor:pointer}code{background:#3b2f2c;padding:1px 5px;border-radius:5px}</style>${body}`;

function startPage() {
  const name = 'Claudia-' + randomBytes(2).toString('hex');
  return page(`<h1>Claudia's GitHub App</h1>
<p>Owner: <b>${esc(org || 'your own GitHub account')}</b>. Hub: <code>${esc(hub)}</code>.</p>
<form method=post action="${esc(target)}?state=${state}" id=f>
<p><label>Name (unique on all of GitHub)<br><input id=n value="${esc(name)}" size=30></label></p>
<input type=hidden name=manifest id=m>
<p><button>Create on GitHub</button></p></form>
<p>GitHub shows its own page next: check the name, press <b>Create GitHub App</b>, and it sends you back here.</p>
<script>const base=${JSON.stringify(manifest('NAME'))};
document.getElementById('f').onsubmit=()=>{document.getElementById('m').value=JSON.stringify({...base,name:document.getElementById('n').value.trim()});};</script>`);
}

function keep(key, value) {
  execFileSync('sops', ['set', sopsFile, `["${key}"]`, JSON.stringify(String(value))], { stdio: ['ignore', 'ignore', 'inherit'] });
}

async function created(code) {
  const r = await fetch(`https://api.github.com/app-manifests/${encodeURIComponent(code)}/conversions`, {
    method: 'POST', headers: { accept: 'application/vnd.github+json', 'user-agent': 'claudia-github-app-create' },
  });
  if (!r.ok) throw new Error(`GitHub answered ${r.status} to the code`);
  const app = await r.json();
  keep('GATE_CLAUDIA_GITHUB_APP_ID', app.id);
  keep('GATE_CLAUDIA_GITHUB_APP_SLUG', app.slug);
  keep('GATE_CLAUDIA_GITHUB_APP_CLIENT_ID', app.client_id);
  keep('GATE_CLAUDIA_GITHUB_APP_CLIENT_SECRET', app.client_secret);
  keep('GATE_CLAUDIA_GITHUB_APP_PRIVATE_KEY', app.pem);
  return app;
}

const server = http.createServer(async (req, res) => {
  const u = new URL(req.url, here);
  const send = (status, html) => { res.writeHead(status, { 'content-type': 'text/html; charset=utf-8' }); res.end(html); };
  if (u.pathname === '/') return send(200, startPage());
  if (u.pathname !== '/created') return send(404, page('<p>Not here.</p>'));
  if (u.searchParams.get('state') !== state) return send(400, page('<p>This answer is not for this page (state). Start again.</p>'));
  try {
    const app = await created(u.searchParams.get('code') || '');
    send(200, page(`<h1>Done</h1><p><b>${esc(app.name)}</b> (id ${esc(app.id)}) exists, and its keys are in the gate's sops file.</p>
<p>Its page: <a style="color:#f0a090" href="${esc(app.html_url)}">${esc(app.html_url)}</a></p><p>You can close this window.</p>`));
    console.log(`github-app-create: ${app.slug} (id ${app.id}) created; its keys are in ${sopsFile}`);
    setTimeout(() => process.exit(0), 500);
  } catch (e) {
    send(500, page(`<p>Not created: ${esc(e.message)}</p>`));
    console.error(`github-app-create: ${e.message}`);
  }
});
server.listen(port, '127.0.0.1', () => console.log(`github-app-create: open ${here}/`));
