// Phone pushes (decision 23): the hub tells a person's iPhones when one of their chats finished a
// turn, asks them a question, or waits on their permission. It learns that the way the page does,
// as that person: for each server the access grid lets them use, the server's chat socket says
// "chats_changed" and /api/chats has every chat's state (runner.ts attentionFields). So servers
// reach nothing new, and since the call carries no view, only the person's own chats are read:
// people with a read-only view of them are never told. Only people with a phone are watched.
//
// Each event is told once per person: the server gives it an id (the question's or permission
// ask's, the turn's result), and the hub's store remembers which it told (hubStore.claim), so a
// hub restart or a reconnect sends nothing twice.
import { hubStore, type Device } from "./store";
import { apnsConfig, createApns, dryRun, shortToken, type Apns } from "./apns";

export type Kind = "finished" | "ask" | "permission";
export type Notice = { server: string; chat: string; kind: Kind; event: string; title: string; body: string };

// A chat row of a server's /api/chats, the fields read here (a server from before them has none).
type Row = {
  id: string;
  title?: string;
  running?: boolean;
  asks?: { id: string; question: string }[];
  permits?: { id: string; tool: string; description: string }[];
  lastTurn?: { id: string; ago: number; stopped: boolean; failed: boolean } | null;
};

// A turn that ended longer ago than this when the hub first sees it (it was down) isn't told:
// old news. Open questions and permission asks are told however old: they still wait.
const FRESH_MS = 10 * 60_000;
// Fetches of a server's list for one person: at most one per 2 s (busy chats change often), and
// once a minute anyway, which also finds a link that died without closing.
const GAP_MS = 2_000;
const POLL_MS = 60_000;
const FETCH_MS = 15_000;
const BACKOFF_MS = [1_000, 2_000, 5_000, 10_000, 30_000, 60_000];
// Tries again after these when Apple is busy or unreachable.
const RETRY_MS = [5_000, 30_000, 120_000];
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

const cut = (s: string, max: number) => {
  const one = s.replace(/\s+/g, " ").trim();
  return one.length > max ? `${one.slice(0, max - 1)}…` : one;
};

// The events a server's list holds for its person now. since: when they got their first phone
// (a turn that ended before that is not news to it).
export function noticesOf(server: string, rows: Row[], since: number, now = Date.now()): Notice[] {
  const out: Notice[] = [];
  for (const r of rows) {
    if (!r || typeof r.id !== "string" || !UUID.test(r.id)) continue;
    const title = cut(String(r.title ?? "") || "Chat", 80);
    const base = { server, chat: r.id, title };
    for (const a of r.asks ?? []) out.push({ ...base, kind: "ask", event: `ask:${a.id}`, body: cut(String(a.question ?? "") || "Has a question", 180) });
    for (const p of r.permits ?? []) {
      const what = [p.tool, p.description].filter(Boolean).join(": ");
      out.push({ ...base, kind: "permission", event: `permit:${p.id}`, body: cut(`Needs your permission: ${what}`, 180) });
    }
    const t = r.lastTurn;
    // Not a turn the person stopped, nor one followed by the next (a message sent meanwhile): the
    // chat is told once it is done.
    if (t && typeof t.id === "string" && !t.stopped && !r.running && t.ago < FRESH_MS && now - t.ago >= since)
      out.push({ ...base, kind: "finished", event: `turn:${t.id}`, body: t.failed ? "Ended with an error" : "Finished" });
  }
  return out;
}

// What the phone gets. url: the hub's address of the chat, which the app opens on a tap.
export function payloadOf(n: Notice) {
  return {
    aps: {
      alert: { title: n.title, body: n.body },
      sound: "default",
      "thread-id": `${n.server}/${n.chat}`,
      "interruption-level": n.kind === "finished" ? "active" : "time-sensitive",
    },
    url: `/c/${n.server}/${n.chat}`,
    server: n.server,
    chat: n.chat,
    kind: n.kind,
  };
}

// One person on one server: the server's chat socket for "chats_changed", and its list on each.
class Watcher {
  private ws: WebSocket | null = null;
  private stopped = false;
  private tries = 0;
  private timer: Timer | null = null;
  private poll: Timer | null = null;
  private soon: Timer | null = null;
  private busy = false;
  private again = false;
  private last = 0;
  // The trouble last logged, so a server that stays down is logged once, not every retry.
  private trouble = "";
  private readonly headers: Record<string, string>;

  constructor(readonly email: string, readonly server: string, readonly address: string, private tell: (w: Watcher, rows: Row[]) => void) {
    // Who is calling, as the hub says it when it passes the person's own requests on (hub.ts toServer).
    this.headers = { "x-auth-request-email": email };
    this.connect();
  }

  stop() {
    this.stopped = true;
    for (const t of [this.timer, this.poll, this.soon]) if (t) clearTimeout(t);
    this.ws?.close();
    this.ws = null;
  }

  private connect() {
    if (this.stopped) return;
    // (Bun's WebSocket takes headers)
    const ws = new WebSocket(`ws://${this.address}/ws`, { headers: this.headers } as unknown as string[]);
    this.ws = ws;
    ws.onopen = () => {
      this.refresh();
      this.poll = setInterval(() => this.refresh(), POLL_MS);
    };
    ws.onmessage = (e) => {
      try {
        if ((JSON.parse(String(e.data)) as { t?: string }).t === "chats_changed") this.refresh();
      } catch {}
    };
    ws.onclose = () => this.down(ws, "no chat socket");
    ws.onerror = () => this.down(ws, "no chat socket");
  }

  // The socket is gone: try again, waiting longer each time (up to a minute) until a list comes.
  private down(ws: WebSocket, why: string) {
    if (this.ws !== ws) return;
    this.ws = null;
    if (this.poll) clearInterval(this.poll);
    this.poll = null;
    ws.close();
    if (this.stopped) return;
    this.troubled(why);
    const wait = BACKOFF_MS[Math.min(this.tries++, BACKOFF_MS.length - 1)]!;
    this.timer = setTimeout(() => this.connect(), wait * (0.8 + Math.random() * 0.4));
  }

  private troubled(why: string) {
    if (this.trouble === why) return;
    this.trouble = why;
    console.error(`push: ${this.email} on ${this.server}: ${why}; trying again`);
  }

  // One fetch at a time, at most one per GAP_MS; a change during a fetch fetches again after it.
  private refresh() {
    if (this.stopped) return;
    if (this.busy) return void (this.again = true);
    if (this.soon) return;
    this.soon = setTimeout(() => {
      this.soon = null;
      void this.fetchList();
    }, Math.max(0, this.last + GAP_MS - Date.now()));
  }

  private async fetchList() {
    this.busy = true;
    this.last = Date.now();
    try {
      const r = await fetch(`http://${this.address}/api/chats`, { headers: this.headers, signal: AbortSignal.timeout(FETCH_MS) });
      if (!r.ok) throw new Error(`its chat list answered ${r.status}`);
      const rows = (await r.json()) as unknown;
      if (!Array.isArray(rows)) throw new Error("its chat list isn't a list");
      if (this.stopped) return;
      if (this.trouble) console.log(`push: ${this.email} on ${this.server}: back`);
      this.trouble = "";
      this.tries = 0;
      this.tell(this, rows as Row[]);
    } catch (err) {
      if (this.stopped) return;
      // A link that answers no more: start over (down() logs it and waits).
      const ws = this.ws;
      this.troubled((err as Error).message);
      if (ws) this.down(ws, (err as Error).message);
    } finally {
      this.busy = false;
      if (this.again) {
        this.again = false;
        this.refresh();
      }
    }
  }
}

let apns: Apns | null = null;
const watchers = new Map<string, Watcher>();

// Each person's server list as it stands: watched as long as they have a phone and the grid lets them in.
export function syncPush() {
  if (!apns) return;
  const want = new Map<string, { email: string; server: string; address: string }>();
  for (const { email } of hubStore.pushPeople())
    for (const s of hubStore.allowed(email)) want.set(`${email}\n${s.name}\n${s.address}`, { email, server: s.name, address: s.address });
  for (const [key, w] of watchers)
    if (!want.has(key)) {
      w.stop();
      watchers.delete(key);
    }
  for (const [key, w] of want) if (!watchers.has(key)) watchers.set(key, new Watcher(w.email, w.server, w.address, tell));
}

function tell(w: Watcher, rows: Row[]) {
  // Still let in, and still with a phone (the grid may have changed since the last sync).
  if (!hubStore.may(w.email, w.server)) return;
  const devices = hubStore.devices(w.email);
  if (!devices.length) return;
  const since = Math.min(...devices.map((d) => d.addedAt));
  for (const n of noticesOf(w.server, rows, since))
    if (hubStore.claim(w.email, n.server, n.chat, n.event)) for (const d of devices) void deliver(d, n);
}

async function deliver(d: Device, n: Notice) {
  const payload = payloadOf(n);
  for (let i = 0; ; i++) {
    const r = await apns!.send(d, payload);
    if (r.status === "ok") return void hubStore.deviceOk(d.token);
    if (r.status === "dry") return;
    if (r.status === "gone") {
      console.log(`push: APNs says ${shortToken(d.token)} (${d.email}) is gone (${r.detail}); dropped`);
      return void hubStore.forgetDevice(d.token);
    }
    if (r.status === "failed" || i >= RETRY_MS.length) {
      console.error(`push: ${n.kind} for ${n.server}/${n.chat} to ${shortToken(d.token)} not sent: ${r.detail}`);
      return;
    }
    await Bun.sleep(RETRY_MS[i]!);
    // (dropped or moved to another person meanwhile)
    if (!hubStore.devices(d.email).some((x) => x.token === d.token)) return;
  }
}

// Once, when the hub starts: push on if the APNs key is set (or a dry run), else said once why not.
export async function startPush() {
  if (process.env.HUB_APNS_DRY_RUN === "1") {
    apns = dryRun;
    console.log("push: dry run (HUB_APNS_DRY_RUN=1): pushes are logged, not sent");
  } else {
    const c = apnsConfig();
    if ("off" in c) return console.log(`push: off (${c.off}); iPhone pushes need HUB_APNS_KEY_FILE, HUB_APNS_KEY_ID and HUB_APNS_TEAM_ID`);
    try {
      apns = await createApns(c.config);
    } catch (err) {
      return console.error(`push: off (the APNs key in HUB_APNS_KEY_FILE can't be used: ${(err as Error).message})`);
    }
    console.log(`push: on (APNs key ${c.config.keyId})`);
  }
  syncPush();
  // The grid, the servers and the phones change through hub.ts, which syncs at once; this catches
  // anything else (an edit to the store by hand).
  setInterval(syncPush, 60_000);
  // What was told stays remembered for 30 days: enough for any question still open.
  const prune = () => hubStore.prunePushed(Date.now() - 30 * 24 * 3600_000);
  prune();
  setInterval(prune, 24 * 3600_000);
}

export const pushOn = () => apns !== null;
