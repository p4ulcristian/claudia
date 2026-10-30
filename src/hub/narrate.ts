// The hub's narration: one voice for every server. The voice service's address and token come from the
// hub's environment (HUB_TTS_URL, HUB_TTS_TOKEN: set by the gate's deploy, kept in its secrets); clips
// are kept in the hub's data by their text, so a story replays without asking again.
import { join } from "node:path";
import { narrator, type Config } from "../narrator";
import { HUB_DATA } from "./store";

async function load(): Promise<Config | null> {
  const url = process.env.HUB_TTS_URL, token = process.env.HUB_TTS_TOKEN;
  return url && token ? { url: url.replace(/\/$/, ""), token } : null;
}

const n = narrator(load, join(HUB_DATA, "narration"));
export const hubNarrate = n.narrate;
export const hubVoice = n.configured;
