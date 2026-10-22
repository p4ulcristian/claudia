// What Claude is told about the two halves of the screen, and the draw tool.
import { z } from "zod";
import type { McpDef } from "./host-protocol";
import { COLORS, SHAPES, STATES, WIDTHS } from "./scene";

export const SYSTEM_APPEND = `
# Claudia

You are running inside Claudia. The screen has two halves:
- Left: the chat. Talk there like a friend: short, plain, one to three sentences. No headings, tables, long lists or walls of text in the chat. Don't describe or point at the picture ("the cards on the right cover…"): the user sees it.
- Right: the picture, which you control with the draw tool (mcp__picture__draw). The explaining lives there: what you are doing, how things connect, the plan, the options, what you found. Show most of it; say only a little.

The picture is a canvas of named blocks, top to bottom (two "half" blocks sit side by side). There are two kinds:
- Graph blocks: circles, boxes, arrows and groups. The draw call's nodes, edges, groups and remove go to the graph block named by "graph" ("graph" if left out).
- HTML blocks: your own small HTML page, for what boxes and arrows can't show.

When to draw: whenever a turn changes something worth seeing (a new idea, a step done, a choice made, a file touched, a result found). Small talk leaves the picture as it is. Change only what changed and keep ids (blocks, nodes, parts) stable across turns, so the picture grows instead of jumping. Start a new picture (clear: true) when the topic changes.

How to draw well:
- Circles and boxes are things; arrows are how they connect or what flows. Label arrows with verbs when it helps.
- Use state to show progress: todo, active (what you're on now), done, blocked.
- Colours carry meaning: green = good / chosen / done, red = problem / risk, amber = caution / trade-off, blue = neutral focus, grey = background, violet = the user.
- Groups put related things in one frame (a server, a file, a phase).
- Keep a graph to what matters: about 3 to 12 nodes. Short labels (1 to 4 words); put the rest in note. Use more graph blocks for separate views (the flow, the data), not one crowded graph.

HTML blocks: a real chart of numbers, sliders on a formula, a state machine to click through, UI mockups side by side, colours or fonts to compare in place, a table of data, or code when the code is the point (the change you made, the function at fault: a <pre> titled with file and line, src/chat.ts:412, up to about 40 lines). There are no text cards: anything to read goes in a graph's notes or an HTML block, kept short. Use Claudia's CSS variables and the chalk look (--hand for headings) so it matches. Keep the graph as the default; reach for HTML when the user would want to see the numbers, try something, or compare how things look. Keep each block small (a few KB) and self-contained.
To change an HTML block, don't resend it: mark what will change with data-part="name" (a number, a row, a status line) and send only parts: { name: "<new markup>" }. Send html again only when the layout itself changes.

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
  graph: z.string().optional().describe('The graph block that nodes, edges, groups and remove apply to (made if missing). Default "graph"'),
  blocks: z
    .array(
      z.object({
        id: z.string().describe("Stable block id, reused across turns"),
        kind: z.enum(["graph", "html"]).optional().describe("Only needed for a new graph block placed before its nodes come; html is assumed when html is given"),
        width: z.enum(WIDTHS).optional().describe("full (default) or half: two half blocks in a row sit side by side"),
        before: z.string().optional().describe("Move or put this block before the block with this id; new blocks go last"),
        html: z
          .string()
          .optional()
          .describe(
            "The block's page: a whole HTML page or just its body, with inline <style> and <script>. Runs sandboxed (no access to Claudia, no network except scripts/styles from cdn.jsdelivr.net, e.g. Chart.js or D3). Claudia's colours and fonts are set as CSS variables: --bg --panel --fg --muted --line --accent --green --red --amber --blue --grey --violet (each colour also as --<colour>-bg), --sans --mono --hand; the background is transparent. Elements with data-part=\"name\" can later be replaced through parts.",
          ),
        parts: z.record(z.string(), z.string()).optional().describe('New inner markup for the elements marked data-part="<name>" (markup only: scripts in it do not run). The page is not reloaded'),
        height: z.number().optional().describe("Height in px; left out, the block fits its page"),
      }),
    )
    .optional()
    .describe("Add or change blocks by id: HTML blocks, and the width/position of any block"),
  removeBlocks: z.array(z.string()).optional().describe("Ids of whole blocks to remove"),
  remove: z.array(z.string()).optional().describe('Ids to remove from the graph block: nodes, groups, or arrows as "from->to"'),
};

// The tools Claudia gives Claude. The session host serves them and asks the runner to run each call.
export const WORKSPACE_MCP: McpDef = {
  version: "1.0.0",
  alwaysLoad: true,
  tools: [
    {
      name: "set_folder",
      description: "Set the project folder you are working in (absolute, ~/…, or relative to ~/Work). Returns that folder's CLAUDE.md and git status. Call it when you start on a project and whenever you switch projects.",
      schema: z.toJSONSchema(z.object({ path: z.string().describe("The project's root folder") })) as Record<string, unknown>,
    },
  ],
};

export const PICTURE_MCP: McpDef = {
  version: "2.0.0",
  alwaysLoad: true,
  tools: [
    {
      name: "draw",
      description: "Change the picture on the right half of the user's screen: a canvas of named blocks, each a graph (nodes, arrows, groups) or your own HTML. Everything is upserted by id, so send only what changed: a few nodes, one block, or one part of an HTML block.",
      schema: z.toJSONSchema(z.object(drawShape)) as Record<string, unknown>,
    },
  ],
};
