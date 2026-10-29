// At the desktop (decision 24): a hub page outside the iPhone app tells the hub whether its person
// is working at it, so their phone alerts wait meanwhile. Active = the page shows, has the focus,
// and had mouse, keyboard or touch input within AWAY_AFTER_MS. Said every HEARTBEAT_MS while
// active, and at once when that stops (blur, hidden, idle, the page closing).
import { AWAY_AFTER_MS, HEARTBEAT_MS } from "../src/presence";

const page = crypto.randomUUID();
let input = Date.now();
let said: boolean | null = null;
let at = 0;

const active = () => document.visibilityState === "visible" && document.hasFocus() && Date.now() - input < AWAY_AFTER_MS;

function say(on: boolean) {
  said = on;
  at = Date.now();
  void fetch("/hub/presence", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ page, active: on }), keepalive: true }).catch(() => {});
}

function tick() {
  const on = active();
  if (on !== said || (on && Date.now() - at >= HEARTBEAT_MS)) say(on);
}

let started = false;
export function startPresence() {
  if (started || window.claudiaNative) return;
  started = true;
  const touched = () => {
    input = Date.now();
    if (said !== true) tick();
  };
  for (const e of ["pointerdown", "pointermove", "keydown", "wheel", "touchstart"]) window.addEventListener(e, touched, { passive: true, capture: true });
  for (const e of ["focus", "blur"]) window.addEventListener(e, tick);
  document.addEventListener("visibilitychange", tick);
  // Closing: said with a beacon, which outlives the page.
  window.addEventListener("pagehide", () => {
    if (said) navigator.sendBeacon("/hub/presence", new Blob([JSON.stringify({ page, active: false })], { type: "application/json" }));
    said = false;
  });
  // Catches the input window running out, and the beat.
  setInterval(tick, 5_000);
  tick();
}
