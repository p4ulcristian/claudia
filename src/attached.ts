// Files the user attaches to a message that go to Claude as files, not images (anything but a photo
// or a video, which web/media.ts turns into images): the runner saves each one in the chat's uploads
// folder (src/uploads.ts) and the message carries one <claudia-file> tag per file after the user's
// text; Claude reads the file at its path. The page shows the tags as file chips. (Its own file
// without Node imports: the page and the runner both read it.)

export type SentFile = { path: string; name: string; size: number; type: string };

const attr = (s: string) => s.replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/\n/g, " ");
const unattr = (s: string) => s.replace(/&quot;/g, '"').replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&amp;/g, "&");

export function fileTag(f: SentFile) {
  return `<claudia-file path="${attr(f.path)}" name="${attr(f.name)}" size="${f.size}" type="${attr(f.type)}">The user attached this file; it is saved at the path.</claudia-file>`;
}

// The user's text with the files after it.
export function withFiles(text: string, files: SentFile[]) {
  if (!files.length) return text;
  return [text, ...files.map(fileTag)].filter(Boolean).join("\n\n");
}

const TAG = /\n*<claudia-file ([^>]*)>[^<]*<\/claudia-file>/g;
const attrOf = (attrs: string, k: string) => unattr(attrs.match(new RegExp(`\\b${k}="([^"]*)"`))?.[1] ?? "");

// A sent message split back into the user's text and its files.
export function sentFiles(text: string): { text: string; files: SentFile[] } {
  if (!text.includes("<claudia-file ")) return { text, files: [] };
  const files: SentFile[] = [];
  const rest = text.replace(TAG, (_, attrs: string) => {
    files.push({ path: attrOf(attrs, "path"), name: attrOf(attrs, "name"), size: Number(attrOf(attrs, "size")) || 0, type: attrOf(attrs, "type") });
    return "";
  }).trim();
  return { text: rest, files };
}

// The largest file the runner takes (the servers' Bun takes bodies up to 128 MB).
export const MAX_FILE_BYTES = 100 * 1024 * 1024;

export function sizeLabel(n: number) {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${Math.round(n / 1024)} KB`;
  return `${(n / 1024 / 1024).toFixed(n < 10 * 1024 * 1024 ? 1 : 0)} MB`;
}
