// Claudia's hub, on the gate: the one address people open. It serves the page, keeps the servers
// list and the access grid (who may use which server), and passes /s/<server>/api/… and
// /s/<server>/ws on to that server over WireGuard, saying who is calling. Each server believes the
// hub as it believed the gate's login before (only the gate reaches it), and picks the caller's own
// profile there.
//
// Behind the gate's Caddy only: it believes X-Auth-Request-Email and -Groups, which Caddy clears
// and the login sets. Two ways in without a login: /hub/enroll (a joining server, with its secret) and
// /hub/github (GitHub's webhook, signed: src/hub/github.ts).
import type { BunRequest, Server } from "bun";
import { join, normalize } from "node:path";
import { forward, pipe, upgrade, type PipeData } from "./src/proxy";
import { hubStore } from "./src/hub/store";
import { NAME, applied, dropPeer, enroll, pair, parseKey, suggestName } from "./src/hub/pairing";
import { pushChanged, pushOn, startPush, syncPush } from "./src/hub/push";
import { liveEnded, liveReported } from "./src/hub/live";
import { beat } from "./src/hub/presence";
import { deviceKey } from "./src/hub/seal";
import { shortToken } from "./src/hub/apns";
import { githubHook } from "./src/hub/github";
import { COMMIT, VERSION } from "./src/version";

const HOST = process.env.HUB_HOST ?? "127.0.0.1";
const PORT = Number(process.env.HUB_PORT ?? 4530);
const ADMINS = process.env.HUB_ADMIN_GROUP ?? "admins";
// The page. On the gate: built ahead (deploy/hub.Dockerfile, `bun build … --public-path=/`) and
// served as files from HUB_WEB, so the gate's little memory never bundles it. Elsewhere: Bun's
// HTML import, bundled when the hub starts (with hot reload in development).
const WEB = process.env.HUB_WEB ?? null;
const index = WEB ? new Response(Bun.file(join(WEB, "index.html"))) : (await import("./web/index.html")).default;
const json = (v: unknown, status = 200) => Response.json(v, { status });

// A built file (hashed names: kept a year), or nothing.
async function webFile(path: string) {
  if (!WEB) return null;
  const rel = normalize(decodeURIComponent(path)).replace(/^\/+/, "");
  if (!rel || rel.startsWith("..")) return null;
  const f = Bun.file(join(WEB, rel));
  if (!(await f.exists())) return null;
  return new Response(f, { headers: rel.startsWith("app-icon/") ? {} : { "cache-control": "public, max-age=31536000, immutable" } });
}

type Caller = { email: string; admin: boolean };

function caller(req: Request): Caller | null {
  const email = req.headers.get("x-auth-request-email")?.trim().toLowerCase();
  if (!email) return null;
  const groups = (req.headers.get("x-auth-request-groups") ?? "").split(",").map((g) => g.trim());
  return { email, admin: groups.includes(ADMINS) };
}

// A change made from another site (a form or script elsewhere riding the login cookie) is refused:
// browsers say where a request comes from (Sec-Fetch-Site), and a socket's Origin must be this host.
// The iPhone app's own requests (URLSession, no page) send neither, and pass.
function crossSite(req: Request) {
  const site = req.headers.get("sec-fetch-site");
  if (site && site !== "same-origin" && site !== "none") return true;
  const origin = req.headers.get("origin");
  if (origin && new URL(origin).host !== new URL(req.url).host) return true;
  return false;
}

const body = async (req: Request) => (await req.json().catch(() => ({}))) as Record<string, unknown>;
const str = (v: unknown, max = 200) => (typeof v === "string" ? v.trim().slice(0, max) : "");
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
// host:port on WireGuard (or anywhere the gate reaches).
const ADDRESS = /^[a-z0-9.-]+:\d{1,5}$/i;

function adminState() {
  return {
    servers: hubStore.servers().map((s) => ({ ...s, pairing: hubStore.pairings().find((p) => p.server === s.name) ?? null, peerUp: applied(s.name) })),
    people: hubStore.people(),
    access: hubStore.access(),
  };
}

// After a change to the servers or the grid: phone pushes follow it at once (src/hub/push.ts).
function changed() {
  syncPush();
  return json(adminState());
}

// An APNs device token (hex; Apple says not to count on its length) and an app's bundle id.
const DEVICE_TOKEN = /^[0-9a-f]{64,200}$/;
const TOPIC = /^[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+$/;
// ActivityKit's tokens (push-to-start, an activity's updates: hex) and an activity's id.
const LIVE_TOKEN = /^[0-9a-f]{32,512}$/;
const ACTIVITY = /^[A-Za-z0-9-]{1,100}$/;
// A page's own id for its presence beats.
const PAGE = /^[A-Za-z0-9-]{8,64}$/;

function admin(req: Request): Caller | Response {
  const c = caller(req);
  if (!c) return json({ error: "not signed in" }, 401);
  if (!c.admin) return json({ error: "admins only" }, 403);
  if (req.method !== "GET" && crossSite(req)) return json({ error: "cross-site" }, 403);
  return c;
}

// /s/<server>/<rest>: to that server, as the caller, with only what the server needs.
function toServer(req: Request, server: Server<PipeData>) {
  const url = new URL(req.url);
  const m = url.pathname.match(/^\/s\/([a-z][a-z0-9-]{0,30})(\/.*)$/);
  if (!m) return json({ error: "not found" }, 404);
  const [, name, rest] = m as unknown as [string, string, string];
  const c = caller(req);
  if (!c) return json({ error: "not signed in" }, 401);
  if (!hubStore.may(c.email, name)) return json({ error: `no access to ${name}` }, 403);
  const target = hubStore.server(name);
  if (!target) return json({ error: `no server ${name}` }, 404);
  if (rest !== "/ws" && !rest.startsWith("/api/")) return json({ error: "not found" }, 404);
  if ((rest === "/ws" || req.method !== "GET") && crossSite(req)) return json({ error: "cross-site" }, 403);
  // The browser's headers stay here (its cookies hold the login); the server gets who is calling,
  // whose chats to show, and the body's type.
  const headers = new Headers({ "x-auth-request-email": c.email });
  for (const h of ["x-claudia-view", "content-type", "accept"]) {
    const v = req.headers.get(h);
    if (v) headers.set(h, v);
  }
  const t = { tcp: target.address };
  if (rest === "/ws") return upgrade(req, server, t, false, { path: `/ws${url.search}`, headers }) ? undefined : new Response("Upgrade failed", { status: 400 });
  return forward(req, t, { path: rest, headers });
}

Bun.serve<PipeData>({
  hostname: HOST,
  port: PORT,
  idleTimeout: 0,
  development: process.env.NODE_ENV !== "production" && { hmr: true, console: true },
  routes: {
    "/": index,
    "/c/*": index,
    "/new": index,
    "/app-icon/icon-192.png": new Response(Bun.file(new URL("./web/app-icon/icon-192.png", import.meta.url))),
    "/app-icon/icon-512.png": new Response(Bun.file(new URL("./web/app-icon/icon-512.png", import.meta.url))),

    // Who is here and the servers they may use. The page asks each server for the rest.
    "/hub/me": {
      GET: (req: Request) => {
        const c = caller(req);
        if (!c) return json({ error: "not signed in" }, 401);
        return json({
          email: c.email,
          admin: c.admin,
          version: VERSION,
          commit: COMMIT,
          servers: hubStore.allowed(c.email).map((s) => ({ name: s.name, label: s.label })),
        });
      },
    },

    // The caller's own phones (the iPhone app's page registers its APNs token: web/native.ts):
    // { token, env: development | production, topic: the app's bundle id, key: base64 of the 32
    // bytes alert texts are sealed with, liveStartToken?: ActivityKit's push-to-start token, which
    // may come later in another POST of the same token }. Kept also while push is off here, so the
    // phones are ready when the key is set.
    "/hub/push/devices": {
      POST: async (req: Request) => {
        const c = caller(req);
        if (!c) return json({ error: "not signed in" }, 401);
        if (crossSite(req)) return json({ error: "cross-site" }, 403);
        const b = await body(req);
        const token = str(b.token).toLowerCase();
        const env = str(b.env);
        const topic = str(b.topic, 155);
        const key = deviceKey(b.key);
        const liveStart = b.liveStartToken === undefined || b.liveStartToken === null ? null : str(b.liveStartToken, 600).toLowerCase();
        if (!DEVICE_TOKEN.test(token)) return json({ error: "token: an APNs device token in hex" }, 400);
        if (env !== "development" && env !== "production") return json({ error: "env: development or production" }, 400);
        if (!TOPIC.test(topic)) return json({ error: "topic: the app's bundle id" }, 400);
        if (!key) return json({ error: "key: base64 of 32 random bytes" }, 400);
        if (liveStart !== null && !LIVE_TOKEN.test(liveStart)) return json({ error: "liveStartToken: ActivityKit's push-to-start token in hex" }, 400);
        hubStore.saveDevice({ token, email: c.email, env, topic, key, liveStart });
        console.log(`push: phone ${shortToken(token)} (${env}${liveStart ? ", Live Activity" : ""}) registered for ${c.email}`);
        syncPush();
        pushChanged(c.email);
        return json({ ok: true, push: pushOn() });
      },
    },
    // A Live Activity's update token, from the iPhone app itself (it may be woken with no page
    // loaded): { device: its APNs device token, activity: ActivityKit's id, token: hex }. Only for
    // the caller's own phone; an activity already ended stays ended.
    "/hub/push/live": {
      POST: async (req: Request) => {
        const c = caller(req);
        if (!c) return json({ error: "not signed in" }, 401);
        if (crossSite(req)) return json({ error: "cross-site" }, 403);
        const b = await body(req);
        const device = str(b.device).toLowerCase();
        const activity = str(b.activity, 100);
        const token = str(b.token, 600).toLowerCase();
        if (!ACTIVITY.test(activity)) return json({ error: "activity: the activity's id" }, 400);
        if (!LIVE_TOKEN.test(token)) return json({ error: "token: the activity's update token in hex" }, 400);
        if (!hubStore.devices(c.email).some((d) => d.token === device)) return json({ error: "no such phone of yours" }, 404);
        hubStore.saveActivity(activity, device, token);
        const a = hubStore.activity(activity);
        if (!a || a.device !== device) return json({ error: "that activity is another phone's" }, 409);
        if (a.endedAt !== null) return json({ ok: true, ended: true });
        liveReported(c.email);
        return json({ ok: true, ended: false });
      },
    },
    // The app saw the activity end (iOS ends one after 8 hours, or the person dismissed it).
    "/hub/push/live/:activity": {
      DELETE: (req: BunRequest<"/hub/push/live/:activity">) => {
        const c = caller(req);
        if (!c) return json({ error: "not signed in" }, 401);
        if (crossSite(req)) return json({ error: "cross-site" }, 403);
        const a = hubStore.activity(decodeURIComponent(req.params.activity));
        if (!a || a.email !== c.email) return json({ error: "no such activity of yours" }, 404);
        liveEnded(c.email, a);
        return json({ ok: true });
      },
    },
    // A desktop page's word on whether its person is working at it: { page: its own id, active }
    // (web/presence.ts). While they are, their phone alerts wait (src/hub/presence.ts).
    "/hub/presence": {
      POST: async (req: Request) => {
        const c = caller(req);
        if (!c) return json({ error: "not signed in" }, 401);
        if (crossSite(req)) return json({ error: "cross-site" }, 403);
        const b = await body(req);
        const page = str(b.page, 64);
        if (!PAGE.test(page)) return json({ error: "page: the page's id" }, 400);
        beat(c.email, page, b.active === true);
        return new Response(null, { status: 204 });
      },
    },
    "/hub/push/devices/:token": {
      DELETE: (req: BunRequest<"/hub/push/devices/:token">) => {
        const c = caller(req);
        if (!c) return json({ error: "not signed in" }, 401);
        if (crossSite(req)) return json({ error: "cross-site" }, 403);
        if (!hubStore.dropDevice(req.params.token.toLowerCase(), c.email)) return json({ error: "no such phone of yours" }, 404);
        syncPush();
        return json({ ok: true });
      },
    },

    // The Servers and Access pages (admins).
    "/hub/admin": { GET: (req: Request) => { const a = admin(req); return a instanceof Response ? a : json(adminState()); } },
    "/hub/admin/servers/:name": {
      PUT: async (req: BunRequest<"/hub/admin/servers/:name">) => {
        const a = admin(req);
        if (a instanceof Response) return a;
        const name = req.params.name;
        if (!NAME.test(name)) return json({ error: "A server name: lower-case letters, digits and dashes, starting with a letter." }, 400);
        const b = await body(req);
        const old = hubStore.server(name);
        const label = str(b.label, 40) || old?.label || name;
        const address = str(b.address, 100) || old?.address || "";
        if (!ADDRESS.test(address)) return json({ error: "Address: host:port, e.g. 10.0.0.2:4510." }, 400);
        hubStore.saveServer({ name, label, address });
        return changed();
      },
      DELETE: (req: BunRequest<"/hub/admin/servers/:name">) => {
        const a = admin(req);
        if (a instanceof Response) return a;
        dropPeer(req.params.name);
        hubStore.dropServer(req.params.name);
        return changed();
      },
    },
    "/hub/admin/people": {
      POST: async (req: Request) => {
        const a = admin(req);
        if (a instanceof Response) return a;
        const email = str((await body(req)).email).toLowerCase();
        if (!EMAIL.test(email)) return json({ error: "That isn't an email address." }, 400);
        hubStore.addPerson(email);
        return changed();
      },
    },
    "/hub/admin/people/:email": {
      DELETE: (req: BunRequest<"/hub/admin/people/:email">) => {
        const a = admin(req);
        if (a instanceof Response) return a;
        hubStore.dropPerson(decodeURIComponent(req.params.email).toLowerCase());
        return changed();
      },
    },
    "/hub/admin/access": {
      PUT: async (req: Request) => {
        const a = admin(req);
        if (a instanceof Response) return a;
        const b = await body(req);
        const email = str(b.email).toLowerCase();
        const server = str(b.server);
        if (!hubStore.people().includes(email) || !hubStore.server(server)) return json({ error: "No such person or server." }, 400);
        hubStore.setAccess(email, server, b.granted === true);
        return changed();
      },
    },
    // A key printed by deploy/pair.sh on a new server: { key, name?, label? }.
    "/hub/admin/pair": {
      POST: async (req: Request) => {
        const a = admin(req);
        if (a instanceof Response) return a;
        const b = await body(req);
        const key = parseKey(str(b.key, 400));
        if (!key) return json({ error: "That isn't a pairing key (claudia-pair:…). Run deploy/pair.sh on the server to get one." }, 400);
        const name = str(b.name, 31) || suggestName(key.host);
        try {
          pair(key, name, str(b.label, 40) || key.host);
        } catch (err) {
          return json({ error: (err as Error).message }, 409);
        }
        return changed();
      },
    },
    // GitHub's webhook: main moved, or its hub image was signed (no login: Caddy lets this path through).
    "/hub/github": { POST: githubHook },
    // A joining server fetching its address with its secret (no login: Caddy lets only this path through).
    "/hub/enroll": {
      POST: async (req: Request) => {
        const r = enroll(str((await body(req)).secret, 100));
        return json(r.body, r.status);
      },
    },
  },
  async fetch(req, server) {
    const path = new URL(req.url).pathname;
    if (path.startsWith("/s/")) return toServer(req, server);
    return (req.method === "GET" && (await webFile(path))) || new Response("Not found", { status: 404 });
  },
  websocket: pipe(),
});

console.log(`Claudia hub on http://${HOST}:${PORT}`);
await startPush();
