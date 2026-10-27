// The editor form (src/forms.ts): code Claude and the user both edit. Each file keeps Claude's code
// (code, its version v) and the user's unsent text (edit, its revision rev). The page saves the user's
// typing here; Send turns it into a chat message; Claude's draw may not replace an unsent edit.
import { createTwoFilesPatch } from "diff";

export type EditorFile = {
  id: string;
  name?: string;
  lang?: string;
  code?: string;
  // Claudia's own fields: Claude's version (counts each new code), the user's unsent text and its
  // revision (counts every save, never reset, so an old seen can't match a newer edit).
  v?: number;
  edit?: string;
  rev?: number;
  // Claude's: the revision its code merges (after a refusal); not stored.
  seen?: number;
};

const files = (data: Record<string, unknown> | undefined): EditorFile[] => (Array.isArray(data?.files) ? (data.files as EditorFile[]) : []);
const pending = (f: EditorFile | undefined): f is EditorFile & { edit: string } => typeof f?.edit === "string";

// Claude's draw into an editor block: files whose code would replace an unsent edit lose their code
// (other fields still apply) and come back as a refusal with the user's code; accepted code gets a
// new version and clears the edit. Claude can't set v, edit or rev.
export function guardDraw(old: Record<string, unknown> | undefined, data: Record<string, unknown>): { data: Record<string, unknown>; refused: string[] } {
  const had = new Map(files(old).map((f) => [f.id, f]));
  const refused: string[] = [];
  const out = files(data).map((given) => {
    const { v: _v, edit: _e, rev: _r, seen, ...f } = given;
    const before = had.get(f.id);
    if (typeof f.code !== "string") return f;
    if (pending(before) && seen !== before.rev) {
      if (f.code === before.edit) return { ...f, v: (before.v ?? 0) + 1, edit: undefined };
      refused.push(`File "${f.id}"${before.name ? ` (${before.name})` : ""}: the user has unsent edits (revision ${before.rev}); your code was not drawn. Their code now:\n\`\`\`\n${before.edit}\n\`\`\`\nMerge your change into it and draw this file again with seen: ${before.rev}.`);
      const { code: _c, ...rest } = f;
      return rest;
    }
    if (before && f.code === before.code && !pending(before)) return f;
    return { ...f, v: (before?.v ?? 0) + 1, edit: undefined };
  });
  return { data: Array.isArray(data.files) ? { ...data, files: out } : data, refused };
}

// The user's typing, saved: base is the version of Claude's code the page had. A save made on an
// older version (Claude's new code arrived meanwhile) is dropped: null.
export function userEdit(data: Record<string, unknown>, file: string, text: string, base: number): Record<string, unknown> | null {
  const list = files(data);
  const f = list.find((x) => x.id === file);
  if (!f || (f.v ?? 0) !== base) return null;
  const next: EditorFile = { ...f, edit: text === (f.code ?? "") ? undefined : text, rev: (f.rev ?? 0) + 1 };
  return { ...data, files: list.map((x) => (x.id === file ? next : x)) };
}

const lineCount = (patch: string, sign: "+" | "-") => patch.split("\n").filter((l) => l.startsWith(sign) && !l.startsWith(sign.repeat(3))).length;
const attr = (s: string) => s.replace(/&/g, "&amp;").replace(/"/g, "&quot;");

// Send: every file with an unsent edit, as one message (the user's note first, then a <claudia-code>
// per file with the diff against Claude's version and the full code). Each sent file's code becomes
// what was sent (Claude has seen it now); its version stays, so the page's next save still fits.
export function sendCode(block: string, data: Record<string, unknown>, note: string): { text: string; data: Record<string, unknown> } | null {
  const list = files(data);
  const sent = list.filter(pending);
  if (!sent.length) return null;
  const parts = sent.map((f) => {
    const name = f.name ?? f.id;
    const patch = createTwoFilesPatch(`${name} (yours)`, `${name} (the user's)`, f.code ?? "", f.edit, undefined, undefined, { context: 3 })
      .split("\n").filter((l) => !/^=+$/.test(l)).join("\n").trimEnd();
    return `<claudia-code block="${attr(block)}" file="${attr(f.id)}" name="${attr(name)}" added="${lineCount(patch, "+")}" removed="${lineCount(patch, "-")}">\n<diff>\n${patch}\n</diff>\n<code>\n${f.edit}\n</code>\n</claudia-code>`;
  });
  const text = [note.trim(), ...parts].filter(Boolean).join("\n\n");
  return { text, data: { ...data, files: list.map((f) => (pending(f) ? { ...f, code: f.edit, edit: undefined } : f)) } };
}
