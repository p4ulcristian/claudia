// The iPhone app (decisions 23, 24) is this page in a native shell. The shell sets
// window.claudiaNative before the page loads, and when iOS gives it an APNs token it sets
// claudiaNative.pushToken and fires "claudia-push-token" (before or after this runs, so both are
// read), again whenever a field changes: the key alert texts are sealed with, and ActivityKit's
// push-to-start token, which may come later. Each new value goes to the hub under the signed-in
// person (the page's own cookies). In a browser nothing happens.
type PushToken = { token: string; env: string; topic: string; key: string; liveStartToken?: string };

declare global {
  interface Window {
    claudiaNative?: { platform: string; version?: string; pushToken?: PushToken };
  }
}

const RETRY_MS = 30_000;
const TRIES = 10;
let latest: PushToken | null = null;
let sent = "";

const fields = (t: PushToken) => ({ token: t.token, env: t.env, topic: t.topic, key: t.key, ...(t.liveStartToken ? { liveStartToken: t.liveStartToken } : {}) });

function onToken(t: PushToken | undefined) {
  if (!t || typeof t.token !== "string" || !t.token) return;
  latest = t;
  void register(t, 0);
}

async function register(t: PushToken, tries: number) {
  const body = JSON.stringify(fields(t));
  if (body === sent) return;
  sent = body;
  const r = await fetch("/hub/push/devices", { method: "POST", headers: { "content-type": "application/json" }, body }).catch(() => null);
  if (r?.ok) return;
  // The hub unreachable or failing: again in a while (unless a newer token came). Refused (not the
  // hub, not signed in, a bad token or key): not again until a field changes or the app starts again.
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
