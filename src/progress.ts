// A long job's progress, read from its output file: the last line `##progress <done>/<total> [label]`
// (bin/claudia-progress writes them; any script can). No imports, so a test can load it alone.
import { open } from "node:fs/promises";

export type Progress = { done: number; total: number; label: string };

const LINE = /^##progress (\d+)\/(\d+)(?: (.*))?$/;

// The last progress line of `text` (a carriage return also ends a line, so a bar redrawn in place counts).
export function lastProgress(text: string): Progress | null {
  const lines = text.split(/[\r\n]+/);
  for (let i = lines.length - 1; i >= 0; i--) {
    const m = LINE.exec(lines[i]!);
    if (!m) continue;
    const total = Number(m[2]);
    if (!total) return null;
    return { done: Math.min(Number(m[1]), total), total, label: (m[3] ?? "").slice(0, 80) };
  }
  return null;
}

// Progress from the end of a task's output file (the last 16 KB), or null: no file, no line.
export async function readProgress(file: string | undefined): Promise<Progress | null> {
  if (!file) return null;
  try {
    const f = await open(file, "r");
    try {
      const { size } = await f.stat();
      const len = Math.min(size, 16_000);
      const buf = Buffer.alloc(len);
      await f.read(buf, 0, len, size - len);
      return lastProgress(buf.toString("utf8"));
    } finally {
      await f.close();
    }
  } catch {
    return null;
  }
}
