// A new server joins the hub over WireGuard. On the server, `deploy/pair.sh` makes a WireGuard key
// pair and a one-time secret and prints a key:
//
//   claudia-pair:<host name>:<WireGuard public key>:<secret>
//
// pasted into the hub's Servers page. The hub gives the server an address from its pool and asks
// the gate for the peer (a file per peer in PEERS_DIR, applied by a root helper on the gate, which
// answers in APPLIED_DIR). The server, polling /hub/enroll with its secret, gets its address and
// the gate's WireGuard details once the peer is up.
import { createHash } from "node:crypto";
import { mkdirSync, readFileSync, renameSync, rmSync, writeFileSync, existsSync } from "node:fs";
import { join } from "node:path";
import { HUB_DATA, hubStore } from "./store";

export const PEERS_DIR = process.env.HUB_PEERS_DIR ?? join(HUB_DATA, "peers");
export const APPLIED_DIR = process.env.HUB_APPLIED_DIR ?? join(HUB_DATA, "peers-applied");
mkdirSync(PEERS_DIR, { recursive: true });

// The addresses new servers get, set where the hub runs (the gate's unit): HUB_WG_POOL, like
// "10.0.0.100-10.0.0.199" (one /24, last octets). Read when a server pairs: a hub without it can't pair.
function pool() {
  const m = (process.env.HUB_WG_POOL ?? "").match(/^(\d+\.\d+\.\d+)\.(\d+)-(?:\d+\.\d+\.\d+\.)?(\d+)$/);
  if (!m) throw new Error("This hub has no WireGuard address pool (HUB_WG_POOL), so it can't pair servers.");
  return { prefix: m[1]!, from: Number(m[2]), to: Number(m[3]) };
}
// Where a server's page listens once joined.
export const SERVER_PORT = Number(process.env.HUB_SERVER_PORT ?? 4510);

// A WireGuard public key: 32 bytes in base64.
const WG_KEY = /^[A-Za-z0-9+/]{42}[AEIMQUYcgkosw048]=$/;
export const NAME = /^[a-z][a-z0-9-]{0,30}$/;

export type PairKey = { host: string; publicKey: string; secret: string };

export function parseKey(raw: string): PairKey | null {
  const parts = raw.trim().split(":");
  if (parts.length !== 4 || parts[0] !== "claudia-pair") return null;
  const [, host, publicKey, secret] = parts as [string, string, string, string];
  if (!WG_KEY.test(publicKey) || !/^[A-Za-z0-9_-]{43}$/.test(secret)) return null;
  return { host: host.slice(0, 64), publicKey, secret };
}

const hash = (secret: string) => createHash("sha256").update(secret).digest("hex");

// The host name the key carries, as a server name: lower case, letters, digits and dashes.
export const suggestName = (host: string) => host.toLowerCase().replace(/[^a-z0-9-]+/g, "-").replace(/^[^a-z]+/, "").slice(0, 31) || "server";

function freeIp(): string {
  const POOL = pool();
  const used = new Set(hubStore.pairings().map((p) => p.ip));
  for (let n = POOL.from; n <= POOL.to; n++) {
    const ip = `${POOL.prefix}.${n}`;
    if (!used.has(ip)) return ip;
  }
  throw new Error("No free WireGuard address left in the pool.");
}

// The peer file the gate's helper reads (a shell script, so one plain line: "<public key> <address>"):
// written beside, then renamed, so it never reads half.
function writePeer(server: string, publicKey: string, ip: string) {
  const file = join(PEERS_DIR, `${server}.peer`);
  writeFileSync(`${file}.new`, `${publicKey} ${ip}\n`, { mode: 0o644 });
  renameSync(`${file}.new`, file);
}

export function dropPeer(server: string) {
  rmSync(join(PEERS_DIR, `${server}.peer`), { force: true });
}

export function pair(key: PairKey, name: string, label: string) {
  if (!NAME.test(name)) throw new Error("A server name: lower-case letters, digits and dashes, starting with a letter.");
  if (hubStore.server(name)) throw new Error(`There is already a server called ${name}.`);
  if (hubStore.pairings().some((p) => p.publicKey === key.publicKey)) throw new Error("This key was already used.");
  const ip = freeIp();
  hubStore.addPaired({ name, label, address: `${ip}:${SERVER_PORT}` }, { publicKey: key.publicKey, ip, secretHash: hash(key.secret) });
  writePeer(name, key.publicKey, ip);
  return { name, ip };
}

// The gate's side of the tunnel, written by the helper: { publicKey, endpoint, address }.
function gateWg(): { publicKey: string; endpoint: string; address: string } | null {
  try {
    return JSON.parse(readFileSync(join(APPLIED_DIR, "gate.json"), "utf8"));
  } catch {
    return null;
  }
}

export const applied = (server: string) => existsSync(join(APPLIED_DIR, `${server}.applied`));

// The server asks with its secret. 404: no such pairing; 202: the peer isn't up on the gate yet.
export function enroll(secret: string): { status: number; body: unknown } {
  if (typeof secret !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(secret)) return { status: 400, body: { error: "secret" } };
  // (looked up by the secret's hash: the store never holds the secret)
  const p = hubStore.pairingBySecret(hash(secret));
  if (!p) return { status: 404, body: { error: "no pairing for this secret" } };
  const gate = gateWg();
  if (!gate || !applied(p.server)) return { status: 202, body: { waiting: "the gate is adding the peer" } };
  hubStore.markFetched(p.server);
  return {
    status: 200,
    body: { name: p.server, address: p.ip, port: SERVER_PORT, gate: { publicKey: gate.publicKey, endpoint: gate.endpoint, address: gate.address } },
  };
}
