// Where the files the user attaches to a message are kept (src/attached.ts): one folder per chat in
// the runner's data folder, the file under the name it had (a "-2" before its extension when that
// name is taken). They stay as long as the chat: its messages point at them.
import { mkdir, open, stat, unlink } from "node:fs/promises";
import { basename, extname, join } from "node:path";
import { DATA_DIR } from "./db";
import { MAX_FILE_BYTES, type SentFile } from "./attached";

const ROOT = join(DATA_DIR, "uploads");
export const uploadsDir = (chat: string) => join(ROOT, chat);

// A name that is one file in that folder: no folders, no control characters, not too long.
export function safeName(name: string) {
  const s = basename(name.replace(/\\/g, "/")).replace(/[\u0000-\u001f\u007f]/g, "").trim();
  if (!s || s === "." || s === "..") return "file";
  if (s.length <= 200) return s;
  const ext = extname(s).slice(0, 20);
  return s.slice(0, 200 - ext.length) + ext;
}

export class TooBig extends Error {}

// Saves a body as the chat's file `name`; a body over MAX_FILE_BYTES is refused and nothing stays.
export async function saveUpload(chat: string, name: string, type: string, body: ReadableStream<Uint8Array>): Promise<SentFile> {
  const dir = uploadsDir(chat);
  await mkdir(dir, { recursive: true, mode: 0o700 });
  const want = safeName(name);
  const ext = extname(want);
  const stem = ext ? want.slice(0, -ext.length) : want;
  // "wx": the name is ours only if no other upload took it first.
  let path = "";
  let fh: Awaited<ReturnType<typeof open>> | null = null;
  for (let n = 1; !fh; n++) {
    path = join(dir, n === 1 ? want : `${stem}-${n}${ext}`);
    fh = await open(path, "wx", 0o600).catch((e) => {
      if (e?.code === "EEXIST" && n < 1000) return null;
      throw e;
    });
  }
  let size = 0;
  try {
    for await (const chunk of body as unknown as AsyncIterable<Uint8Array>) {
      size += chunk.byteLength;
      if (size > MAX_FILE_BYTES) throw new TooBig();
      await fh.write(chunk);
    }
    await fh.close();
  } catch (e) {
    await fh.close().catch(() => {});
    await unlink(path).catch(() => {});
    throw e;
  }
  return { path, name: basename(path), size, type: type || "application/octet-stream" };
}

// The chat's saved file `name`, or null.
export async function uploadPath(chat: string, name: string) {
  if (safeName(name) !== name) return null;
  const path = join(uploadsDir(chat), name);
  const st = await stat(path).catch(() => null);
  return st?.isFile() ? path : null;
}

// The files a message names that really are this chat's saved files (sizes read from disk).
export async function ownFiles(chat: string, files: unknown): Promise<SentFile[]> {
  if (!Array.isArray(files)) return [];
  const out: SentFile[] = [];
  for (const f of files.slice(0, 50)) {
    if (typeof f?.path !== "string") continue;
    const name = basename(f.path);
    const path = await uploadPath(chat, name);
    if (!path || path !== f.path) continue;
    const st = await stat(path);
    out.push({ path, name, size: st.size, type: typeof f.type === "string" && f.type.length < 200 ? f.type : "application/octet-stream" });
  }
  return out;
}
