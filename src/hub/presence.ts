// Who is at a desktop now (decision 24): each Claudia page outside the iPhone app tells the hub
// whether its person is working at it (POST /hub/presence, web/presence.ts), every HEARTBEAT_MS
// while they are and at once when they stop. A person is present while any of their pages said
// "active" within PRESENCE_STALE_MS. Kept in memory: after a hub restart everyone is away until
// their page's next beat, so alerts go out rather than wait.
import { PRESENCE_STALE_MS } from "../presence";

const pages = new Map<string, Map<string, number>>();
const timers = new Map<string, Timer>();
let onAway: (email: string) => void = () => {};

export function whenAway(f: (email: string) => void) {
  onAway = f;
}

export function present(email: string, now = Date.now()) {
  for (const at of pages.get(email)?.values() ?? []) if (now - at < PRESENCE_STALE_MS) return true;
  return false;
}

// One page's word: active (a beat) or not (blurred, hidden, idle, closing).
export function beat(email: string, page: string, active: boolean) {
  const was = present(email);
  const mine = pages.get(email) ?? new Map<string, number>();
  if (active) mine.set(page, Date.now());
  else mine.delete(page);
  for (const [p, at] of mine) if (Date.now() - at >= PRESENCE_STALE_MS) mine.delete(p);
  if (mine.size) pages.set(email, mine);
  else pages.delete(email);
  check(email, was);
}

// Away now (the last active page stopped, or went quiet): tell once; else look again when the
// newest beat goes stale.
function check(email: string, was: boolean) {
  const t = timers.get(email);
  if (t) clearTimeout(t);
  timers.delete(email);
  if (!present(email)) {
    pages.delete(email);
    if (was) onAway(email);
    return;
  }
  const newest = Math.max(...pages.get(email)!.values());
  timers.set(email, setTimeout(() => check(email, true), newest + PRESENCE_STALE_MS - Date.now() + 50));
}
