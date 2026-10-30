// A server's narration: the profile's voice service from ~/.config/claudia/tts.json
// ({ "url", "token", optional "reference": { "audio", "text" } }), clips kept in the profile's data.
import { homedir } from "node:os";
import { join } from "node:path";
import { readFile } from "node:fs/promises";
import { DATA_DIR } from "./db";
import { narrator, type Config } from "./narrator";

const CONFIG = join(homedir(), ".config/claudia/tts.json");

async function load(): Promise<Config | null> {
  try {
    const c = JSON.parse(await readFile(CONFIG, "utf8"));
    if (typeof c.url !== "string" || typeof c.token !== "string") return null;
    const r = c.reference;
    return { url: c.url.replace(/\/$/, ""), token: c.token, reference: r && typeof r.audio === "string" && typeof r.text === "string" ? { audio: r.audio, text: r.text } : undefined };
  } catch {
    return null;
  }
}

const n = narrator(load, join(DATA_DIR, "narration"));
export const narrate = n.narrate;
export const voiceConfigured = n.configured;
