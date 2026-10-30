// What Claude is told about the two halves of the screen, and the draw tool.
import { z } from "zod";
import type { McpDef } from "./host-protocol";
import { COLORS, SHAPES, STATES, WIDTHS } from "./scene";
import { FORM_NAMES, formsHelp } from "./forms";

export const SYSTEM_APPEND = `
# Claudia

You are running inside Claudia. The screen has two halves:
- Left: the chat. Talk there like a friend: short, plain, one to three sentences. No headings, tables, long lists or walls of text in the chat. Don't describe or point at the picture ("the cards on the right cover…"): the user sees it.
- Right: the picture, which you control with the draw tool (mcp__picture__draw). The explaining lives there: what you are doing, how things connect, the plan, the options, what you found. Show most of it; say only a little.

The picture is a canvas of blocks, top to bottom (two "half" blocks sit side by side). There are three kinds, and each block wears a badge with its kind and its name:
- Graph blocks: circles, boxes, arrows and groups. The draw call's nodes, edges, groups and remove go to the graph block named by "graph" ("graph" if left out).
- Forms: predefined shapes for common things, drawn in the board's chalk style. Pick a form when one fits; it is faster to read and cheaper to update than your own HTML. Send only the items that changed (by id); removeItems drops some. The forms and their data are listed in the draw tool's description (always the current list).
- Freeform: your own small HTML page, for what no graph or form can show.

Give every block a short, telling name (blocks[].name, e.g. "Deploy steps", "Latency by hour"): the user sees it on the badge and uses it to ask for changes ("make the latency chart weekly").

Templates: the user can save a freeform block as a named template (or ask you to: save_template). The templates tool lists them; draw one with blocks: [{ id, template: "<name>", parts }] and fill its parts. When the user asks to change a template, change it with save_template (same name), then draw it again.

When to draw: whenever a turn changes something worth seeing (a new idea, a step done, a choice made, a file touched, a result found). Small talk leaves the picture as it is. Change only what changed and keep ids (blocks, nodes, parts) stable across turns, so the picture grows instead of jumping. Start a new picture (clear: true) when the topic changes.

How to draw well:
- Circles and boxes are things; arrows are how they connect or what flows. Label arrows with verbs when it helps.
- Use state to show progress: todo, active (what you're on now), done, blocked.
- Colours carry meaning: green = good / chosen / done, red = problem / risk, amber = caution / trade-off, blue = neutral focus, grey = background, violet = the user.
- Groups put related things in one frame (a server, a file, a phase).
- Keep a graph to what matters: about 3 to 12 nodes. Short labels (1 to 4 words); put the rest in note. Use more graph blocks for separate views (the flow, the data), not one crowded graph.

Freeform blocks: a chart no form covers, sliders on a formula, a state machine to click through, UI mockups side by side, colours or fonts to compare in place or a table of data. Code always goes in the files form (the file opened, lines highlighted, notes pinned), never in freeform HTML. There are no text cards: anything to read goes in a graph's notes or an HTML block, kept short. Use Claudia's CSS variables and the chalk look (--hand for headings) so it matches. Keep graphs and forms as the default; reach for freeform when the user would want to try something or compare how things look, and nothing predefined fits. Keep each block small (a few KB) and self-contained. A page is always shown at its full height and the picture scrolls, so never give it a fixed height or inner scroll areas (no height on wrappers, no overflow: auto).
To change a freeform block, don't resend it: mark what will change with data-part="name" (a number, a row, a status line) and send only parts: { name: "<new markup>" }. Send html again only when the layout itself changes.

When you need the user to choose, use AskUserQuestion: it fills the whole window as buttons, with each option's description (put pros and cons there) and your recommended option first. Don't also write the question out in the chat.

The draw call costs the user nothing to read, so prefer drawing over writing.

## Working folder
The chat runs in ~/Work. When you start working in a project, call mcp__workspace__set_folder with its root folder; it hands you that project's CLAUDE.md and git status. Call it again whenever you move to another project. (If you edit a file first, the folder is set to that file's project automatically.) Run commands in the working folder (cd <folder> && …). The user sees the working folder, and the file changes you make are shown to them as diffs, so don't list your edits in the chat.

## Progress bars
A background command that takes minutes (a test suite, a build, a copy) shows a live bar on its chip under the chat's title when its output has a line `##progress <done>/<total> <label>`. Wrap such a command with claudia-progress (on PATH): `claudia-progress --total 259 --match '^Testing ' --label 'test namespaces' -- <command>` prints that line after each output line matching the regex. Run it with run_in_background so the chip exists.

## Images in the chat
To send the user an image, call mcp__workspace__show_image with the file's path; it appears in the chat under your steps. Markdown image links in your reply do not load.
`.trim();

const colour = z.enum(COLORS).optional();

// The draw tool's input; forms: the ones on offer (the user can delete forms in the Forms window).
const drawShape = (forms: string[]) => ({
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
        name: z.string().optional().describe("Short telling name shown on the block's badge, e.g. \"Deploy steps\""),
        kind: z.enum(["graph", "html"]).optional().describe("Only needed for a new graph block placed before its nodes come; html is assumed when html or template is given"),
        form: z.enum(forms as [string, ...string[]]).optional().describe("Makes this a form block of that kind"),
        data: z.record(z.string(), z.any()).optional().describe("A form's data (see the forms in this tool's description): item lists are upserted by item id, other fields replaced"),
        removeItems: z.array(z.string()).optional().describe("Ids of form items to remove"),
        template: z.string().optional().describe("Draw a saved template (by name) in this freeform block; fill it through parts"),
        width: z.enum(WIDTHS).optional().describe("full (default) or half: two half blocks in a row sit side by side"),
        before: z.string().optional().describe("Move or put this block before the block with this id; new blocks go last"),
        html: z
          .string()
          .optional()
          .describe(
            "The block's page: a whole HTML page or just its body, with inline <style> and <script>. Runs sandboxed (no access to Claudia, no network except scripts/styles from cdn.jsdelivr.net, e.g. Chart.js or D3). Claudia's colours and fonts are set as CSS variables: --bg --panel --fg --muted --line --accent --green --red --amber --blue --grey --violet (each colour also as --<colour>-bg), --sans --mono --hand; the background is transparent. Elements with data-part=\"name\" can later be replaced through parts.",
          ),
        parts: z.record(z.string(), z.string()).optional().describe('New inner markup for the elements marked data-part="<name>" (markup only: scripts in it do not run). The page is not reloaded'),
        height: z.number().optional().describe("Not used: a page is always shown at its full height"),
      }),
    )
    .optional()
    .describe("Add or change blocks by id: HTML blocks, and the width/position of any block"),
  removeBlocks: z.array(z.string()).optional().describe("Ids of whole blocks to remove"),
  remove: z.array(z.string()).optional().describe('Ids to remove from the graph block: nodes, groups, or arrows as "from->to"'),
});

// The tools Claudia gives Claude. The session host serves them and asks the runner to run each call.
export const WORKSPACE_MCP: McpDef = {
  version: "1.1.0",
  alwaysLoad: true,
  tools: [
    {
      name: "show_image",
      description: "Show a picture file in the chat (png, jpeg, gif or webp, up to 5 MB): a screenshot, a rendered image, a photo. The user sees it under your steps and you see it too. Use it when the user asks you to send or show an image; a diagram or explanation still goes in the picture on the right.",
      schema: z.toJSONSchema(z.object({ path: z.string().describe("The image file (absolute, ~/…, or relative to the working folder)"), caption: z.string().optional().describe("A few words about it") })) as Record<string, unknown>,
    },
    {
      name: "set_folder",
      description: "Set the project folder you are working in (absolute, ~/…, or relative to ~/Work). Returns that folder's CLAUDE.md and git status. Call it when you start on a project and whenever you switch projects.",
      schema: z.toJSONSchema(z.object({ path: z.string().describe("The project's root folder") })) as Record<string, unknown>,
    },
  ],
};

// hidden: forms the user deleted; they leave the tool's description and its list of forms.
// required: kinds the user made required in this chat (the row over the picture).
export const pictureMcp = (hidden: string[] = [], required: string[] = []): McpDef => {
  const forms = FORM_NAMES.filter((f) => !hidden.includes(f));
  return {
  version: "3.0.0",
  alwaysLoad: true,
  tools: [
    {
      name: "draw",
      description: `Change the picture on the right half of the user's screen: a canvas of named blocks, each a graph (nodes, arrows, groups), a predefined form or your own freeform HTML. Everything is upserted by id, so send only what changed: a few nodes, a few form items, one block, or one part of a freeform block.

Forms (blocks[].form + blocks[].data); pick one when it fits, before freeform:
${formsHelp(forms)}${required.length ? `\n\nIn this chat the user made REQUIRED: ${required.join(", ")} (green in the form row). The picture always holds at least one block of each; a change that takes the last one away is refused. Other kinds stay allowed.` : ""}${required.includes("freeform") ? " Freeform required means: be creative, build interactive pages where they help." : ""}${hidden.includes("freeform") ? "\n\nFreeform is switched off for this chat: no html blocks or templates; use graphs and forms." : ""}`,
      schema: z.toJSONSchema(z.object(drawShape(forms.length ? forms : ["none"]))) as Record<string, unknown>,
    },
    {
      name: "templates",
      description: "List the saved templates (freeform blocks kept by name): name, description, parts.",
      schema: z.toJSONSchema(z.object({})) as Record<string, unknown>,
    },
    {
      name: "save_template",
      description: "Save or change a template: from a freeform block of the picture (block) and/or new html. Same name = change it.",
      schema: z.toJSONSchema(
        z.object({
          name: z.string().describe("Short telling name, e.g. \"Plan usage tiles\""),
          block: z.string().optional().describe("Id of a freeform block in the picture to take the page and parts from"),
          html: z.string().optional().describe("The page, as for a freeform block (replaces the old one)"),
          parts: z.record(z.string(), z.string()).optional().describe("Default markup for its data-part elements"),
          description: z.string().optional().describe("One line: what it shows and when to use it"),
        }),
      ) as Record<string, unknown>,
    },
  ],
};
};
