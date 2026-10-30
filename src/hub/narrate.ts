// The hub's narration: one voice for every server. The voice service's address and token come from the
// hub's environment (HUB_TTS_URL, and the token in HUB_TTS_TOKEN_FILE or HUB_TTS_TOKEN; the gate's deploy
// sets them from its secrets, like the APNs key); clips
// are kept in the hub's data by their text, so a story replays without asking again.
import { readFile } from "node:fs/promises";
import { join } from "node:path";
import { narrator, type Config } from "../narrator";
import { HUB_DATA } from "./store";

async function load(): Promise<Config | null> {
  const url = process.env.HUB_TTS_URL;
  const file = process.env.HUB_TTS_TOKEN_FILE;
  const token = file ? (await readFile(file, "utf8").catch(() => "")).trim() : process.env.HUB_TTS_TOKEN;
  return url && token ? { url: url.replace(/\/$/, ""), token } : null;
}

const n = narrator(load, join(HUB_DATA, "narration"));
export const hubNarrate = n.narrate;
export const hubVoice = n.configured;
