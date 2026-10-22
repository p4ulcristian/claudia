// Predefined forms for the picture: each a block kind with known fields, drawn by its own chalk
// renderer (web/forms.tsx). Claude's draw tool and prompt are built from this list, and the forms
// gallery shows each with its sample, so a new form is one entry here plus its renderer.
//
// A form's data is merged field by field: lists of items with an id are upserted by id (send only
// the items that changed), anything else is replaced.

export const STATUSES = ["todo", "running", "done", "failed", "skipped"] as const;

export type FormSpec = {
  label: string;
  when: string; // when Claude should reach for it
  fields: string; // its data, in one line, for the tool description
  sample: Record<string, unknown>;
};

export const FORMS = {
  pipeline: {
    label: "Pipeline",
    when: "Steps that run in order, each with a status: a deploy, CI, a migration, a multi-step job. Update one stage as it moves.",
    fields: `stages: [{ id, label, status: ${STATUSES.join("|")}, note? }] in order`,
    sample: {
      stages: [
        { id: "build", label: "Build", status: "done", note: "41 s" },
        { id: "test", label: "Test", status: "done", note: "212 passed" },
        { id: "deploy", label: "Deploy", status: "running", note: "stage" },
        { id: "verify", label: "Verify", status: "todo" },
      ],
    },
  },
  timeline: {
    label: "Timeline",
    when: "What happened when, or what is planned by date: an incident, a history, a schedule.",
    fields: `events: [{ id, at (a short time or date label), label, note?, status?: ${STATUSES.join("|")}, color? }] in time order`,
    sample: {
      events: [
        { id: "a", at: "09:12", label: "Alert fired", note: "p95 over 2 s", color: "red" },
        { id: "b", at: "09:20", label: "Rolled back", status: "done" },
        { id: "c", at: "09:31", label: "Root cause found", note: "N+1 query in orders" },
        { id: "d", at: "tomorrow", label: "Fix ships", status: "todo" },
      ],
    },
  },
  compare: {
    label: "Compare",
    when: "Options side by side with their pros and cons, and the one picked (or recommended) marked.",
    fields: "options: [{ id, label, pros?: string[], cons?: string[], note?, picked?: boolean }]",
    sample: {
      options: [
        { id: "pwa", label: "Home Screen app", pros: ["minutes to ship", "updates instantly"], cons: ["Safari underneath"], picked: true },
        { id: "native", label: "Native wrapper", pros: ["native extras later"], cons: ["hours of work", "TestFlight per update"] },
      ],
    },
  },
  chart: {
    label: "Chart",
    when: "Numbers to see as a shape: trends, comparisons, distributions. Bar, line, area or point.",
    fields: "mark: bar|line|area|point, x: field name, y: field name, color?: field name (one series per value), title?, rows: [{ id, <x>: …, <y>: …, … }]",
    sample: {
      mark: "bar",
      x: "hour",
      y: "turns",
      rows: [
        { id: "0", hour: "0h", turns: 12 },
        { id: "1", hour: "1h", turns: 19 },
        { id: "2", hour: "2h", turns: 15 },
        { id: "3", hour: "3h", turns: 26 },
        { id: "4", hour: "4h", turns: 14 },
      ],
    },
  },
  stats: {
    label: "Stats",
    when: "A few numbers that matter right now, big: usage, counts, durations, a progress.",
    fields: "tiles: [{ id, label, value, note?, progress?: 0..1, color? }]",
    sample: {
      tiles: [
        { id: "five", label: "5-hour used", value: "85%", progress: 0.85, color: "amber" },
        { id: "week", label: "Week used", value: "15%", progress: 0.15 },
        { id: "reset", label: "Resets in", value: "34m" },
      ],
    },
  },
  code: {
    label: "Code",
    when: "Code when the code is the point: the change you made, the function at fault, a snippet to talk about, or a whole file with the parts that matter marked. Coloured like VS Code. Mark the lines that matter; pin a short note to a line instead of explaining it in the chat. To show a whole file, send file and no code: Claudia reads it itself (no need to send its text) and folds it down to the marked and noted lines, which the user can unfold.",
    fields: "file? (its path, relative to the working folder; without code the whole file is read), line? (number of the first line of code, default 1), lang? (else taken from the file name), code? (the lines as one string; with diff: true each line starts with '+', '-' or ' '), diff?: boolean, mark?: [line numbers or \"a-b\" ranges], notes?: [{ id, line, text, color? }] pinned under that line. Later calls send only what changed (a new note, other marks); sending file again without code reads it again",
    sample: {
      file: "src/orders/total.ts",
      line: 12,
      diff: true,
      code: [
        " export function orderTotal(items: Item[]) {",
        "-  return items.reduce((sum, i) => sum + i.price, 0);",
        "+  return items.reduce((sum, i) => sum + i.price * i.qty, 0);",
        " }",
      ].join("\n"),
      notes: [{ id: "qty", line: 13, text: "quantity was ignored: 3 coats billed as 1", color: "amber" }],
    },
  },
  files: {
    label: "Files",
    when: "A project's files as a tree the user can browse and open: where things live, what a change touched, which files matter. Read live from disk (git status shown), so it stays current; you send only the folder and what to point out. For files that don't exist yet (a planned layout, a sketch of a library), send sketch: alone it is a made-up tree; with root it adds planned files to the real one. Each is labelled for the user (on disk / sketch / planned), so never present made-up files as real.",
    fields: "root? (a folder: absolute, ~/…, or relative to the working folder; default the working folder), open?: [folders relative to root to show unfolded], file? (a file relative to root to show open, whole), marks?: [{ id, path (relative to root), note?, color? }] files or folders to point out (their folders unfold). Planned files: sketch: [{ path (relative to root when root is given), code?, status? }] (folders come from the paths), title? (what a root-less sketch is); open and marks work the same",
    sample: {
      demo: [
        { path: "src", dir: true },
        { path: "src/orders", dir: true },
        { path: "src/orders/total.ts", status: "M" },
        { path: "src/orders/total.test.ts", status: "U" },
        { path: "src/shops.ts" },
        { path: "README.md" },
      ],
      marks: [{ id: "t", path: "src/orders/total.ts", note: "the fix", color: "amber" }],
    },
  },
} satisfies Record<string, FormSpec>;

export type FormName = keyof typeof FORMS;
export const FORM_NAMES = Object.keys(FORMS) as FormName[];

// data merged into a form's current data (see the top of this file); remove = item ids to drop.
export function mergeForm(prev: Record<string, unknown>, data: Record<string, unknown> | undefined, remove: string[] = []): Record<string, unknown> {
  const out: Record<string, unknown> = { ...prev };
  for (const [k, v] of Object.entries(data ?? {})) {
    const old = out[k];
    const items = (x: unknown): x is { id: string }[] => Array.isArray(x) && x.every((i) => i && typeof i === "object" && "id" in i);
    if (items(v) && items(old)) {
      const list = [...old];
      for (const it of v) {
        const i = list.findIndex((x) => x.id === it.id);
        if (i >= 0) list[i] = { ...list[i], ...it };
        else list.push(it);
      }
      out[k] = list;
    } else out[k] = v;
  }
  if (remove.length) {
    const gone = new Set(remove);
    for (const [k, v] of Object.entries(out)) if (Array.isArray(v)) out[k] = v.filter((x) => !(x && typeof x === "object" && gone.has((x as { id?: string }).id ?? "")));
  }
  return out;
}

// The forms part of Claude's draw tool description (in the tool, not the system prompt, so a running
// chat gets new forms when the tools are swapped in).
export function formsHelp(names: readonly string[] = FORM_NAMES): string {
  return (names as FormName[]).map((n) => `- ${n}: ${FORMS[n].when} Data: ${FORMS[n].fields}.`).join("\n");
}
