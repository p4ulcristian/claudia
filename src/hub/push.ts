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
//
// Decision 24: an alert's title and body are sealed for each phone (seal.ts); every alert carries
// the badge, the person's chats waiting on them plus those finished since they last opened them
// (across every server they may use), and a change of that count alone goes as a badge-only push.
// While the person works at a desktop page (presence.ts) alerts are held, and when they leave it,
// only what still matters goes, the newest per chat. The Live Activity is live.ts.
import { hubStore, type Device } from "./store";
import { apnsConfig, createApns, dryRun, shortToken, type Apns } from "./apns";
import { seal } from "./seal";
import { present, whenAway } from "./presence";
import { liveChanged, startLive, type LiveItem, type Summary } from "./live";

export type Kind = "finished" | "ask" | "permission";
export type Notice = { server: string; chat: string; kind: Kind; event: string; title: string; body: string };

// A chat row of a server's /api/chats, the fields read here (a server from before them has none).
export type Row = {
  id: string;
  title?: string;
  running?: boolean;
  // Work Claude left running after its turn (decision 25): the chat still works.
  agents?: number;
  asks?: { id: string; question: string; ago?: number }[];
  permits?: { id: string; tool: string; description: string; ago?: number }[];
  lastTurn?: { id: string; ago: number; stopped: boolean; failed: boolean; reply?: string } | null;
  runningFor?: number | null;
  unseen?: boolean;
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
// A badge-only push waits for the count to stay put this long (opening a chat right as it finishes
// would otherwise move the badge up and down).
const BADGE_SETTLE_MS = 3_000;
// Until each of a person's servers has answered once (the hub just started), their count is partial:
// no badge-only push before that, or this long.
const BADGE_READY_MS = 30_000;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const started = Date.now();

const cut = (s: string, max: number) => {
  const one = Array.from(s.replace(/\s+/g, " ").trim());
  return one.length > max ? `${one.slice(0, max - 1).join("")}…` : one.join("");
};
const waits = (r: Row) => (r.asks?.length ?? 0) + (r.permits?.length ?? 0) > 0;
// Claude's turn runs, or work it left running after the turn does (subagents, background shells).
const working = (r: Row) => !!r.running || (Number(r.agents) || 0) > 0;

// The events a server's list holds for its person now. since: when they got their first phone
// (a turn that ended before that is not news to it).
export function noticesOf(server: string, rows: Row[], since: number, now = Date.now()): Notice[] {
  const out: Notice[] = [];
  for (const r of rows) {
    if (!r || typeof r.id !== "string" || !UUID.test(r.id)) continue;
    const title = cut(String(r.title ?? "") || "Chat", 80);
    const base = { server, chat: r.id, title };
    for (const a of r.asks ?? []) out.push({ ...base, kind: "ask", event: `ask:${a.id}`, body: cut(String(a.question ?? "") || "Has a question", 300) });
    for (const p of r.permits ?? []) {
      const what = [p.tool, p.description].filter(Boolean).join(": ");
      out.push({ ...base, kind: "permission", event: `permit:${p.id}`, body: cut(`Needs your permission: ${what}`, 180) });
    }
    const t = r.lastTurn;
    // Not a turn the person stopped, nor one followed by the next (a message sent meanwhile), nor
    // one whose agents still work (told once the last ends; if its result starts a turn, that
    // turn's end is told instead), nor one they have seen since it ended (unseen false; a server
    // from before it says nothing): the chat is told once it is done. The body is the start of
    // Claude's reply.
    if (t && typeof t.id === "string" && !t.stopped && !working(r) && r.unseen !== false && t.ago < FRESH_MS && now - t.ago >= since)
      out.push({ ...base, kind: "finished", event: `turn:${t.id}`, body: t.failed ? "Ended with an error" : cut(String(t.reply ?? "") || "Finished", 200) });
  }
  return out;
}

// What the phone gets: a plain alert Apple can read ("Claudia", "New message") and, sealed with the
// phone's key in enc, the real title and body, which the app's extension shows instead. url: the
// hub's address of the chat, which the app opens on a tap.
export function payloadOf(n: Notice, key: string, badge: number) {
  return {
    aps: {
      alert: { title: "Claudia", body: "New message" },
      "mutable-content": 1,
      sound: "default",
      badge,
      "thread-id": `${n.server}/${n.chat}`,
      "interruption-level": n.kind === "finished" ? "active" : "time-sensitive",
    },
    enc: seal(key, { title: n.title, body: n.body }),
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
  // Its list came at least once (the person's badge counts only once every server's has).
  reported = false;

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
      this.reported = true;
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
// Each person's newest list from each server, what the badge and the Live Activity are made of.
const lists = new Map<string, Map<string, Row[]>>();
// Alerts held while the person is at a desktop page: by chat, in the order they came.
const held = new Map<string, Map<string, Notice[]>>();
const badgeTimers = new Map<string, Timer>();

// Each person's server list as it stands: watched as long as they have a phone and the grid lets them in.
export function syncPush() {
  if (!apns) return;
  const want = new Map<string, { email: string; server: string; address: string }>();
  for (const { email } of hubStore.pushPeople())
    for (const s of hubStore.allowed(email)) want.set(`${email}\n${s.name}\n${s.address}`, { email, server: s.name, address: s.address });
  const touched = new Set<string>();
  for (const [key, w] of watchers)
    if (!want.has(key)) {
      w.stop();
      watchers.delete(key);
      lists.get(w.email)?.delete(w.server);
      touched.add(w.email);
    }
  for (const [key, w] of want) if (!watchers.has(key)) watchers.set(key, new Watcher(w.email, w.server, w.address, tell));
  // (a server taken away from someone leaves their badge and card at once)
  for (const email of touched) pushChanged(email);
}

function tell(w: Watcher, rows: Row[]) {
  // Still let in, and still with a phone (the grid may have changed since the last sync).
  if (!hubStore.may(w.email, w.server)) return;
  const devices = hubStore.devices(w.email);
  if (!devices.length) return;
  const mine = lists.get(w.email) ?? new Map<string, Row[]>();
  mine.set(w.server, rows.filter((r) => r && typeof r.id === "string" && UUID.test(r.id)));
  lists.set(w.email, mine);
  const since = Math.min(...devices.map((d) => d.addedAt));
  const news = noticesOf(w.server, rows, since).filter((n) => !hubStore.wasTold(w.email, n.server, n.chat, n.event));
  if (present(w.email)) hold(w.email, news);
  else for (const n of news) if (hubStore.claim(w.email, n.server, n.chat, n.event)) alert(w.email, n);
  pushChanged(w.email);
}

// The badge and the Live Activity follow the person's lists (a phone registered, a list changed).
export function pushChanged(email: string) {
  if (!apns) return;
  scheduleBadge(email);
  liveChanged(email);
}

// Every chat of the person's that runs or waits on them, and how many finished since they last
// opened them, across the servers the grid lets them use. since: when it started running or
// waiting, kept steady across fetches (the servers say "ago", which moves by a fetch's delay).
const sinceSeen = new Map<string, number>();
export function summaryOf(email: string): Summary {
  const now = Date.now();
  const out: Summary = { running: [], waiting: [], done: 0 };
  const keep = new Set<string>();
  const steady = (key: string, ago: number) => {
    keep.add(key);
    const at = Math.floor((now - Math.max(0, ago)) / 1000);
    const had = sinceSeen.get(key);
    if (had !== undefined && Math.abs(had - at) <= 10) return had;
    sinceSeen.set(key, at);
    return at;
  };
  for (const [server, rows] of lists.get(email) ?? []) {
    if (!hubStore.may(email, server)) continue;
    for (const r of rows) {
      const base = { title: String(r.title ?? "") || "Chat", server, chat: r.id };
      if (waits(r)) {
        const open = [...(r.asks ?? []), ...(r.permits ?? [])];
        const longest = open.reduce((m, x) => Math.max(m, Number(x.ago) || 0), 0);
        const kind: LiveItem["kind"] = r.asks?.length ? "ask" : "permission";
        out.waiting.push({ ...base, kind, since: steady(`${email}\n${server}/${r.id}\nwait:${open.map((x) => x.id).join(",")}`, longest) });
      } else if (working(r)) out.running.push({ ...base, kind: null, since: steady(`${email}\n${server}/${r.id}\nrun`, Number(r.runningFor) || 0) });
      else if (r.unseen) out.done++;
    }
  }
  for (const k of sinceSeen.keys()) if (k.startsWith(`${email}\n`) && !keep.has(k)) sinceSeen.delete(k);
  // Longest waiting first; running in the order they started.
  out.waiting.sort((a, b) => a.since - b.since);
  out.running.sort((a, b) => a.since - b.since);
  return out;
}

// The app icon's number: chats waiting on the person plus chats finished since they last opened them.
export function badgeOf(email: string) {
  const s = summaryOf(email);
  return s.waiting.length + s.done;
}

// Whether every server of the person's has answered since the hub started (else the count is partial).
function badgeReady(email: string) {
  if (Date.now() - started >= BADGE_READY_MS) return true;
  for (const w of watchers.values()) if (w.email === email && !w.reported) return false;
  return true;
}

// A badge-only push to each phone whose number is not the count, once the count has settled.
function scheduleBadge(email: string, wait = BADGE_SETTLE_MS) {
  const t = badgeTimers.get(email);
  if (t) clearTimeout(t);
  badgeTimers.set(email, setTimeout(() => void sendBadge(email), wait));
}

async function sendBadge(email: string) {
  badgeTimers.delete(email);
  if (!badgeReady(email)) return scheduleBadge(email, 5_000);
  const n = badgeOf(email);
  for (const d of hubStore.devices(email)) {
    if (d.badge === n) continue;
    const r = await apns!.send(d, { aps: { badge: n } }, { type: "alert", priority: 5 });
    if (r.status === "ok" || r.status === "dry") {
      hubStore.setBadge(d.token, n);
      if (r.status === "ok") hubStore.deviceOk(d.token);
    } else if (r.status === "gone") goneDevice(d, r.detail);
    else if (r.status === "retry") scheduleBadge(email, 30_000);
    else console.error(`push: badge to ${shortToken(d.token)} not sent: ${r.detail}`);
  }
}

// Holding while the person is at a desktop: each event once, in the order it came.
function hold(email: string, news: Notice[]) {
  if (!news.length) return;
  const mine = held.get(email) ?? new Map<string, Notice[]>();
  for (const n of news) {
    const k = `${n.server}/${n.chat}`;
    const list = mine.get(k) ?? [];
    if (!list.some((x) => x.event === n.event)) list.push(n);
    mine.set(k, list);
  }
  held.set(email, mine);
}

// The person left the desktop: per chat the newest held alert goes if it still matters (the
// question still open, the turn still unseen); the rest are dropped, never told later.
function release(email: string) {
  const mine = held.get(email);
  held.delete(email);
  if (!mine) return;
  for (const list of mine.values()) {
    const newest = list.at(-1)!;
    for (const n of list.slice(0, -1)) hubStore.claim(email, n.server, n.chat, n.event);
    if (hubStore.claim(email, newest.server, newest.chat, newest.event) && stillMatters(email, newest)) alert(email, newest);
  }
}

function stillMatters(email: string, n: Notice) {
  if (!hubStore.may(email, n.server)) return false;
  const r = lists.get(email)?.get(n.server)?.find((x) => x.id === n.chat);
  if (!r) return false;
  if (n.kind === "ask") return !!r.asks?.some((a) => `ask:${a.id}` === n.event);
  if (n.kind === "permission") return !!r.permits?.some((p) => `permit:${p.id}` === n.event);
  return !!r.lastTurn && `turn:${r.lastTurn.id}` === n.event && !working(r) && r.unseen !== false;
}

// One alert to each of the person's phones, with the badge as it stands.
function alert(email: string, n: Notice) {
  const badge = badgeOf(email);
  for (const d of hubStore.devices(email)) void deliver(d, n, badge);
}

function goneDevice(d: Device, detail?: string) {
  console.log(`push: APNs says ${shortToken(d.token)} (${d.email}) is gone (${detail}); dropped`);
  hubStore.forgetDevice(d.token);
}

async function deliver(d: Device, n: Notice, badge: number) {
  const payload = payloadOf(n, d.key, badge);
  for (let i = 0; ; i++) {
    const r = await apns!.send(d, payload);
    if (r.status === "ok" || r.status === "dry") {
      if (r.status === "ok") hubStore.deviceOk(d.token);
      return void hubStore.setBadge(d.token, badge);
    }
    if (r.status === "gone") return goneDevice(d, r.detail);
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
  startLive(apns, summaryOf);
  whenAway(release);
  syncPush();
  // The grid, the servers and the phones change through hub.ts, which syncs at once; this catches
  // anything else (an edit to the store by hand).
  setInterval(syncPush, 60_000);
  // What was told stays remembered for 30 days: enough for any question still open.
  const prune = () => {
    hubStore.prunePushed(Date.now() - 30 * 24 * 3600_000);
    hubStore.pruneActivities(Date.now() - 24 * 3600_000);
  };
  prune();
  setInterval(prune, 24 * 3600_000);
}

export const pushOn = () => apns !== null;
