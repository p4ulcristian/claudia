// Apple push (APNs) from the hub (decisions 23, 24): an alert, a badge or a Live Activity push to one
// phone, over HTTP/2, signed with the
// developer account's APNs key (token auth: an ES256 JWT made by jose, reused for 50 minutes; Apple
// refuses one older than an hour and too-frequent new ones). Development builds' tokens go to the
// sandbox, production builds' to Apple's main address.
import { connect, type ClientHttp2Session } from "node:http2";
import { readFileSync } from "node:fs";
import { SignJWT, importPKCS8 } from "jose";

export const APPLE = { development: "https://api.sandbox.push.apple.com", production: "https://api.push.apple.com" } as const;
export type ApnsEnv = keyof typeof APPLE;
export type ApnsConfig = { keyFile: string; keyId: string; teamId: string };
// token: the device token, or for a Live Activity its push-to-start or update token (topic then
// <bundle id>.push-type.liveactivity).
export type PushDevice = { token: string; env: ApnsEnv; topic: string };
// alert (also badge-only) or liveactivity; priority 10 = now, 5 = when it suits the phone's power.
export type PushKind = { type: "alert" | "liveactivity"; priority: 5 | 10 };
const ALERT: PushKind = { type: "alert", priority: 10 };
// ok: delivered to Apple. gone: the token is dead (drop it). retry: worth trying again later.
// failed: Apple refused it for another reason. dry: logged, not sent.
export type Sent = { status: "ok" | "gone" | "retry" | "failed" | "dry"; detail?: string };
export type Apns = { send: (d: PushDevice, payload: object, kind?: PushKind) => Promise<Sent> };

const JWT_MS = 50 * 60_000;
const REQUEST_MS = 15_000;

// A device token in a log: its start only.
export const shortToken = (token: string) => `${token.slice(0, 6)}…`;

// The key's settings, from the environment where the hub runs. All three, or no push (and why).
export function apnsConfig(env: Record<string, string | undefined> = process.env): { config: ApnsConfig } | { off: string } {
  const config = { keyFile: env.HUB_APNS_KEY_FILE?.trim() ?? "", keyId: env.HUB_APNS_KEY_ID?.trim() ?? "", teamId: env.HUB_APNS_TEAM_ID?.trim() ?? "" };
  const missing = [["HUB_APNS_KEY_FILE", config.keyFile], ["HUB_APNS_KEY_ID", config.keyId], ["HUB_APNS_TEAM_ID", config.teamId]].filter(([, v]) => !v).map(([k]) => k);
  return missing.length ? { off: `${missing.join(", ")} not set` } : { config };
}

// Reads the key now, so a bad key file stops push at the start rather than at the first push.
// origins: where each APNs environment is (Apple's; a test passes its own).
export async function createApns(config: ApnsConfig, origins: Record<ApnsEnv, string> = APPLE): Promise<Apns> {
  const key = await importPKCS8(readFileSync(config.keyFile, "utf8"), "ES256");
  let jwt = { token: "", at: 0 };
  const sessions = new Map<string, ClientHttp2Session>();

  async function bearer() {
    if (jwt.token && Date.now() - jwt.at < JWT_MS) return jwt.token;
    const token = await new SignJWT({}).setProtectedHeader({ alg: "ES256", kid: config.keyId }).setIssuer(config.teamId).setIssuedAt().sign(key);
    jwt = { token, at: Date.now() };
    return token;
  }

  // One HTTP/2 connection per APNs address, kept open (Apple asks for that) and made again when it drops.
  function session(origin: string) {
    const have = sessions.get(origin);
    if (have && !have.closed && !have.destroyed) return have;
    const s = connect(origin);
    s.on("error", (err) => console.error(`push: APNs connection: ${err.message}`));
    s.on("goaway", () => s.close());
    s.on("close", () => sessions.get(origin) === s && sessions.delete(origin));
    s.setTimeout(30 * 60_000, () => s.close());
    sessions.set(origin, s);
    return s;
  }

  function post(d: PushDevice, body: string, auth: string, kind: PushKind) {
    return new Promise<{ status: number; body: string }>((resolve, reject) => {
      const req = session(origins[d.env]).request({
        ":method": "POST",
        ":path": `/3/device/${d.token}`,
        authorization: `bearer ${auth}`,
        "apns-topic": d.topic,
        "apns-push-type": kind.type,
        "apns-priority": String(kind.priority),
        "content-type": "application/json",
      });
      let status = 0;
      let text = "";
      req.setEncoding("utf8");
      req.on("response", (h) => (status = Number(h[":status"])));
      req.on("data", (c: string) => (text += c));
      req.on("end", () => resolve({ status, body: text }));
      // (closed by the timeout or a dropped connection, with no answer: status 0, tried again)
      req.on("close", () => resolve({ status, body: text }));
      req.on("error", reject);
      req.setTimeout(REQUEST_MS, () => req.close());
      req.end(body);
    });
  }

  return {
    async send(d, payload, kind = ALERT) {
      let r: { status: number; body: string };
      try {
        r = await post(d, JSON.stringify(payload), await bearer(), kind);
      } catch (err) {
        return { status: "retry", detail: (err as Error).message };
      }
      if (r.status === 200) return { status: "ok" };
      let reason = "";
      try {
        reason = String((JSON.parse(r.body) as { reason?: unknown }).reason ?? "");
      } catch {}
      const detail = `${r.status} ${reason}`.trim();
      if (r.status === 410 || reason === "BadDeviceToken" || reason === "Unregistered") return { status: "gone", detail };
      // A refused provider token: make a new one for the next try.
      if (r.status === 403 && /ProviderToken/.test(reason)) {
        jwt = { token: "", at: 0 };
        return { status: reason === "ExpiredProviderToken" ? "retry" : "failed", detail };
      }
      if (r.status === 429 || r.status >= 500 || r.status === 0) return { status: "retry", detail };
      return { status: "failed", detail };
    },
  };
}

// Pushes written to the log instead of sent (HUB_APNS_DRY_RUN=1): to see what would go out, with no
// key. Chat titles in a Live Activity's state show as their length only: no chat text in logs.
export const dryRun: Apns = {
  async send(d, payload, kind = ALERT) {
    const shown = JSON.stringify(payload, (k, v) => (k === "title" && typeof v === "string" && v !== "Claudia" ? `‹${Array.from(v).length} chars›` : v));
    console.log(`push (dry run, ${kind.type} p${kind.priority}) to ${shortToken(d.token)} (${d.env}, ${d.topic}): ${shown}`);
    return { status: "dry" };
  },
};
