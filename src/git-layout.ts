// The git form's commit graph layout (from GitGud's lib/graph-layout.js): which lane each commit sits
// in and which commits get a branch colour. Pure data, no git: the runner lays out what git log gave
// (src/git.ts), the forms gallery lays out its sample.

export type Ref = { type: "head" | "branch" | "remote" | "tag" | "detached"; name: string; tracked?: boolean; gone?: boolean; ahead?: number; behind?: number };
export type Commit = {
  hash: string;
  parents: string[];
  author: string;
  when: string; // "3 hours ago"
  time: number; // author time, unix seconds
  refs: Ref[];
  subject: string;
  col?: number; // lane, 0-based
  colorKey?: number | null; // index into the lane colours; null = merged-in history, drawn grey
};

export function layoutGraph(commits: Commit[]): Commit[] {
  assignLanes(commits);
  colorizeTrunks(commits);
  return commits;
}

// Commits newest first, children before parents (git's --topo-order). Only parents in view get a lane,
// so a merge whose second parent is off screen doesn't open a lane that never closes.
function assignLanes(commits: Commit[]) {
  const present = new Set(commits.map((c) => c.hash));
  const lanes: (string | null)[] = []; // each lane holds the hash it is waiting for
  const firstFree = () => {
    const i = lanes.indexOf(null);
    return i === -1 ? lanes.push(null) - 1 : i;
  };
  for (const c of commits) {
    let col = lanes.indexOf(c.hash);
    if (col === -1) col = firstFree();
    // a merge can have several lanes waiting on this commit: collapse them
    for (let i = 0; i < lanes.length; i++) if (i !== col && lanes[i] === c.hash) lanes[i] = null;
    c.col = col;
    const parents = c.parents.filter((p) => present.has(p));
    if (!parents.length) lanes[col] = null;
    else {
      lanes[col] = parents[0]; // the first parent continues this lane
      for (const p of parents.slice(1)) if (lanes.indexOf(p) === -1) lanes[firstFree()] = p;
    }
  }
}

// A commit is coloured when it is on a branch's trunk: reachable from a branch tip by first parents
// only. Merged-in history stays grey, so a coloured line always leads to a real branch tip. Tips that
// share history share the colour of the newest tip.
function colorizeTrunks(commits: Commit[]) {
  const byHash = new Map(commits.map((c) => [c.hash, c]));
  for (const c of commits) c.colorKey = null;
  const tipColor = new Map<string, number>();
  let next = 0;
  const local = commits.filter((c) => c.refs.some((r) => r.type === "branch" || r.type === "head"));
  const remote = commits.filter((c) => !local.includes(c) && c.refs.some((r) => r.type === "remote"));
  for (const tip of [...local, ...remote]) {
    let key = tipColor.get(tip.hash);
    if (key === undefined) tipColor.set(tip.hash, (key = next++));
    let cur: Commit | undefined = tip;
    while (cur && cur.colorKey === null) {
      cur.colorKey = key;
      cur = cur.parents[0] ? byHash.get(cur.parents[0]) : undefined;
    }
  }
}
