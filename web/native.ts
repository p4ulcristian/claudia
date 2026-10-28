// The iPhone app (decision 23) is this page in a native shell. The shell sets window.claudiaNative
// before the page loads, and when iOS gives it an APNs token it sets claudiaNative.pushToken and
// fires "claudia-push-token" (before or after this runs, so both are read). The token goes to the
// hub under the signed-in person (the page's own cookies). In a browser nothing happens.
type PushToken = { token: string; env: string; topic: string };

declare global {
  interface Window {
    claudiaNative?: { platform: string; version?: string; pushToken?: PushToken };
  }
}

const RETRY_MS = 30_000;
const TRIES = 10;
let latest: PushToken | null = null;
let sent = "";

function onToken(t: PushToken | undefined) {
  if (!t || typeof t.token !== "string" || !t.token) return;
  latest = t;
  void register(t, 0);
}

async function register(t: PushToken, tries: number) {
  const key = `${t.token}|${t.env}|${t.topic}`;
  if (key === sent) return;
  sent = key;
  const r = await fetch("/hub/push/devices", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ token: t.token, env: t.env, topic: t.topic }),
  }).catch(() => null);
  if (r?.ok) return;
  // The hub unreachable or failing: again in a while (unless a newer token came). Refused (not the
  // hub, not signed in, a bad token): not again until the app starts again.
  if ((!r || r.status >= 500) && tries + 1 < TRIES) {
    sent = "";
    setTimeout(() => latest === t && void register(t, tries + 1), RETRY_MS);
  }
}

if (window.claudiaNative) {
  window.addEventListener("claudia-push-token", (e) => onToken((e as CustomEvent<PushToken>).detail));
  onToken(window.claudiaNative.pushToken);
}

export {};
