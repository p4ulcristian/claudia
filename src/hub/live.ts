// One summary Live Activity per person's phone (decision 24): while any of their chats runs or
// waits on them, a lock-screen card and the Dynamic Island show how many, with the chats' titles.
// The hub starts it with the phone's push-to-start token, updates it through the token the app
// reports for it (POST /hub/push/live), and ends it when nothing has run or waited for a short
// while. ActivityKit payloads can't be sealed (iOS reads them, not the app), so the titles go to
// Apple in plain text: the user's accepted cost.
//
// Updates are state, not news: at most one per UPDATE_GAP_MS per person, priority 10 only when a
// chat newly waits on them, and one that fails is not retried as such: the state as it then stands
// is sent again a little later.
import { hubStore, type Device } from "./store";
import { shortToken, type Apns, type PushDevice } from "./apns";

export type LiveItem = { title: string; server: string; chat: string; since: number; kind: "ask" | "permission" | null };
// Every running and waiting chat (not cut down yet) and how many finished since last seen.
export type Summary = { running: LiveItem[]; waiting: LiveItem[]; done: number };

const UPDATE_GAP_MS = 5_000;
// Nothing running or waiting for this long ends the card: a follow-up sent right after a turn, or
// a chat started a moment later, keeps the same card instead of ending one and starting another.
const END_AFTER_MS = 30_000;
// After the end, the card stays on the lock screen this long (seconds).
const DISMISS_S = 15 * 60;
// iOS ends a Live Activity after 8 hours. One that ends sooner was dismissed by the person: no new
// card until the next time something runs; one that lived this long may start again at once.
const RENEW_AFTER_MS = 7.75 * 3600_000;
const RETRY_MS = 30_000;
const LIST_MAX = 5;
const TITLE_MAX = 40;
const PAYLOAD_MAX = 4096;
const ATTRIBUTES = "ClaudiaSummaryAttributes";

const cutTitle = (s: string) => {
  const chars = Array.from(s.replace(/\s+/g, " ").trim() || "Chat");
  return chars.length > TITLE_MAX ? `${chars.slice(0, TITLE_MAX - 1).join("")}…` : chars.join("");
};

// busy: a flush is sending (one at a time per person, so a phone never gets two starts).
type Person = { timer: Timer | null; busy: boolean; last: number; urgent: boolean; quietSince: number | null; waiting: Set<string> };

let apns: Apns | null = null;
let summaryOf: (email: string) => Summary = () => ({ running: [], waiting: [], done: 0 });
const people = new Map<string, Person>();
// What each activity was last sent (its content without the time), so an unchanged state isn't sent again.
const sentSig = new Map<string, string>();

export function startLive(a: Apns, summary: (email: string) => Summary) {
  apns = a;
  summaryOf = summary;
}

const person = (email: string) => {
  let p = people.get(email);
  if (!p) people.set(email, (p = { timer: null, busy: false, last: 0, urgent: false, quietSince: null, waiting: new Set() }));
  return p;
};

const active = (s: Summary) => s.running.length + s.waiting.length > 0;

// The content-state the app's ClaudiaSummaryAttributes reads: at most five of each, titles cut, the
// whole push under Apple's 4 KB (the longer list gives way first).
export function contentOf(s: Summary, nowS: number, wrap: (state: object) => object = (state) => ({ aps: { "content-state": state } })) {
  const item = (i: LiveItem) => ({ title: cutTitle(i.title), server: i.server, chat: i.chat, since: i.since, kind: i.kind });
  const running = s.running.slice(0, LIST_MAX).map((i) => ({ ...item(i), kind: null }));
  const waiting = s.waiting.slice(0, LIST_MAX).map(item);
  const state = () => ({ running, waiting, done: s.done, updatedAt: nowS });
  while (Buffer.byteLength(JSON.stringify(wrap(state()))) > PAYLOAD_MAX && running.length + waiting.length) (running.length >= waiting.length ? running : waiting).pop();
  return state();
}

// The person's chats changed: the card follows (soon, within the gap).
export function liveChanged(email: string) {
  if (!apns) return;
  const p = person(email);
  const s = summaryOf(email);
  const waiting = new Set(s.waiting.map((i) => `${i.server}/${i.chat}`));
  for (const k of waiting) if (!p.waiting.has(k)) p.urgent = true;
  p.waiting = waiting;
  if (active(s)) p.quietSince = null;
  else p.quietSince ??= Date.now();
  schedule(email);
}

function schedule(email: string, at?: number) {
  const p = person(email);
  if (p.timer) clearTimeout(p.timer);
  const when = at ?? Math.max(p.last + UPDATE_GAP_MS, Date.now());
  p.timer = setTimeout(() => {
    p.timer = null;
    if (p.busy) return schedule(email, Date.now() + 200);
    p.busy = true;
    void flush(email).finally(() => (p.busy = false));
  }, Math.max(0, when - Date.now()));
}

const liveTarget = (d: Device, token: string): PushDevice => ({ token, env: d.env, topic: `${d.topic}.push-type.liveactivity` });

async function flush(email: string) {
  const p = person(email);
  const now = Date.now();
  const nowS = Math.floor(now / 1000);
  const s = summaryOf(email);
  const on = active(s);
  // Quiet, but not for long yet: the card shows nothing running (an update), and ends later.
  const quiet = !on && p.quietSince !== null && now - p.quietSince < END_AFTER_MS;
  const priority = p.urgent ? 10 : 5;
  let sent = false;
  let again = false;
  for (const d of hubStore.devices(email)) {
    const acts = hubStore.activities(d.token);
    if ((on || quiet) && acts.length) {
      const state = contentOf(s, nowS, (st) => ({ aps: { timestamp: nowS, event: "update", "content-state": st } }));
      const sig = JSON.stringify({ ...state, updatedAt: 0 });
      for (const a of acts) {
        if (sentSig.get(a.activity) === sig) continue;
        const r = await apns!.send(liveTarget(d, a.token), { aps: { timestamp: nowS, event: "update", "content-state": state } }, { type: "liveactivity", priority });
        sent = true;
        if (r.status === "ok" || r.status === "dry") sentSig.set(a.activity, sig);
        else if (r.status === "gone") gone(a.activity, d);
        else if (r.status === "retry") again = true;
        else console.error(`push: Live Activity update to ${shortToken(d.token)} not sent: ${r.detail}`);
      }
    } else if (on && d.liveStart && d.liveStartedAt === null) {
      const wrap = (st: object) => ({
        aps: { timestamp: nowS, event: "start", "content-state": st, "attributes-type": ATTRIBUTES, attributes: {}, alert: { title: "Claudia", body: "Chats running" } },
      });
      const r = await apns!.send(liveTarget(d, d.liveStart), wrap(contentOf(s, nowS, wrap)), { type: "liveactivity", priority: 10 });
      sent = true;
      if (r.status === "ok" || r.status === "dry") hubStore.liveStarted(d.token, now);
      else if (r.status === "gone") {
        console.log(`push: APNs says the push-to-start token of ${shortToken(d.token)} is gone (${r.detail}); dropped`);
        hubStore.setLiveStart(d.token, null);
      } else if (r.status === "retry") again = true;
      else console.error(`push: Live Activity start on ${shortToken(d.token)} not sent: ${r.detail}`);
    } else if (!on && !quiet) {
      for (const a of acts) {
        const wrap = (st: object) => ({ aps: { timestamp: nowS, event: "end", "content-state": st, "dismissal-date": nowS + DISMISS_S } });
        const r = await apns!.send(liveTarget(d, a.token), wrap(contentOf(s, nowS, wrap)), { type: "liveactivity", priority: 5 });
        sent = true;
        if (r.status === "retry") {
          again = true;
          continue;
        }
        if (r.status === "failed") console.error(`push: Live Activity end to ${shortToken(d.token)} not sent: ${r.detail}`);
        hubStore.endActivity(a.activity);
        sentSig.delete(a.activity);
      }
      if (d.liveStartedAt !== null && !again) hubStore.liveStarted(d.token, null);
    }
  }
  if (sent) {
    p.last = now;
    p.urgent = false;
  }
  if (again) schedule(email, Date.now() + RETRY_MS);
  // (the end, once the quiet lasted)
  else if (quiet) schedule(email, Math.max(p.quietSince! + END_AFTER_MS, p.last + UPDATE_GAP_MS));
}

function gone(activity: string, d: Device) {
  console.log(`push: APNs says a Live Activity token of ${shortToken(d.token)} is gone; dropped`);
  hubStore.dropActivity(activity);
  sentSig.delete(activity);
}

// The app reported its activity's update token: the card gets the state as it stands.
export function liveReported(email: string) {
  liveChanged(email);
}

// The app saw its activity end (iOS's 8 hours, or the person dismissed it).
export function liveEnded(email: string, activity: { activity: string; device: string; addedAt: number }) {
  if (!hubStore.endActivity(activity.activity)) return;
  sentSig.delete(activity.activity);
  if (Date.now() - activity.addedAt >= RENEW_AFTER_MS && !hubStore.activities(activity.device).length) hubStore.liveStarted(activity.device, null);
  liveChanged(email);
}
