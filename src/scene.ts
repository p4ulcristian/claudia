// The picture on the right: a fixed set of parts Claude names by id.
// Shared by the server (applies draw calls) and the page (renders them).

export const COLORS = ["green", "red", "amber", "blue", "grey", "violet"] as const;
export const SHAPES = ["circle", "box", "pill", "diamond"] as const;
export const STATES = ["todo", "active", "done", "blocked"] as const;
export const CARD_KINDS = ["text", "list", "table", "code"] as const;

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
};

export type Scene = {
  title: string;
  nodes: SceneNode[];
  edges: SceneEdge[];
  groups: SceneGroup[];
  cards: SceneCard[];
};

export type DrawInput = {
  title?: string;
  clear?: boolean;
  nodes?: (Partial<SceneNode> & { id: string })[];
  edges?: SceneEdge[];
  groups?: (Partial<SceneGroup> & { id: string })[];
  cards?: (Partial<SceneCard> & { id: string })[];
  remove?: string[];
};

export const emptyScene = (): Scene => ({ title: "", nodes: [], edges: [], groups: [], cards: [] });

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

// Returns the new scene and the ids that changed, so the page can make them glow.
export function applyDraw(prev: Scene, input: DrawInput): { scene: Scene; changed: string[] } {
  const base = input.clear ? emptyScene() : prev;
  const changed = new Set<string>();
  const gone = new Set(input.remove ?? []);

  let nodes = upsert(base.nodes, input.nodes, (p) => ({ label: p.id, ...p }) as SceneNode);
  let groups = upsert(base.groups, input.groups, (p) => ({ label: p.id, ...p }) as SceneGroup);
  let cards = upsert(base.cards, input.cards, (p) => ({ kind: "text", ...p }) as SceneCard);
  let edges = [...base.edges];
  for (const e of input.edges ?? []) {
    const i = edges.findIndex((x) => edgeId(x) === edgeId(e));
    if (i >= 0) edges[i] = { ...edges[i], ...e };
    else edges.push(e);
    changed.add(edgeId(e));
  }
  for (const list of [input.nodes, input.groups, input.cards]) for (const p of list ?? []) changed.add(p.id);

  nodes = nodes.filter((n) => !gone.has(n.id));
  groups = groups.filter((g) => !gone.has(g.id));
  cards = cards.filter((c) => !gone.has(c.id));
  const ids = new Set(nodes.map((n) => n.id));
  edges = edges.filter((e) => !gone.has(edgeId(e)) && ids.has(e.from) && ids.has(e.to));
  const groupIds = new Set(groups.map((g) => g.id));
  nodes = nodes.map((n) => (n.group && !groupIds.has(n.group) ? { ...n, group: undefined } : n));

  return {
    scene: { title: input.title ?? base.title, nodes, edges, groups, cards },
    changed: [...changed].filter((id) => !gone.has(id)),
  };
}
