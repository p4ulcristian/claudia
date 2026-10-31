// Narration: a line of text becomes speech through a voice service, and is kept on disk by the text,
// so a story replays without asking again. Used by the hub (config from the environment: HUB_TTS_URL,
// HUB_TTS_TOKEN) and by a server's runner (src/narrate.ts: ~/.config/claudia/tts.json:
// { "url": "https://…", "token": "…" }). The service's speech route is
// <url>/v1/audio/speech (iris-voice: github.com/p4ulcristian/iris-voice): form fields text alone,
// spoken in the service's default voice; text and instruction; or text with ref_audio and ref_text
// to speak in a cloned voice. It answers raw 16-bit mono PCM at 24 kHz.
//
// The narrator is the service's default voice, unless a story asks for one (instruction) or the
// profile has its own recording. Asked for a voice only by a description, the service invents a
// slightly different person for every line, so a description makes ONE reference clip (kept on
// disk) and every line is spoken in a clone of it. A recording of your own voice goes in the config:
// { "reference": { "audio": "/path/clip.wav", "text": "the exact words in the clip" } }.
import { createHash } from "node:crypto";
import { join } from "node:path";
import { mkdir, readFile, writeFile } from "node:fs/promises";

const MAX_CHARS = 1200;
const BYTES_PER_SECOND = 24000 * 2;

export type Config = { url: string; token: string; reference?: { audio: string; text: string } };


// The service makes one clip at a time (and limits requests a minute): ours wait in line.
let line: Promise<unknown> = Promise.resolve();
const pending = new Map<string, Promise<Uint8Array>>();

const REFERENCE_TEXT = "Hello. I am going to walk you through this one part at a time. Take your time, and ask me about anything you see.";

// raw 16-bit mono PCM at 24 kHz as a WAV file: the 44-byte RIFF header, then the samples
function toWav(pcm: Uint8Array): Uint8Array {
  const out = new Uint8Array(44 + pcm.length);
  const v = new DataView(out.buffer);
  const tag = (at: number, t: string) => { for (let i = 0; i < 4; i++) out[at + i] = t.charCodeAt(i); };
  tag(0, "RIFF"); v.setUint32(4, 36 + pcm.length, true); tag(8, "WAVE"); tag(12, "fmt ");
  v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
  v.setUint32(24, 24000, true); v.setUint32(28, 48000, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true);
  tag(36, "data"); v.setUint32(40, pcm.length, true);
  out.set(pcm, 44);
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
async function reference(c: Config, cache: string, voice: string): Promise<{ wav: Uint8Array; text: string }> {
  if (c.reference) return { wav: new Uint8Array(await readFile(c.reference.audio)), text: c.reference.text };
  const key = createHash("sha256").update(`ref1\n${voice}\n${REFERENCE_TEXT}`).digest("hex").slice(0, 24);
  const file = join(cache, `narrator-${key}.wav`);
  const kept = await readFile(file).catch(() => null);
  if (kept) return { wav: new Uint8Array(kept), text: REFERENCE_TEXT };
  const form = new FormData();
  form.set("text", REFERENCE_TEXT);
  form.set("instruction", voice);
  const wav = toWav(await speak(c, form));
  await mkdir(cache, { recursive: true });
  await writeFile(file, wav);
  return { wav, text: REFERENCE_TEXT };
}

export function narrator(load: () => Promise<Config | null>, cache: string) {
  return {
    configured: async () => !!(await load()),
    narrate: async (text: string, instruction?: string): Promise<{ pcm: Uint8Array; seconds: number }> => {
  const clean = text.trim().slice(0, MAX_CHARS);
  if (!clean) throw new Error("nothing to say");
  const c = await load();
  if (!c) throw new Error("no voice set up for this profile (~/.config/claudia/tts.json)");
  const voice = (instruction ?? "").trim().slice(0, 200);
  // the narrator: the profile's own recording, a story's described voice (its one clip), or the service's default voice
  const who = c.reference ? `own:${c.reference.audio}\n${c.reference.text}` : voice ? `made:${voice}` : `default:${c.url}`;
  const key = createHash("sha256").update(`clone1\n${who}\n${clean}`).digest("hex");
  let p = pending.get(key);
  if (!p) {
    p = (async () => {
      const file = join(cache, `${key}.pcm`);
      const kept = await readFile(file).catch(() => null);
      if (kept) return new Uint8Array(kept);
      const next = line.then(async () => {
        const form = new FormData();
        form.set("text", clean);
        if (c.reference || voice) {
          const ref = await reference(c, cache, voice);
          form.set("ref_audio", new Blob([Buffer.from(ref.wav)], { type: "audio/wav" }), "narrator.wav");
          form.set("ref_text", ref.text);
        }
        const pcm = await speak(c, form);
        await mkdir(cache, { recursive: true });
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
  };
}
