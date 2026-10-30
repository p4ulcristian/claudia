// GitHub's webhook for this repository (POST /hub/github; no login: the gate's Caddy lets only this
// path through, rate limited). GitHub signs each delivery with the shared secret
// (X-Hub-Signature-256), and a delivery can only make machines look at main sooner: each one fetches
// and checks what it runs by itself.
// - push to main: every server is poked (POST /api/update-check on its page), and pulls main.
// - a hub-image run on main succeeded: the gate's root helper is asked to update the hub, by writing
//   HUB_UPDATE_REQUEST (claudia-hub-update.path); it finds, pulls and verifies the image itself.
import { createHmac, timingSafeEqual } from "node:crypto";
import { readFileSync } from "node:fs";
import { hubStore } from "./store";

const REPO = process.env.HUB_REPO ?? "p4ulcristian/claudia";
const SECRET_FILE = process.env.HUB_GITHUB_SECRET_FILE?.trim() || null;
const REQUEST_FILE = process.env.HUB_UPDATE_REQUEST?.trim() || null;

const secret = (() => {
  if (!SECRET_FILE) return null;
  try {
    return readFileSync(SECRET_FILE, "utf8").trim() || null;
  } catch (err) {
    console.error(`github: no webhook secret (${(err as Error).message}); /hub/github is off`);
    return null;
  }
})();

function signed(body: Buffer, header: string | null) {
  if (!secret || !header?.startsWith("sha256=")) return false;
  const want = createHmac("sha256", secret).update(body).digest();
  const got = Buffer.from(header.slice("sha256=".length), "hex");
  return got.length === want.length && timingSafeEqual(got, want);
}

function pokeServers() {
  for (const s of hubStore.servers()) {
    fetch(`http://${s.address}/api/update-check`, { method: "POST", signal: AbortSignal.timeout(5000) })
      .then((r) => console.log(`github: poked ${s.name}: ${r.status}`))
      .catch((err) => console.log(`github: poked ${s.name}: ${(err as Error).message}`));
  }
}

async function requestHubUpdate() {
  if (!REQUEST_FILE) return console.log("github: hub image signed; HUB_UPDATE_REQUEST not set, so no update asked");
  await Bun.write(REQUEST_FILE, `${new Date().toISOString()}\n`);
  console.log("github: hub image signed; hub update asked");
}

type Delivery = {
  ref?: string;
  action?: string;
  repository?: { full_name?: string };
  workflow_run?: { path?: string; head_branch?: string; conclusion?: string };
};

export async function githubHook(req: Request) {
  if (!secret) return new Response(null, { status: 503 });
  const body = Buffer.from(await req.arrayBuffer());
  if (!signed(body, req.headers.get("x-hub-signature-256"))) return new Response(null, { status: 401 });
  let d: Delivery;
  try {
    d = JSON.parse(body.toString("utf8")) as Delivery;
  } catch {
    return new Response(null, { status: 400 });
  }
  if (d.repository?.full_name !== REPO) return new Response(null, { status: 204 });
  const event = req.headers.get("x-github-event");
  if (event === "push" && d.ref === "refs/heads/main") pokeServers();
  const run = d.workflow_run;
  if (event === "workflow_run" && d.action === "completed" && run?.conclusion === "success" && run.head_branch === "main" && run.path === ".github/workflows/hub-image.yml") await requestHubUpdate();
  return new Response(null, { status: 202 });
}
