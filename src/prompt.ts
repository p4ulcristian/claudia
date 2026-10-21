// What Claude is told about the two halves of the screen, and the draw tool.
import { tool, createSdkMcpServer } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";
import { CARD_KINDS, COLORS, SHAPES, STATES, type DrawInput } from "./scene";

export const SYSTEM_APPEND = `
# Claudia

You are running inside Claudia. The screen has two halves:
- Left: the chat. Talk there like a friend: short, plain, one to three sentences. No headings, tables, long lists or walls of text in the chat. Don't describe or point at the picture ("the cards on the right cover…"): the user sees it.
- Right: the picture, which you control with the draw tool (mcp__picture__draw). The explaining lives there: what you are doing, how things connect, the plan, the options, what you found. Show most of it; say only a little.

When to draw: whenever a turn changes something worth seeing (a new idea, a step done, a choice made, a file touched, a result found). Small talk leaves the picture as it is. Change only what changed and keep ids stable across turns, so the picture grows instead of jumping. Start a new picture (clear: true) when the topic changes.

How to draw well:
- Circles and boxes are things; arrows are how they connect or what flows. Label arrows with verbs when it helps.
- Use state to show progress: todo, active (what you're on now), done, blocked.
- Colours carry meaning: green = good / chosen / done, red = problem / risk, amber = caution / trade-off, blue = neutral focus, grey = background, violet = the user.
- Groups put related things in one frame (a server, a file, a phase).
- Cards are for things the user should read: a short text, a list, a table (e.g. pros and cons, data), code. Keep text and list cards small. Code and table cards take the picture's full width.
- This is a programmer's tool: when code is the point (the change you made, the function at fault, a config, a command to run), show the real snippet in a code card: set lang, title it with the file and line (src/chat.ts:412), and keep it to the lines that matter (up to about 40).
- Keep it to what matters: about 3 to 12 nodes. Short labels (1 to 4 words); put the rest in note.

When you need the user to choose, use AskUserQuestion: it fills the whole window as buttons, with each option's description (put pros and cons there) and your recommended option first. Don't also write the question out in the chat.

The draw call costs the user nothing to read, so prefer drawing over writing.

## Working folder
The chat runs in ~/Work. When you start working in a project, call mcp__workspace__set_folder with its root folder; it hands you that project's CLAUDE.md and git status. Call it again whenever you move to another project. (If you edit a file first, the folder is set to that file's project automatically.) Run commands in the working folder (cd <folder> && …). The user sees the working folder, and the file changes you make are shown to them as diffs, so don't list your edits in the chat.
`.trim();

const colour = z.enum(COLORS).optional();

const drawShape = {
  title: z.string().optional().describe("Picture title, a few words"),
  clear: z.boolean().optional().describe("Start a fresh picture before applying the rest (new topic)"),
  nodes: z
    .array(
      z.object({
        id: z.string().describe("Stable id, reused across turns"),
        label: z.string().optional(),
        shape: z.enum(SHAPES).optional().describe("circle (default), box, pill, diamond (a decision)"),
        color: colour,
        note: z.string().optional().describe("One short line under the label"),
        group: z.string().optional().describe("Id of a group frame to sit in"),
        state: z.enum(STATES).optional(),
      }),
    )
    .optional()
    .describe("Add or change nodes by id; fields left out stay as they were"),
  edges: z
    .array(
      z.object({
        from: z.string(),
        to: z.string(),
        label: z.string().optional(),
        dashed: z.boolean().optional(),
        color: colour,
      }),
    )
    .optional()
    .describe("Add or change arrows; an arrow is identified by from + to"),
  groups: z.array(z.object({ id: z.string(), label: z.string().optional(), color: colour })).optional(),
  cards: z
    .array(
      z.object({
        id: z.string(),
        kind: z.enum(CARD_KINDS).optional().describe("text (Markdown), list, table, code"),
        title: z.string().optional(),
        color: colour,
        text: z.string().optional().describe("For text cards; short Markdown"),
        items: z.array(z.string()).optional().describe("For list cards"),
        columns: z.array(z.string()).optional().describe("For table cards"),
        rows: z.array(z.array(z.string())).optional().describe("For table cards"),
        code: z.string().optional(),
        lang: z.string().optional(),
      }),
    )
    .optional()
    .describe("Readable parts shown under the diagram"),
  remove: z.array(z.string()).optional().describe('Ids to remove: nodes, groups, cards, or arrows as "from->to"'),
};

// The working-folder tool, one per chat like the picture.
export function workspaceServer(onSet: (path: string) => Promise<string>) {
  return createSdkMcpServer({
    name: "workspace",
    version: "1.0.0",
    alwaysLoad: true,
    tools: [
      tool(
        "set_folder",
        "Set the project folder you are working in (absolute, ~/…, or relative to ~/Work). Returns that folder's CLAUDE.md and git status. Call it when you start on a project and whenever you switch projects.",
        { path: z.string().describe("The project's root folder") },
        async (args) => ({ content: [{ type: "text", text: await onSet(args.path) }] }),
      ),
    ],
  });
}

// One server per chat, so each draw call lands in the chat that made it.
export function pictureServer(onDraw: (input: DrawInput) => string) {
  return createSdkMcpServer({
    name: "picture",
    version: "1.0.0",
    alwaysLoad: true,
    tools: [
      tool(
        "draw",
        "Change the picture on the right half of the user's screen. Nodes, arrows, groups and cards are upserted by id, so send only what changed.",
        drawShape,
        async (args) => ({ content: [{ type: "text", text: onDraw(args as DrawInput) }] }),
      ),
    ],
  });
}
