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
  optIn?: boolean; // only on runners that name it in CLAUDIA_FORMS (src/chat.ts)
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
  files: {
    label: "Files",
    when: "A project's files as a tree the user can browse and open: where things live, what a change touched, which files matter, and any code to show: open the file (file) with the lines that matter highlighted (lines) and short notes pinned to lines (notes) instead of explaining in the chat; code that isn't on disk (a proposal, a snippet) goes in sketch. Read live from disk (git status shown), so it stays current; you send only the folder and what to point out. For files that don't exist yet (a planned layout, a sketch of a library), send sketch: alone it is a made-up tree; with root it adds planned files to the real one. Each is labelled for the user (on disk / sketch / planned), so never present made-up files as real.",
    fields: "root? (a folder: absolute, ~/…, or relative to the working folder; default the working folder), file? (the selected file, relative to root: shown open, whole, with only the folders down to it unfolded; without it the first marked file is selected), lines?: [line numbers or \"a-b\" ranges to highlight in that file; it scrolls to the first], notes?: [{ id, line, text, color? }] pinned under lines of that file, marks?: [{ id, path (relative to root), note?, color? }] files or folders to point out. Planned files: sketch: [{ path (relative to root when root is given), code?, status? }] (folders come from the paths), title? (what a root-less sketch is); marks work the same",
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
  git: {
    label: "Git",
    optIn: true,
    when: "A repo's history as a live commit graph, like GitGud: branches in coloured lanes (local, remote or both, ahead/behind), HEAD, tags, the uncommitted changes as a dashed node above HEAD; the user clicks a commit for its files and diff. Read live from the repo and redrawn when it changes (a commit, a checkout, an edit), so you send only the repo and what to point out. Read only: it never changes the repo.",
    fields: "repo? (a folder in the repo: absolute, ~/…, or relative to the working folder; default the working folder), branches?: [branch names to graph; default all], limit? (commits, default 100), commit? (a hash, branch, tag or \"WORKING\" to open for the user), marks?: [{ id, commit (a hash, branch, tag, \"HEAD\" or \"WORKING\"), note?, color? }] commits to point out",
    sample: {
      demo: {
        head: "c4",
        currentBranch: "fix/tax",
        dirty: { total: 2, staged: 0, unstaged: 1, untracked: 1, files: [{ path: "src/orders/total.ts", status: "M" }, { path: "src/orders/tax.test.ts", status: "?" }] },
        branches: [
          { name: "fix/tax", kind: "local", local: true, current: true },
          { name: "main", kind: "both", local: true, tracked: true, ahead: 0, behind: 0 },
          { name: "origin/shops", kind: "remote", local: false },
        ],
        commits: [
          { hash: "c4", parents: ["c2"], author: "Paul", when: "20 minutes ago", time: 1_000_000, refs: [{ type: "head", name: "fix/tax" }], subject: "Tax on shipping too" },
          { hash: "c3", parents: ["c1"], author: "Ananda", when: "5 hours ago", time: 983_000, refs: [{ type: "remote", name: "origin/shops" }], subject: "Shop list paging" },
          { hash: "c2", parents: ["c1", "c0"], author: "Paul", when: "yesterday", time: 914_000, refs: [{ type: "branch", name: "main", tracked: true, ahead: 0, behind: 0 }], subject: "Merge orders: quantities" },
          { hash: "c0", parents: ["c1"], author: "Peter", when: "2 days ago", time: 830_000, refs: [], subject: "Bill each coat's quantity" },
          { hash: "c1", parents: [], author: "Paul", when: "3 days ago", time: 760_000, refs: [{ type: "tag", name: "v1.4" }], subject: "Orders: totals" },
        ],
      },
      marks: [{ id: "m", commit: "c0", note: "the quantity fix", color: "amber" }],
    },
  },
  clojure: {
    label: "Clojure",
    optIn: true,
    when: "A Clojure project's namespaces as a map the user clicks through: bubbles for namespaces (or for prefixes, which open into their namespaces), arrows for :require, laid out in layers from entry points to leaves, across or down by the map's shape (an arrow going back is a circle, drawn red); a click on a namespace lists its vars with their arglists and docs, a click on a var shows its source, a click on an arrow lists the functions it uses. Read live with clj-kondo, so you send only the project and what to open or point out.",
    fields: "root? (the project folder: absolute, ~/…, or relative to the working folder; default the working folder), at? (a namespace prefix to open the map in, like \"features.orders\"), open? (a namespace, ns/var, or \"a -> b\" for the arrow between two bubbles of the map, to pick for the user), direction?: across|down (default: picked by the map's shape, deep maps run down), marks?: [{ id, ns, var?, note?, color? }] namespaces or vars to point out",
    sample: {
      demo: {
        namespaces: [
          { name: "ir.server", vars: 2, requires: ["ir.routes", "ir.db"] },
          { name: "ir.routes", vars: 2, requires: ["ir.orders.api", "ir.shops"] },
          { name: "ir.orders.api", vars: 3, requires: ["ir.orders.total", "ir.db"] },
          { name: "ir.orders.total", vars: 3, requires: ["ir.orders.tax", "ir.money"] },
          { name: "ir.orders.tax", vars: 2, requires: ["ir.money"] },
          { name: "ir.shops", vars: 2, requires: ["ir.db"] },
          { name: "ir.db", vars: 2, requires: [] },
          { name: "ir.money", vars: 2, requires: [] },
        ],
        uses: {
          "ir.server": { "ir.routes": ["app"], "ir.db": ["pool"] },
          "ir.routes": { "ir.orders.api": ["checkout", "hand-over"], "ir.shops": ["shop", "list-shops"] },
          "ir.orders.api": { "ir.orders.total": ["total"], "ir.db": ["q", "tx"] },
          "ir.orders.total": { "ir.orders.tax": ["rate"], "ir.money": ["round"] },
          "ir.orders.tax": { "ir.money": ["cents"] },
          "ir.shops": { "ir.db": ["q"] },
        },
        vars: {
          "ir.orders.total": {
            doc: "An order's total: every coat, shipping and tax.",
            external: ["clojure.string"],
            vars: [
              { name: "line-price", args: ["[line]"], private: true, kind: "fn", row: 3, code: "(defn- line-price [line]\n  (* (:qty line) (:unit-cents line)))" },
              { name: "shipping", args: ["[order]"], kind: "fn", row: 6, code: "(defn shipping [order]\n  (if (:pickup? order) 0 1500))" },
              { name: "total", args: ["[order]", "[order opts]"], doc: "Sum of every coat, shipping and tax.", kind: "fn", row: 9, code: "(defn total\n  \"Sum of every coat, shipping and tax.\"\n  ([order] (total order {}))\n  ([order {:keys [state]}]\n   (let [net (+ (reduce + (map line-price (:lines order)))\n                (shipping order))]\n     (+ net (money/round (* net (tax/rate state)))))))" },
            ],
          },
        },
      },
      at: "ir",
      open: "ir.orders.total/total",
      marks: [{ id: "t", ns: "ir.orders.tax", note: "shipping taxed too", color: "amber" }],
    },
  },
  calls: {
    label: "Calls",
    optIn: true,
    when: "A Clojure project's function calls around one var: what calls what, and what that code is. Three tabs: Unfold (the var's source; each call to a project var is a link that opens that var's code nested under the calling line), Neighbours (the var in the middle, its callers on the left with the calling line, its callees on the right with their doc line; click to move), Map (columns by steps away, up to callers and down to callees, boxes coloured by folder, arrows for calls). Calls to .sql files named by a keyword (Iron Rainbow's sql/q) link to the file. Read live with clj-kondo, so you send only the project and the var. Pick it to show how code flows through functions; clojure for how namespaces depend on each other; files for one file's lines.",
    fields: "root? (the project folder: absolute, ~/…, or relative to the working folder; default the working folder), open (the start var, \"ns/var\", required; the end of a namespace is enough when only one matches, like \"state-gate/check\"), view?: unfold|neighbours|map (the tab shown, default unfold), up?, down? (the map's steps of callers and callees, default 2 and 3), marks?: [{ id, var (\"ns/var\"), line? (a row of its file), note?, color? }] vars or lines to point out (shown on the code and in the map)",
    sample: {
      demo: {
        "features.common.payments.charge.backend.state-gate/check": {
          file: "features/common/payments/charge/backend/state_gate.clj", row: 75,
          code: "(defn check\n  \"refusal for a charge to `workspace-ids` from a customer in `customer-state`,\n   read from the database and today's date.\"\n  [pool workspace-ids customer-state]\n  (refusal {:shops          (shop-states pool workspace-ids)\n            :customer-state customer-state\n            :today          (today-iso)}))",
          calls: ["features.common.payments.charge.backend.state-gate/refusal", "features.common.payments.charge.backend.state-gate/shop-states", "features.common.payments.charge.backend.state-gate/today-iso"],
        },
        "features.common.payments.charge.backend.state-gate/refusal": {
          file: "features/common/payments/charge/backend/state_gate.clj", row: 35,
          code: "(defn refusal\n  \"Why this charge may not happen, or nil when it may. Pure.\"\n  [{:keys [shops customer-state today]}]\n  (let [code     (some-> customer-state str/trim not-empty us-states/resolve-code)\n        customer (when (rules/blocked? code today) code)\n        shop     (some (fn [{:keys [state override?]}]\n                         (when (and (not override?) (rules/blocked? state today))\n                           (us-states/resolve-code state)))\n                       shops)]\n    (cond\n      customer {:error-code \"state-not-available\" :side :customer :state customer}\n      shop     {:error-code \"state-not-available\" :side :shop :state shop})))",
          calls: ["utils.us-states/resolve-code", "features.common.payments.state-rules/blocked?"],
        },
        "features.common.payments.charge.backend.state-gate/shop-states": {
          file: "features/common/payments/charge/backend/state_gate.clj", row: 23,
          code: "(defn shop-states\n  \"[{:workspace-id :state :override?}] for the given workspace ids.\"\n  [pool workspace-ids]\n  (mapv (fn [row]\n          {:workspace-id (str (:workspace/id row))\n           :state        (:business-info/facility-state-code row)\n           :override?    (true? (:partnership/state-payments-override row))})\n        (sql/q pool :payment-states {:workspace-ids workspace-ids})))",
          calls: ["file:features/common/payments/charge/backend/payment_states.sql"],
        },
        "features.common.payments.charge.backend.state-gate/today-iso": {
          file: "features/common/payments/charge/backend/state_gate.clj", row: 15,
          code: "(defn today-iso\n  \"Today in US Eastern time, as yyyy-mm-dd: the date a state's law takes effect.\"\n  []\n  (str (LocalDate/now (ZoneId/of \"America/New_York\"))))",
        },
        "file:features/common/payments/charge/backend/payment_states.sql": {
          file: "features/common/payments/charge/backend/payment_states.sql", row: 1,
          code: "-- Each shop's facility state and its Labs state-payments override (#384).\nSELECT w.:workspace/id,\n       pf.shop_profile__facility_state_code AS business_info__facility_state_code\nFROM workspaces w\nLEFT JOIN shop_profiles pf ON pf.:shop-profile/workspace-id = w.:workspace/id\nWHERE w.:workspace/id = ANY(:workspace-ids::uuid[])",
        },
        "features.common.payments.charge.backend.intent/priced-payment-intent": {
          file: "features/common/payments/charge/backend/intent.clj", row: 159,
          code: "(defn priced-payment-intent\n  \"The PaymentIntent for a cart, priced on the server.\"\n  [pool {:keys [workspace-id address] :as params}]\n  (if-let [blocked (state-gate/check pool [workspace-id] (:state address))]\n    {:status 400 :body blocked}\n    (create-intent pool params)))",
          calls: ["features.common.payments.charge.backend.state-gate/check"],
        },
        "utils.us-states/resolve-code": {
          file: "utils/us_states.cljc", row: 92,
          code: "(defn resolve-code\n  \"The 2-letter code for a state given as its code or its full name, or nil.\"\n  [state]\n  (when (string? state)\n    (or (normalize-code state)\n        (get lower-name->code (-> state str/trim str/lower-case)))))",
        },
        "features.common.payments.state-rules/blocked?": {
          file: "features/common/payments/state_rules.cljc", row: 32,
          code: "(defn blocked?\n  \"True when card payments are off for `state` on `today`.\"\n  [state today]\n  (let [s (code state)]\n    (boolean\n     (when s\n       (or (contains? always-blocked s)\n           (when-let [until (get blocked-until s)]\n             (neg? (compare (str today) until))))))))",
        },
      },
      open: "features.common.payments.charge.backend.state-gate/check",
      marks: [{ id: "m", var: "features.common.payments.charge.backend.state-gate/shop-states", line: 30, note: "one query for every shop", color: "amber" }],
    },
  },
  editor: {
    label: "Editor",
    when: "Code you and the user both edit: an example for them to change, an exercise, a draft to finish, a snippet to try. One tab per file, a real editor; the user types in it and presses Send, which hands you a <claudia-code> message with their diff against your version and their full code. While the user has unsent edits in a file, draw refuses new code for that file and gives you their code: merge your change into it and draw again with seen set to the revision it names. Pick it when the user should write or change code; files for code on disk to read.",
    fields: "files: [{ id, name (a file name: its extension picks the language), lang? (when the name has none), code, seen? (the user's edit revision your code merges, after a refusal) }] tabs in order, open? (the file id shown), note? (one line over the editor: what to try)",
    sample: {
      note: "Make total() skip cancelled lines.",
      files: [
        { id: "total", name: "total.ts", code: "type Line = { qty: number; unitCents: number; cancelled?: boolean };\n\nexport function total(lines: Line[]): number {\n  return lines.reduce((sum, l) => sum + l.qty * l.unitCents, 0);\n}\n" },
        { id: "test", name: "total.test.ts", code: "import { expect, test } from \"bun:test\";\nimport { total } from \"./total\";\n\ntest(\"two coats\", () => {\n  expect(total([{ qty: 2, unitCents: 1500 }])).toBe(3000);\n});\n" },
      ],
    },
  },
  scene3d: {
    label: "3D scene",
    when: "Something best understood as a shape in space that the user can rotate and zoom: an architecture with layers, a mechanism, a building, a molecule, a product, a map with height. Predefined shapes placed in 3D, arrows between them, labels. Pick it when depth or a free viewpoint helps; a flat graph is better for plain boxes and arrows.",
    fields: "parts: [{ id, shape: box|sphere|cylinder|cone|pyramid|torus|capsule|plane|ring|extrude|lathe|tube|rod|group, pos?: [x,y,z] (y is up, 1 unit is about a box), size?: number or [x,y,z], rot?: [x,y,z] degrees, color? (green|red|amber|blue|grey|violet or any CSS colour), label?, note?, material? (what the surface is made of, for real reflections: steel|brushed-steel|aluminium|chrome|copper|brass|iron|plastic|matte-plastic|rubber|enamel|ceramic|wood|glass; glass is see-through and never solid; a colour tints it), opacity?: 0..1, wire?: true, points? (extrude: [[x,y]…] outline pulled 1 unit deep, scale it with size; lathe: [[radius,y]…] profile spun round y; tube: [[x,y,z]…] path), parent? (a part or group id: this part's pos/rot are then relative to it and it follows it), ghost? (true: not solid), outline?: false (no ink outline), a group is an empty frame to hang parts on and turn together (a rotor and its blades), radius? (tube, rod), from?/to? ([x,y,z] ends of a rod: a cylinder between two points) }], arrows?: [{ id, from, to (a part id, or [x,y,z]), via?: [[x,y,z]…] (it bends through these points), flow?: true (a stream of dashes runs along it: air, current, signal), label?, color? }] (they follow their parts when these move), legend?: [{ color, label }] (colour key chips in a corner: use one colour per job, always the same: heat red, cold air blue, motion green, control violet), script?: JavaScript source for what the data can't say, run in a locked-down separate engine (no window, no network; plain JavaScript and Math only, 40 ms per frame): define build() returning { parts?, arrows? } for parts made by loops and maths, and frame(t, step) (t = seconds, step = the story's current step, 0-based, -1 when there is none) returning { [partId]: { pos?, rot?, size?, color?, opacity?, from?/to? (rod) } } to move parts every frame; the user gets play/pause and speed buttons; build() may also return allow), allow?: [[idA, idB]…] pairs that are meant to touch or overlap (a pin inside a rod end; an id ending in * matches every id starting so), scan?: seconds of the script the user's scan button checks (default 12). story?: [{ say?: the line spoken in this step (shown as subtitles; a narrator voice reads it aloud when the user has turned it on, it is off by default), show?: ids to bring in (part or arrow ids; a trailing * matches a prefix, like blade*), hide?: ids to take away, focus?: ids that glow while the step lasts, camera?: { frame?: ids to bring into view, target?: an id or [x,y,z], dist?, az?: degrees round, el?: degrees up }, hold?: minimum seconds }] makes the scene a narrated build: parts the story shows stay hidden until their step (parts it never shows are there from the start), the user presses play and the steps run one after the other, each part fading in as it is spoken about, with pause, next, back and a dot per step; voice?: how the narrator sounds (like: a calm man), autoplay?: true starts the story by itself, narrate?: false turns the voice off. The default look is a chalk diagram: flat colour in bands and a thin light outline round every part; while a story step has focus, the other parts fade back and the focused ones get a thick amber outline, so name the focus in each step. The page checks that solid parts never pass through each other: parts that are translucent (opacity under 1), wire, plane, ring or ghost: true are housings and don't count; every other overlap glows red and is counted for the user, so build the model so nothing intersects (real clearances, split shafts) and list only intended overlaps in allow",
    sample: {
      parts: [
        { id: "app", shape: "box", pos: [0, 3.6, 0], size: [3, 0.6, 2], color: "blue", label: "App" },
        { id: "api", shape: "box", pos: [0, 1.8, 0], size: [3, 0.6, 2], color: "green", label: "API" },
        { id: "db", shape: "cylinder", pos: [0, 0.3, 0], size: [1.8, 0.6, 1.8], color: "amber", label: "Postgres" },
        { id: "cache", shape: "sphere", pos: [4, 1.8, 0], size: 1.1, color: "violet", label: "Cache", note: "hot reads" },
      ],
      arrows: [
        { id: "a1", from: "app", to: "api", label: "calls" },
        { id: "a2", from: "api", to: "db", label: "SQL" },
        { id: "a3", from: "api", to: "cache", label: "reads", color: "violet" },
      ],
    },
  },
  flow: {
    label: "Flow",
    when: "Something travelling through a system, step by step: a request through the layers, an order through checkout, a job through a queue. Stations side by side (with steps inside if one has them), cards carrying what travels slide between them frame by frame, a caption per frame; the user clicks back / next. Several scenarios (allowed vs refused, cache hit vs miss) to switch between. Use it when what moves and how it changes is the point; for a job's progress use pipeline.",
    fields: "stations: [{ id, label, note?, steps?: [{ id, label }] }] left to right, lanes?: [{ id, label }] one row per kind of thing that travels (default one), scenarios?: [{ id, label, frames }] or frames for one way through; a frame: { caption, cards?: [{ lane? (default the first), at (a station or step id), title?, body? (text, kept to a few lines), lang? (colours body), new? (a piece of body to mark as just added), color?, dim? }], on?: [station or step ids lit], leave?: [step ids, dashed: on the way out], skip?: [step ids, struck: never run] }. A lane's card slides when its at changes; a lane without a card in a frame fades out",
    sample: {
      stations: [
        { id: "browser", label: "Browser", note: "the screen" },
        { id: "api", label: "API", note: "checks", steps: [{ id: "who", label: "who?" }, { id: "may", label: "may they?" }] },
        { id: "db", label: "Postgres", note: "the data" },
      ],
      lanes: [{ id: "req", label: "the request" }, { id: "work", label: "work + replies" }],
      scenarios: [
        { id: "ok", label: "Anna (her shop)", frames: [
          { caption: "The screen asks to hand order 42 over.", cards: [{ lane: "req", at: "browser", title: "request", body: "POST /orders/42/hand", color: "grey" }] },
          { caption: "The API reads the cookie: Anna is asking.", on: ["who"], cards: [{ lane: "req", at: "who", title: "request", body: "order 42\nperson: Anna", new: "person: Anna", color: "violet" }] },
          { caption: "Is order 42 in her shop? A SELECT goes to Postgres.", on: ["may"], cards: [{ lane: "req", at: "may", title: "request (waiting)", body: "order 42\nperson: Anna", color: "violet", dim: true }, { lane: "work", at: "db", title: "SQL", body: "SELECT shop_id FROM orders\nWHERE id = 42", lang: "sql", color: "amber" }] },
          { caption: "Shop 7: hers. The order is handed over.", on: ["may"], cards: [{ lane: "req", at: "may", title: "answer", body: "status: handed", color: "green" }] },
          { caption: "The screen shows \"handed\".", leave: ["may", "who"], cards: [{ lane: "req", at: "browser", title: "done", body: "status: handed", color: "green" }] },
        ] },
        { id: "no", label: "Bob (not his)", frames: [
          { caption: "Bob asks to hand order 42 over.", cards: [{ lane: "req", at: "browser", title: "request", body: "POST /orders/42/hand", color: "grey" }] },
          { caption: "The cookie says Bob; order 42 is in shop 7, not his.", on: ["may"], cards: [{ lane: "req", at: "may", title: "answer", body: "status: not found", color: "red" }] },
          { caption: "Bob sees \"not found\". Nothing changed.", leave: ["may", "who"], cards: [{ lane: "req", at: "browser", title: "done", body: "status: not found", color: "red" }] },
        ] },
      ],
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
