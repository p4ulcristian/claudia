// The hub's own store, on the gate: the servers it reaches, who may use which, and pairings waiting
// for their WireGuard peer.
import { Database } from "bun:sqlite";
import { mkdirSync } from "node:fs";
import { join } from "node:path";

export const HUB_DATA = process.env.HUB_DATA ?? join(process.env.XDG_DATA_HOME ?? `${process.env.HOME}/.local/share`, "claudia-hub");
mkdirSync(HUB_DATA, { recursive: true });

const db = new Database(join(HUB_DATA, "hub.db"));
db.exec(`
  PRAGMA journal_mode = WAL;
  PRAGMA foreign_keys = ON;
  -- A server: its short name (in addresses: /s/<name>/…, /c/<name>/<chat>), the label people see,
  -- and where its page listens on WireGuard (host:port).
  CREATE TABLE IF NOT EXISTS servers (
    name TEXT PRIMARY KEY,
    label TEXT NOT NULL,
    address TEXT NOT NULL,
    added_at INTEGER NOT NULL
  );
  -- The people of the access grid, by their sign-in email.
  CREATE TABLE IF NOT EXISTS people (
    email TEXT PRIMARY KEY,
    added_at INTEGER NOT NULL
  );
  -- A tick in the grid: this person works on this server as their own profile there.
  CREATE TABLE IF NOT EXISTS access (
    email TEXT NOT NULL REFERENCES people (email) ON DELETE CASCADE,
    server TEXT NOT NULL REFERENCES servers (name) ON DELETE CASCADE,
    PRIMARY KEY (email, server)
  );
  -- A server joining from the Servers page: its WireGuard key, the address it was given, and the
  -- hash of the one-time secret it fetches its address with. Done once it has fetched it.
  CREATE TABLE IF NOT EXISTS pairings (
    server TEXT PRIMARY KEY REFERENCES servers (name) ON DELETE CASCADE,
    public_key TEXT NOT NULL UNIQUE,
    ip TEXT NOT NULL UNIQUE,
    secret_hash TEXT NOT NULL UNIQUE,
    created_at INTEGER NOT NULL,
    fetched_at INTEGER
  );
  -- A phone of a person (decision 23): its APNs device token, which APNs (development builds go to
  -- the sandbox) and the app's bundle id (the push topic). Kept when the person leaves the grid:
  -- they are only told about servers the grid still lets them use.
  CREATE TABLE IF NOT EXISTS devices (
    token TEXT PRIMARY KEY,
    email TEXT NOT NULL,
    env TEXT NOT NULL CHECK (env IN ('development', 'production')),
    topic TEXT NOT NULL,
    added_at INTEGER NOT NULL,
    last_ok_at INTEGER
  );
  CREATE INDEX IF NOT EXISTS devices_email ON devices (email);
  -- What a person was already told about (a chat's question, permission ask or finished turn, by
  -- the server's id for it), so a hub restart or reconnect never tells it again.
  CREATE TABLE IF NOT EXISTS pushed (
    email TEXT NOT NULL,
    server TEXT NOT NULL,
    chat TEXT NOT NULL,
    event TEXT NOT NULL,
    at INTEGER NOT NULL,
    PRIMARY KEY (email, server, chat, event)
  );
`);

export type Server = { name: string; label: string; address: string };
export type Pairing = { server: string; publicKey: string; ip: string; createdAt: number; fetchedAt: number | null };

const q = {
  servers: db.query<Server, []>("SELECT name, label, address FROM servers ORDER BY added_at, name"),
  server: db.query<Server, [string]>("SELECT name, label, address FROM servers WHERE name = ?"),
  saveServer: db.query(
    `INSERT INTO servers (name, label, address, added_at) VALUES (?, ?, ?, ?)
     ON CONFLICT (name) DO UPDATE SET label = excluded.label, address = excluded.address`,
  ),
  dropServer: db.query("DELETE FROM servers WHERE name = ?"),
  people: db.query<{ email: string }, []>("SELECT email FROM people ORDER BY added_at, email"),
  addPerson: db.query("INSERT OR IGNORE INTO people (email, added_at) VALUES (?, ?)"),
  dropPerson: db.query("DELETE FROM people WHERE email = ?"),
  access: db.query<{ email: string; server: string }, []>("SELECT email, server FROM access"),
  grant: db.query("INSERT OR IGNORE INTO access (email, server) VALUES (?, ?)"),
  revoke: db.query("DELETE FROM access WHERE email = ? AND server = ?"),
  allowed: db.query<Server, [string]>(
    "SELECT s.name, s.label, s.address FROM servers s JOIN access a ON a.server = s.name WHERE a.email = ? ORDER BY s.added_at, s.name",
  ),
  may: db.query<{ n: number }, [string, string]>("SELECT 1 AS n FROM access WHERE email = ? AND server = ?"),
  pairings: db.query<{ server: string; public_key: string; ip: string; created_at: number; fetched_at: number | null }, []>(
    "SELECT server, public_key, ip, created_at, fetched_at FROM pairings ORDER BY created_at",
  ),
  addPairing: db.query("INSERT INTO pairings (server, public_key, ip, secret_hash, created_at) VALUES (?, ?, ?, ?, ?)"),
  pairingBySecret: db.query<{ server: string; public_key: string; ip: string; created_at: number; fetched_at: number | null }, [string]>(
    "SELECT server, public_key, ip, created_at, fetched_at FROM pairings WHERE secret_hash = ?",
  ),
  fetched: db.query("UPDATE pairings SET fetched_at = ? WHERE server = ? AND fetched_at IS NULL"),
  // (a token registered again by another person's sign-in moves to them)
  saveDevice: db.query(
    `INSERT INTO devices (token, email, env, topic, added_at) VALUES (?, ?, ?, ?, ?)
     ON CONFLICT (token) DO UPDATE SET email = excluded.email, env = excluded.env, topic = excluded.topic`,
  ),
  dropDevice: db.query("DELETE FROM devices WHERE token = ? AND email = ?"),
  forgetDevice: db.query("DELETE FROM devices WHERE token = ?"),
  devices: db.query<DeviceRow, [string]>("SELECT token, email, env, topic, added_at, last_ok_at FROM devices WHERE email = ? ORDER BY added_at"),
  pushPeople: db.query<{ email: string; since: number }, []>("SELECT email, MIN(added_at) AS since FROM devices GROUP BY email"),
  deviceOk: db.query("UPDATE devices SET last_ok_at = ? WHERE token = ?"),
  claim: db.query("INSERT OR IGNORE INTO pushed (email, server, chat, event, at) VALUES (?, ?, ?, ?, ?)"),
  prunePushed: db.query("DELETE FROM pushed WHERE at < ?"),
};

type DeviceRow = { token: string; email: string; env: "development" | "production"; topic: string; added_at: number; last_ok_at: number | null };
export type Device = { token: string; email: string; env: "development" | "production"; topic: string; addedAt: number; lastOkAt: number | null };
const asDevice = (r: DeviceRow): Device => ({ token: r.token, email: r.email, env: r.env, topic: r.topic, addedAt: r.added_at, lastOkAt: r.last_ok_at });

const asPairing = (r: { server: string; public_key: string; ip: string; created_at: number; fetched_at: number | null }): Pairing =>
  ({ server: r.server, publicKey: r.public_key, ip: r.ip, createdAt: r.created_at, fetchedAt: r.fetched_at });

export const hubStore = {
  servers: () => q.servers.all(),
  server: (name: string) => q.server.get(name) ?? null,
  saveServer: (s: Server) => q.saveServer.run(s.name, s.label, s.address, Date.now()),
  dropServer: (name: string) => q.dropServer.run(name),
  people: () => q.people.all().map((r) => r.email),
  addPerson: (email: string) => q.addPerson.run(email, Date.now()),
  dropPerson: (email: string) => q.dropPerson.run(email),
  access: () => q.access.all(),
  setAccess: (email: string, server: string, granted: boolean) => (granted ? q.grant.run(email, server) : q.revoke.run(email, server)),
  // The servers this person may use, in the order they were added.
  allowed: (email: string) => q.allowed.all(email),
  may: (email: string, server: string) => !!q.may.get(email, server),
  pairings: () => q.pairings.all().map(asPairing),
  // A new server and its pairing, together or not at all.
  addPaired: (s: Server, p: { publicKey: string; ip: string; secretHash: string }) =>
    db.transaction(() => {
      q.saveServer.run(s.name, s.label, s.address, Date.now());
      q.addPairing.run(s.name, p.publicKey, p.ip, p.secretHash, Date.now());
    })(),
  pairingBySecret: (hash: string) => {
    const r = q.pairingBySecret.get(hash);
    return r ? asPairing(r) : null;
  },
  markFetched: (server: string) => q.fetched.run(Date.now(), server),
  saveDevice: (d: { token: string; email: string; env: "development" | "production"; topic: string }) =>
    q.saveDevice.run(d.token, d.email, d.env, d.topic, Date.now()),
  // A person removing one of their own phones: false when it isn't theirs (or not there).
  dropDevice: (token: string, email: string) => q.dropDevice.run(token, email).changes > 0,
  // APNs said the token is dead.
  forgetDevice: (token: string) => q.forgetDevice.run(token),
  devices: (email: string) => q.devices.all(email).map(asDevice),
  // The people with a phone, and since when they have had one.
  pushPeople: () => q.pushPeople.all(),
  deviceOk: (token: string) => q.deviceOk.run(Date.now(), token),
  // Takes the right to tell a person about an event: true only the first time.
  claim: (email: string, server: string, chat: string, event: string) => q.claim.run(email, server, chat, event, Date.now()).changes > 0,
  prunePushed: (before: number) => q.prunePushed.run(before),
};
