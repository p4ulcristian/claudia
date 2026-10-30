// Narration: a line of text becomes speech through the profile's own voice service, and is kept on
// disk by the text, so a story replays without asking again. Set up per profile in
// ~/.config/claudia/tts.json: { "url": "https://…", "token": "…" } (the service's speech route is
// <url>/v1/audio/speech: form fields text and instruction, answers raw 16-bit mono PCM at 24 kHz).
import { createHash } from "node:crypto";
import { homedir } from "node:os";
import { join } from "node:path";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { DATA_DIR } from "./db";

const CONFIG = join(homedir(), ".config/claudia/tts.json");
const CACHE = join(DATA_DIR, "narration");
const MAX_CHARS = 1200;
const DEFAULT_VOICE = "a calm, warm woman, clear and unhurried";
const BYTES_PER_SECOND = 24000 * 2;

type Config = { url: string; token: string };

async function config(): Promise<Config | null> {
  try {
    const c = JSON.parse(await readFile(CONFIG, "utf8"));
    return typeof c.url === "string" && typeof c.token === "string" ? { url: c.url.replace(/\/$/, ""), token: c.token } : null;
  } catch {
    return null;
  }
}

export const voiceConfigured = async () => !!(await config());

// The service makes one clip at a time (and limits requests a minute): ours wait in line.
let line: Promise<unknown> = Promise.resolve();
const pending = new Map<string, Promise<Uint8Array>>();

export function narrate(text: string, instruction?: string): Promise<{ pcm: Uint8Array; seconds: number }> {
  const clean = text.trim().slice(0, MAX_CHARS);
  if (!clean) return Promise.reject(new Error("nothing to say"));
  const voice = (instruction ?? "").trim().slice(0, 200) || DEFAULT_VOICE;
  const key = createHash("sha256").update(`${voice}\n${clean}`).digest("hex");
  let p = pending.get(key);
  if (!p) {
    p = (async () => {
      const file = join(CACHE, `${key}.pcm`);
      const kept = await readFile(file).catch(() => null);
      if (kept) return new Uint8Array(kept);
      const next = line.then(async () => {
        const c = await config();
        if (!c) throw new Error("no voice set up for this profile (~/.config/claudia/tts.json)");
        const form = new FormData();
        form.set("text", clean);
        form.set("instruction", voice);
        const r = await fetch(`${c.url}/v1/audio/speech`, { method: "POST", headers: { Authorization: `Bearer ${c.token}` }, body: form, signal: AbortSignal.timeout(120_000) });
        if (!r.ok) throw new Error(`the voice answered ${r.status}`);
        const pcm = new Uint8Array(await r.arrayBuffer());
        if (pcm.length < 2) throw new Error("the voice answered with no sound");
        await mkdir(CACHE, { recursive: true });
        await writeFile(file, pcm);
        return pcm;
      });
      line = next.catch(() => {});
      return next;
    })().finally(() => pending.delete(key));
    pending.set(key, p);
  }
  return p.then((pcm) => ({ pcm, seconds: pcm.length / BYTES_PER_SECOND }));
}
