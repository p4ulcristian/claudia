// Narration: a line of text becomes speech through the profile's own voice service, and is kept on
// disk by the text, so a story replays without asking again. Set up per profile in
// ~/.config/claudia/tts.json: { "url": "https://…", "token": "…" } (the service's speech route is
// <url>/v1/audio/speech: form fields text and instruction, or text with ref_audio and ref_text to speak
// in a cloned voice; it answers raw 16-bit mono PCM at 24 kHz).
//
// One narrator: asked for a voice only by a description, the service invents a slightly different
// person for every line. So the description makes ONE reference clip (kept on disk), and every line
// is spoken in a clone of it. To use a recording of your own voice instead, put it in the config:
// { "reference": { "audio": "/path/clip.wav", "text": "the exact words in the clip" } }.
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

type Config = { url: string; token: string; reference?: { audio: string; text: string } };

async function config(): Promise<Config | null> {
  try {
    const c = JSON.parse(await readFile(CONFIG, "utf8"));
    if (typeof c.url !== "string" || typeof c.token !== "string") return null;
    const r = c.reference;
    return { url: c.url.replace(/\/$/, ""), token: c.token, reference: r && typeof r.audio === "string" && typeof r.text === "string" ? { audio: r.audio, text: r.text } : undefined };
  } catch {
    return null;
  }
}

export const voiceConfigured = async () => !!(await config());

// The service makes one clip at a time (and limits requests a minute): ours wait in line.
let line: Promise<unknown> = Promise.resolve();
const pending = new Map<string, Promise<Uint8Array>>();

const REFERENCE_TEXT = "Hello. I am going to walk you through this one part at a time. Take your time, and ask me about anything you see.";

// raw PCM to a WAV file with ffmpeg
async function toWav(pcm: Uint8Array): Promise<Uint8Array> {
  const p = Bun.spawn(["ffmpeg", "-loglevel", "error", "-f", "s16le", "-ar", "24000", "-ac", "1", "-i", "pipe:0", "-f", "wav", "pipe:1"], { stdin: Buffer.from(pcm), stdout: "pipe", stderr: "pipe" });
  const out = new Uint8Array(await new Response(p.stdout).arrayBuffer());
  if ((await p.exited) !== 0 || out.length < 100) throw new Error(`ffmpeg could not make the reference clip: ${await new Response(p.stderr).text()}`);
  return out;
}

async function speak(c: Config, form: FormData): Promise<Uint8Array> {
  const r = await fetch(`${c.url}/v1/audio/speech`, { method: "POST", headers: { Authorization: `Bearer ${c.token}` }, body: form, signal: AbortSignal.timeout(120_000) });
  if (!r.ok) throw new Error(`the voice answered ${r.status}`);
  const pcm = new Uint8Array(await r.arrayBuffer());
  if (pcm.length < 2) throw new Error("the voice answered with no sound");
  return pcm;
}

// The narrator's reference clip: the profile's own recording, or one made once from the description.
async function reference(c: Config, voice: string): Promise<{ wav: Uint8Array; text: string }> {
  if (c.reference) return { wav: new Uint8Array(await readFile(c.reference.audio)), text: c.reference.text };
  const key = createHash("sha256").update(`ref1\n${voice}\n${REFERENCE_TEXT}`).digest("hex").slice(0, 24);
  const file = join(CACHE, `narrator-${key}.wav`);
  const kept = await readFile(file).catch(() => null);
  if (kept) return { wav: new Uint8Array(kept), text: REFERENCE_TEXT };
  const form = new FormData();
  form.set("text", REFERENCE_TEXT);
  form.set("instruction", voice);
  const wav = await toWav(await speak(c, form));
  await mkdir(CACHE, { recursive: true });
  await writeFile(file, wav);
  return { wav, text: REFERENCE_TEXT };
}

export async function narrate(text: string, instruction?: string): Promise<{ pcm: Uint8Array; seconds: number }> {
  const clean = text.trim().slice(0, MAX_CHARS);
  if (!clean) throw new Error("nothing to say");
  const c = await config();
  if (!c) throw new Error("no voice set up for this profile (~/.config/claudia/tts.json)");
  const voice = (instruction ?? "").trim().slice(0, 200) || DEFAULT_VOICE;
  // the narrator is the reference (own recording, or the description's one clip), then every line is a clone of it
  const who = c.reference ? `own:${c.reference.audio}\n${c.reference.text}` : `made:${voice}`;
  const key = createHash("sha256").update(`clone1\n${who}\n${clean}`).digest("hex");
  let p = pending.get(key);
  if (!p) {
    p = (async () => {
      const file = join(CACHE, `${key}.pcm`);
      const kept = await readFile(file).catch(() => null);
      if (kept) return new Uint8Array(kept);
      const next = line.then(async () => {
        const ref = await reference(c, voice);
        const form = new FormData();
        form.set("text", clean);
        form.set("ref_audio", new Blob([Buffer.from(ref.wav)], { type: "audio/wav" }), "narrator.wav");
        form.set("ref_text", ref.text);
        const pcm = await speak(c, form);
        await mkdir(CACHE, { recursive: true });
        await writeFile(file, pcm);
        return pcm;
      });
      line = next.catch(() => {});
      return next;
    })().finally(() => pending.delete(key));
    pending.set(key, p);
  }
  const pcm = await p;
  return { pcm, seconds: pcm.length / BYTES_PER_SECOND };
}
