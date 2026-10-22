// The picture on the right: a canvas of named blocks, each a graph (nodes, arrows, groups) or
// Claude's own HTML. Everything is named, so a draw call sends only what changed: a few nodes,
// one block, or one marked part inside an HTML block.
// Shared by the server (applies draw calls) and the page (renders them).

export const COLORS = ["green", "red", "amber", "blue", "grey", "violet"] as const;
export const SHAPES = ["circle", "box", "pill", "diamond"] as const;
export const STATES = ["todo", "active", "done", "blocked"] as const;
export const CARD_KINDS = ["text", "list", "table", "code", "page"] as const;

export type Color = (typeof COLORS)[number];

export type SceneNode = {
  id: string;
  label: string;
  shape?: (typeof SHAPES)[number];
  color?: Color;
  note?: string;
  group?: string;
  state?: (typeof STATES)[number];
};

export type SceneEdge = {
  from: string;
  to: string;
  label?: string;
  dashed?: boolean;
  color?: Color;
};

export type SceneGroup = { id: string; label: string; color?: Color };

export type SceneCard = {
  id: string;
  kind: (typeof CARD_KINDS)[number];
  title?: string;
  color?: Color;
  text?: string;
  items?: string[];
  columns?: string[];
  rows?: string[][];
  code?: string;
  lang?: string;
  // page cards: an HTML page shown in a sandboxed frame; height in px, or fitted to the page.
  html?: string;
  height?: number;
};

export const WIDTHS = ["full", "half"] as const;
type BlockBase = { id: string; width?: (typeof WIDTHS)[number] };
export type GraphBlock = BlockBase & { kind: "graph"; nodes: SceneNode[]; edges: SceneEdge[]; groups: SceneGroup[] };
// parts: markup for the elements marked data-part="<name>" in html, swapped in without reloading the page.
export type HtmlBlock = BlockBase & { kind: "html"; html: string; height?: number; parts: Record<string, string> };
export type Block = GraphBlock | HtmlBlock;

// blocks: the canvas. Pictures from before blocks have none and keep their nodes/edges/groups/cards
// (shown as they were); the next draw on one turns it into blocks (toBlocks).
export type Scene = {
  title: string;
  nodes: SceneNode[];
  edges: SceneEdge[];
  groups: SceneGroup[];
  cards: SceneCard[];
  blocks?: Block[];
};

export type BlockInput = {
  id: string;
  kind?: "graph" | "html";
  width?: (typeof WIDTHS)[number];
  before?: string;
  html?: string;
  height?: number;
  parts?: Record<string, string>;
};

export type DrawInput = {
  title?: string;
  clear?: boolean;
  // The graph block the nodes, edges, groups and remove go to (made if missing); "graph" by default.
  graph?: string;
  nodes?: (Partial<SceneNode> & { id: string })[];
  edges?: SceneEdge[];
  groups?: (Partial<SceneGroup> & { id: string })[];
  remove?: string[];
  blocks?: BlockInput[];
  removeBlocks?: string[];
};

export const emptyScene = (): Scene => ({ title: "", nodes: [], edges: [], groups: [], cards: [], blocks: [] });

// An older picture as blocks: its graph becomes the "graph" block, its page cards HTML blocks;
// the other cards (text, list, table, code) are dropped.
export function toBlocks(scene: Scene): Block[] {
  if (scene.blocks) return scene.blocks;
  const blocks: Block[] = [];
  if (scene.nodes.length) blocks.push({ id: "graph", kind: "graph", nodes: scene.nodes, edges: scene.edges, groups: scene.groups });
  for (const c of scene.cards) if (c.kind === "page" && c.html) blocks.push({ id: c.id, kind: "html", html: c.html, height: c.height, parts: {} });
  return blocks;
}

export const edgeId = (e: { from: string; to: string }) => `${e.from}->${e.to}`;

// Upsert by id: fields given replace, fields left out stay.
function upsert<T extends { id: string }>(list: T[], patches: (Partial<T> & { id: string })[] | undefined, fresh: (p: Partial<T> & { id: string }) => T | null): T[] {
  if (!patches?.length) return list;
  const out = [...list];
  for (const p of patches) {
    const i = out.findIndex((x) => x.id === p.id);
    if (i >= 0) out[i] = { ...out[i], ...p } as T;
    else {
      const made = fresh(p);
      if (made) out.push(made);
    }
  }
  return out;
}

// One graph block's nodes, arrows and groups after a draw call; adds the ids that changed.
function drawGraph(g: GraphBlock, input: DrawInput, changed: Set<string>): GraphBlock {
  const gone = new Set(input.remove ?? []);
  let nodes = upsert(g.nodes, input.nodes, (p) => ({ label: p.id, ...p }) as SceneNode);
  let groups = upsert(g.groups, input.groups, (p) => ({ label: p.id, ...p }) as SceneGroup);
  let edges = [...g.edges];
  for (const e of input.edges ?? []) {
    const i = edges.findIndex((x) => edgeId(x) === edgeId(e));
    if (i >= 0) edges[i] = { ...edges[i], ...e };
    else edges.push(e);
    changed.add(edgeId(e));
  }
  for (const list of [input.nodes, input.groups]) for (const p of list ?? []) changed.add(p.id);
  nodes = nodes.filter((n) => !gone.has(n.id));
  groups = groups.filter((x) => !gone.has(x.id));
  const ids = new Set(nodes.map((n) => n.id));
  edges = edges.filter((e) => !gone.has(edgeId(e)) && ids.has(e.from) && ids.has(e.to));
  const groupIds = new Set(groups.map((x) => x.id));
  nodes = nodes.map((n) => (n.group && !groupIds.has(n.group) ? { ...n, group: undefined } : n));
  for (const id of gone) changed.delete(id);
  return { ...g, nodes, edges, groups };
}

// Put a block before another one, or keep it where it is (new blocks go last).
function place(blocks: Block[], b: Block, before?: string): Block[] {
  const rest = blocks.filter((x) => x.id !== b.id);
  const at = before ? rest.findIndex((x) => x.id === before) : -1;
  if (at >= 0) return [...rest.slice(0, at), b, ...rest.slice(at)];
  const was = blocks.findIndex((x) => x.id === b.id);
  if (was >= 0) return blocks.map((x) => (x.id === b.id ? b : x));
  return [...rest, b];
}

// Returns the new scene and the ids that changed (nodes, arrows, blocks, "block:part"), so the page can make them glow.
export function applyDraw(prev: Scene, input: DrawInput): { scene: Scene; changed: string[] } {
  const base = input.clear ? emptyScene() : prev;
  const changed = new Set<string>();
  let blocks = toBlocks(base);

  for (const b of input.blocks ?? []) {
    const old = blocks.find((x) => x.id === b.id);
    const kind = old?.kind ?? b.kind ?? (b.html !== undefined ? "html" : "graph");
    let next: Block;
    if (kind === "graph") {
      const g = old?.kind === "graph" ? old : { id: b.id, kind: "graph" as const, nodes: [], edges: [], groups: [] };
      next = { ...g, ...(b.width ? { width: b.width } : {}) };
    } else {
      const h = old?.kind === "html" ? old : { id: b.id, kind: "html" as const, html: "", parts: {} };
      const fresh = b.html !== undefined && b.html !== h.html;
      next = {
        ...h,
        ...(b.width ? { width: b.width } : {}),
        ...(b.height !== undefined ? { height: b.height } : {}),
        ...(b.html !== undefined ? { html: b.html } : {}),
        // New HTML starts from its own markup; parts sent with it still apply.
        parts: { ...(fresh ? {} : h.parts), ...(b.parts ?? {}) },
      };
      if (fresh || b.height !== undefined) changed.add(b.id);
      for (const name of Object.keys(b.parts ?? {})) changed.add(`${b.id}:${name}`);
    }
    if (!old || b.width) changed.add(b.id);
    blocks = place(blocks, next, b.before);
  }

  if (input.nodes?.length || input.edges?.length || input.groups?.length || input.remove?.length) {
    const id = input.graph ?? "graph";
    const old = blocks.find((x) => x.id === id);
    const g: GraphBlock = old?.kind === "graph" ? old : { id, kind: "graph", nodes: [], edges: [], groups: [] };
    blocks = place(blocks, drawGraph(g, input, changed));
  }

  const goneBlocks = new Set(input.removeBlocks ?? []);
  blocks = blocks.filter((b) => !goneBlocks.has(b.id) && (b.kind === "html" || b.nodes.length > 0));

  return {
    scene: { title: input.title ?? base.title, nodes: [], edges: [], groups: [], cards: [], blocks },
    changed: [...changed],
  };
}

// "2 blocks: graph (5 nodes, 4 arrows), chart (HTML)": what the draw tool tells Claude back.
export function describe(scene: Scene): string {
  const blocks = toBlocks(scene);
  if (!blocks.length) return "The picture is empty.";
  return `${blocks.length} block${blocks.length > 1 ? "s" : ""}: ` + blocks
    .map((b) => (b.kind === "graph" ? `${b.id} (graph, ${b.nodes.length} nodes, ${b.edges.length} arrows)` : `${b.id} (HTML${Object.keys(b.parts).length ? `, parts ${Object.keys(b.parts).join(", ")}` : ""})`))
    .join(", ");
}
