// At the desktop (decision 24): while a person works at a Claudia page outside the iPhone app, the
// hub holds their phone alerts. The page (web/presence.ts) and the hub (src/hub/presence.ts) both
// read these. (Its own file: the page imports it.)

// No mouse, keyboard or touch input on the page for this long = away from the desktop: the held
// alerts go to the phone. The user's pick (2026-10-29), not a setting.
export const AWAY_AFTER_MS = 2 * 60_000;
// An active page says so this often; one that stops saying it (closed, asleep) counts as gone
// after a beat more.
export const HEARTBEAT_MS = 30_000;
export const PRESENCE_STALE_MS = HEARTBEAT_MS + 15_000;
