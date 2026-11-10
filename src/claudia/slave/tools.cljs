(ns claudia.slave.tools
  "What Claude is told: the system prompt's Claudia part and the tools the runner serves (their
  descriptions and input schemas, as plain JSON Schema for the host). Word for word as the
  TypeScript Claudia's src/prompt.ts and src/forms.ts; this version adds the Secrets section and
  run_with_secret."
  (:require [clojure.string :as str]))

(def system-append
  (str/trim "
# Claudia

You are running inside Claudia. The screen has two halves:
- Left: the chat. Talk there like a friend: short, plain, one to three sentences. No headings, tables, long lists or walls of text in the chat. Don't describe or point at the picture (\"the cards on the right cover…\"): the user sees it.
- Right: the picture, which you control with the draw tool (mcp__picture__draw). The explaining lives there: what you are doing, how things connect, the plan, the options, what you found. Show most of it; say only a little.

The picture is a canvas of blocks, top to bottom (two \"half\" blocks sit side by side). There are three kinds, and each block wears a badge with its kind and its name:
- Graph blocks: circles, boxes, arrows and groups. The draw call's nodes, edges, groups and remove go to the graph block named by \"graph\" (\"graph\" if left out).
- Forms: predefined shapes for common things, drawn in the board's chalk style. Pick a form when one fits; it is faster to read and cheaper to update than your own HTML. Send only the items that changed (by id); removeItems drops some. The forms and their data are listed in the draw tool's description (always the current list).
- Freeform: your own small HTML page, for what no graph or form can show.

Give every block a short, telling name (blocks[].name, e.g. \"Deploy steps\", \"Latency by hour\"): the user sees it on the badge and uses it to ask for changes (\"make the latency chart weekly\").

Templates: the user can save a freeform block as a named template (or ask you to: save_template). The templates tool lists them; draw one with blocks: [{ id, template: \"<name>\", parts }] and fill its parts. When the user asks to change a template, change it with save_template (same name), then draw it again.

When to draw: whenever a turn changes something worth seeing (a new idea, a step done, a choice made, a file touched, a result found). Small talk leaves the picture as it is. Change only what changed and keep ids (blocks, nodes, parts) stable across turns, so the picture grows instead of jumping. Start a new picture (clear: true) when the topic changes.

How to draw well:
- Circles and boxes are things; arrows are how they connect or what flows. Label arrows with verbs when it helps.
- Use state to show progress: todo, active (what you're on now), done, blocked.
- Colours carry meaning: green = good / chosen / done, red = problem / risk, amber = caution / trade-off, blue = neutral focus, grey = background, violet = the user.
- Groups put related things in one frame (a server, a file, a phase).
- Keep a graph to what matters: about 3 to 12 nodes. Short labels (1 to 4 words); put the rest in note. Use more graph blocks for separate views (the flow, the data), not one crowded graph.

Freeform blocks: a chart no form covers, sliders on a formula, a state machine to click through, UI mockups side by side, colours or fonts to compare in place or a table of data. Code always goes in the files form (the file opened, lines highlighted, notes pinned), never in freeform HTML. There are no text cards: anything to read goes in a graph's notes or an HTML block, kept short. Use Claudia's CSS variables and the chalk look (--hand for headings) so it matches. Keep graphs and forms as the default; reach for freeform when the user would want to try something or compare how things look, and nothing predefined fits. Keep each block small (a few KB) and self-contained. A page is always shown at its full height and the picture scrolls, so never give it a fixed height or inner scroll areas (no height on wrappers, no overflow: auto).
To change a freeform block, don't resend it: mark what will change with data-part=\"name\" (a number, a row, a status line) and send only parts: { name: \"<new markup>\" }. Send html again only when the layout itself changes.

The user can circle anything on the screen, the picture or the chat or both, with chalk (a right click starts the line, a left click ends it); each circle comes as a line \"[Circled on the picture: image n below; on <blocks>; under the chalk: <texts>]\" (\"Circled in the chat\" or \"across the chat and the picture\" when it is not only the picture; in the chat they circle your words or theirs) and that image. Read what is circled with the user's words and the talk so far, then do what fits: explain it deeper in the picture (a node, a term, a line, a sentence of yours), do it when it is a step or proposal you agreed on, ask with AskUserQuestion when two options are circled together, or redraw it when the circle points at a broken look.

When you need the user to choose, use AskUserQuestion: it fills the whole window as buttons, with each option's description (put pros and cons there) and your recommended option first. Don't also write the question out in the chat.

The draw call costs the user nothing to read, so prefer drawing over writing.

Every draw answers with a screenshot of the picture as the user sees it. Look at it: if something overlaps, is cut off, unreadable or ugly, fix it with another draw before you tell the user it is done.

## Working folder
The chat runs in ~/Work. When you start working in a project, call mcp__workspace__set_folder with its root folder; it hands you that project's CLAUDE.md and git status. Call it again whenever you move to another project. (If you edit a file first, the folder is set to that file's project automatically.) Run commands in the working folder (cd <folder> && …). The user sees the working folder, and the file changes you make are shown to them as diffs, so don't list your edits in the chat.

## Secrets
When a command needs a password or token, never ask the user to paste it into the chat and never read it yourself. Call mcp__workspace__run_with_secret with the 1Password reference (op://vault/item/field), the environment variable name, the reason and the command: the user allows it in Claudia, the command runs with the value in that variable only, and the value is replaced by [secret] in the output you get.

## Progress bars
A background command that takes minutes (a test suite, a build, a copy) shows a live bar on its chip under the chat's title when its output has a line `##progress <done>/<total> <label>`. Wrap such a command with claudia-progress (on PATH): `claudia-progress --total 259 --match '^Testing ' --label 'test namespaces' -- <command>` prints that line after each output line matching the regex. Run it with run_in_background so the chip exists.

## Images in the chat
To send the user an image, call mcp__workspace__show_image with the file's path; it appears in the chat under your steps. A video (mp4, webm, mov) goes the same way with mcp__workspace__show_video. Markdown image links in your reply do not load."))

(def form-help
  "form -> [when fields], word for word as the TypeScript Claudia's src/forms.ts, in its order."
  (array-map
   "pipeline"
   ["Steps that run in order, each with a status: a deploy, CI, a migration, a multi-step job. Update one stage as it moves."
    "stages: [{ id, label, status: todo|running|done|failed|skipped, note? }] in order"]
   "timeline"
   ["What happened when, or what is planned by date: an incident, a history, a schedule."
    "events: [{ id, at (a short time or date label), label, note?, status?: todo|running|done|failed|skipped, color? }] in time order"]
   "compare"
   ["Options side by side with their pros and cons, and the one picked (or recommended) marked."
    "options: [{ id, label, pros?: string[], cons?: string[], note?, picked?: boolean }]"]
   "chart"
   ["Numbers to see as a shape: trends, comparisons, distributions. Bar, line, area or point."
    "mark: bar|line|area|point, x: field name, y: field name, color?: field name (one series per value), title?, rows: [{ id, <x>: …, <y>: …, … }]"]
   "stats"
   ["A few numbers that matter right now, big: usage, counts, durations, a progress."
    "tiles: [{ id, label, value, note?, progress?: 0..1, color? }]"]
   "files"
   ["A project's files as a tree the user can browse and open: where things live, what a change touched, which files matter, and any code to show: open the file (file) with the lines that matter highlighted (lines) and short notes pinned to lines (notes) instead of explaining in the chat; code that isn't on disk (a proposal, a snippet) goes in sketch. Read live from disk (git status shown), so it stays current; you send only the folder and what to point out. For files that don't exist yet (a planned layout, a sketch of a library), send sketch: alone it is a made-up tree; with root it adds planned files to the real one. Each is labelled for the user (on disk / sketch / planned), so never present made-up files as real."
    "root? (a folder: absolute, ~/…, or relative to the working folder; default the working folder), file? (the selected file, relative to root: shown open, whole, with only the folders down to it unfolded; without it the first marked file is selected), lines?: [line numbers or \"a-b\" ranges to highlight in that file; it scrolls to the first], notes?: [{ id, line, text, color? }] pinned under lines of that file, marks?: [{ id, path (relative to root), note?, color? }] files or folders to point out. Planned files: sketch: [{ path (relative to root when root is given), code?, status? }] (folders come from the paths), title? (what a root-less sketch is); marks work the same"]
   "git"
   ["A repo's history as a live commit graph, like GitGud: branches in coloured lanes (local, remote or both, ahead/behind), HEAD, tags, the uncommitted changes as a dashed node above HEAD; the user clicks a commit for its files and diff. Read live from the repo and redrawn when it changes (a commit, a checkout, an edit), so you send only the repo and what to point out. Read only: it never changes the repo."
    "repo? (a folder in the repo: absolute, ~/…, or relative to the working folder; default the working folder), branches?: [branch names to graph; default all], limit? (commits, default 100), commit? (a hash, branch, tag or \"WORKING\" to open for the user), marks?: [{ id, commit (a hash, branch, tag, \"HEAD\" or \"WORKING\"), note?, color? }] commits to point out"]
   "clojure"
   ["A Clojure project's namespaces as a map the user clicks through: bubbles for namespaces (or for prefixes, which open into their namespaces), arrows for :require, laid out in layers from entry points to leaves, across or down by the map's shape (an arrow going back is a circle, drawn red); a click on a namespace lists its vars with their arglists and docs, a click on a var shows its source, a click on an arrow lists the functions it uses. Read live with clj-kondo, so you send only the project and what to open or point out."
    "root? (the project folder: absolute, ~/…, or relative to the working folder; default the working folder), at? (a namespace prefix to open the map in, like \"features.orders\"), open? (a namespace, ns/var, or \"a -> b\" for the arrow between two bubbles of the map, to pick for the user), direction?: across|down (default: picked by the map's shape, deep maps run down), marks?: [{ id, ns, var?, note?, color? }] namespaces or vars to point out"]
   "calls"
   ["A Clojure project's function calls around one var: what calls what, and what that code is. Three tabs: Unfold (the var's source; each call to a project var is a link that opens that var's code nested under the calling line), Neighbours (the var in the middle, its callers on the left with the calling line, its callees on the right with their doc line; click to move), Map (columns by steps away, up to callers and down to callees, boxes coloured by folder, arrows for calls). Calls to .sql files named by a keyword (Iron Rainbow's sql/q) link to the file. Read live with clj-kondo, so you send only the project and the var. Pick it to show how code flows through functions; clojure for how namespaces depend on each other; files for one file's lines."
    "root? (the project folder: absolute, ~/…, or relative to the working folder; default the working folder), open (the start var, \"ns/var\", required; the end of a namespace is enough when only one matches, like \"state-gate/check\"), view?: unfold|neighbours|map (the tab shown, default unfold), up?, down? (the map's steps of callers and callees, default 2 and 3), marks?: [{ id, var (\"ns/var\"), line? (a row of its file), note?, color? }] vars or lines to point out (shown on the code and in the map)"]
   "editor"
   ["Code you and the user both edit: an example for them to change, an exercise, a draft to finish, a snippet to try. One tab per file, a real editor; the user types in it and presses Send, which hands you a <claudia-code> message with their diff against your version and their full code. While the user has unsent edits in a file, draw refuses new code for that file and gives you their code: merge your change into it and draw again with seen set to the revision it names. Pick it when the user should write or change code; files for code on disk to read."
    "files: [{ id, name (a file name: its extension picks the language), lang? (when the name has none), code, seen? (the user's edit revision your code merges, after a refusal) }] tabs in order, open? (the file id shown), note? (one line over the editor: what to try)"]
   "scene3d"
   ["Something best understood as a shape in space that the user can rotate and zoom: an architecture with layers, a mechanism, a building, a molecule, a product, a map with height. Predefined shapes placed in 3D, arrows between them, labels. Pick it when depth or a free viewpoint helps; a flat graph is better for plain boxes and arrows."
    "a picture holds one 3D scene: a new scene3d block takes the old one's place, so to show another model redraw the scene (a new id, or the same id with its parts and story replaced). parts: [{ id, shape: box|sphere|cylinder|cone|pyramid|torus|capsule|plane|ring|extrude|lathe|tube|rod|group|person|anatomy|code|text|folders, pos?: [x,y,z] (y is up, 1 unit is about a box), size?: number or [x,y,z], rot?: [x,y,z] degrees, color? (green|red|amber|blue|grey|violet or any CSS colour), label?, note?, material? (what the surface is made of, for real reflections: steel|brushed-steel|aluminium|chrome|copper|brass|iron|plastic|matte-plastic|rubber|enamel|ceramic|wood|glass; glass is see-through and never solid; a colour tints it), opacity?: 0..1, wire?: true, points? (extrude: [[x,y]…] outline pulled 1 unit deep, scale it with size; lathe: [[radius,y]…] profile spun round y; tube: [[x,y,z]…] path), parent? (a part or group id: this part's pos/rot are then relative to it and it follows it), ghost? (true: not solid), outline?: false (no ink outline), a group is an empty frame to hang parts on and turn together (a rotor and its blades), radius? (tube, rod), from?/to? ([x,y,z] ends of a rod: a cylinder between two points) , pose? (person, below) }]. A person is a real human body (rigged, clothed), standing at pos, facing +z, turned with rot [0, degrees, 0]; size is their height (default 1.75, so 1 unit is a metre around people); color tints the top, look: { skin, top, bottom, shoes, hair } any of them. pose: a name (stand, t, reach, wave, point, hands_on_hips, hold, walk, sit (on a seat 0.46 high), sit_floor, kneel, squat, bend, lie) or { base: name, then any of: hips, chest (where the base of the neck goes: the spine bends to it), pelvis: [x,y,z] tilt degrees (x + forward, − back: lie is -90), twist: degrees, look, handL, handR, footL, footR, elbowL, elbowR, kneeL, kneeR (points an elbow or knee bends towards), toesL, toesR }, every point [x,y,z] in the person's own space (x + their left, y up, z + in front of them, metres for 1.75: hips 0.95, shoulders 1.41, head top 1.75) or, for look/hands/feet, a part id to reach for or look at (a hand on a switch: handR: 'switch'). A part with parent '<person>.<joint>' (hips, chest, head, handL, handR, footL, footR) rides on that joint, its pos in the person's axes (a mug held: parent 'anna.handR', pos [0, 0, 0.06]); arrows and cameras can point at '<person>.<joint>' too. People are not solid (not overlap-checked). An anatomy part is real human anatomy (Z-Anatomy models: bones, muscles, nerves, ligaments): ref names the structures by English name (femur, adductor longus), Latin name (os femoris, nervus obturatorius), id (femur.r) or tag: a region (Right lower limb, Pelvis, Head), a compartment (Medial compartment of thigh), a nerve's territory (Obturator nerve: the muscles it supplies); ref may be a list; side?: right|left|both (default both); system?: skeletal|muscular|nervous|joints|attachment (attachment: the muscles' origin and insertion patches, red and blue, only when asked for). Every structure stands where it is in one 1.75 m body (metres, feet on y 0, facing +z, the body's right at -x), so anatomy parts fit together: give the parts of one body the same pos (moves it) and size (scales it). One structure keeps the part's id; several become parts '<id>:<structure id>' (focus or frame 'thigh:*'). Labels default to the Latin name with the English one as a note (label: false for none); colour by system unless given. Anatomy is not overlap-checked. Real code: the scene's root? (a folder: absolute, ~/…, or relative to the working folder) and ref? (a git branch, tag or commit, read instead of the disk) let parts show the project's own code. A code part is a dark slab with code painted on its front (+z) face in VS Code's colours, no labels: file (relative to root) with range? (\"146-167\" or a list of ranges, a ⋮ row between two; default the whole file) or code (inline text; start? its first line number; lang?), mark? (lines in amber: numbers or \"a-b\"), title? (its header, default the file), size (its width; the height follows its lines), over? (a part id: it floats above that part, lift? units over its top and clear of the parts in front of it, pos is then an offset, a thin line down to it; all slabs with over hang as one row at one height, never over each other, in the order of their parts). A text part is painted words standing up (rot [-90,0,0] lays them on the floor): text, size (letter height), color, weight?: bold, align?: left (pos is then its left end). A folders part is a folder of root as a flat map on the floor: every folder an area one thin step above its parent's with its name painted at its front edge, every file a flat tile inside it (its area by its share of the lines, its name painted on it when big enough), one colour per folder at the hue depth; path? (the folder, default root), size? (its width, default 20), max? (tiles, default 1500; deeper folders become one tile each), hue? (the folder depth that gets its own colour, default 1: 2 colours each folder inside the top ones). Its parts' ids are \"<map id>:<path>\" (a folder's ends in /), so a story can frame and focus \"map:src/app/*\" and code can float over a file (over: \"map:src/app/core.clj\", file then optional); clicking a file raises its first 60 lines above it. world?: \"construct\" sets the scene in an endless white room (fog, soft shadows, no grid). arrows?: [{ id, from, to (a part id, or [x,y,z]), via?: [[x,y,z]…] (it bends through these points), flow?: true (a stream of dashes runs along it: air, current, signal), label?, color? }] (they follow their parts when these move), legend?: [{ color, label }] (colour key chips in a corner: use one colour per job, always the same: heat red, cold air blue, motion green, control violet), script?: JavaScript source for what the data can't say, run in a locked-down separate engine (no window, no network; plain JavaScript and Math only, 40 ms per frame): define build() returning { parts?, arrows? } for parts made by loops and maths, and frame(t, step) (t = seconds, step = the story's current step, 0-based, -1 when there is none) returning { [partId]: { pos?, rot?, size?, color?, opacity?, from?/to? (rod), pose? (person: set at once) } } to move parts every frame; the user gets play/pause and speed buttons; build() may also return allow), allow?: [[idA, idB]…] pairs that are meant to touch or overlap (a pin inside a rod end; an id ending in * matches every id starting so), scan?: seconds of the script the user's scan button checks (default 12). story?: [{ say?: the line spoken in this step (shown as subtitles; a narrator voice reads it aloud when the user has turned it on, it is off by default), show?: ids to bring in (part or arrow ids; a trailing * matches a prefix, like blade*), hide?: ids to take away, focus?: ids that glow while the step lasts, camera?: { frame?: ids to bring into view, target?: an id or [x,y,z], dist?, az?: degrees round, el?: degrees up, read?: a part id (straight in front of its +z face, close enough to read a code slab) }, mark?: { <code part id>: lines marked while this step lasts }, hold?: minimum seconds, pose?: { <person id>: pose } (they glide into it), move?: seconds the glide takes (default 1.4) }] makes the scene a narrated build: parts the story shows stay hidden until their step (parts it never shows are there from the start), the user presses play and the steps run one after the other, each part fading in as it is spoken about, with pause, next, back and a dot per step; voice?: how the narrator sounds (like: a calm man), autoplay?: false keeps it waiting for the play button (a story starts by itself by default), narrate?: false turns the voice off. The default look is a chalk diagram: flat colour in bands and a thin light outline round every part; while a story step has focus, the other parts fade back and the focused ones get a thick amber outline, so name the focus in each step. The page checks that solid parts never pass through each other: parts that are translucent (opacity under 1), wire, plane, ring or ghost: true are housings and don't count; every other overlap glows red and is counted for the user, so build the model so nothing intersects (real clearances, split shafts) and list only intended overlaps in allow"]
   "flow"
   ["Something travelling through a system, step by step: a request through the layers, an order through checkout, a job through a queue. Stations side by side (with steps inside if one has them), cards carrying what travels slide between them frame by frame, a caption per frame; the user clicks back / next. Several scenarios (allowed vs refused, cache hit vs miss) to switch between. Use it when what moves and how it changes is the point; for a job's progress use pipeline."
    "stations: [{ id, label, note?, steps?: [{ id, label }] }] left to right, lanes?: [{ id, label }] one row per kind of thing that travels (default one), scenarios?: [{ id, label, frames }] or frames for one way through; a frame: { caption, cards?: [{ lane? (default the first), at (a station or step id), title?, body? (text, kept to a few lines), lang? (colours body), new? (a piece of body to mark as just added), color?, dim? }], on?: [station or step ids lit], leave?: [step ids, dashed: on the way out], skip?: [step ids, struck: never run] }. A lane's card slides when its at changes; a lane without a card in a frame fades out"]))

(defn help-lines
  "The forms part of the draw tool's description (src/forms.ts formsHelp): one line per form."
  [forms]
  (str/join "\n" (for [f forms :let [[use-when fields] (form-help f)] :when use-when]
                   (str "- " f ": " use-when " Data: " fields "."))))

(defn first-sentence
  "A form's when-line up to its first full stop (what forms news names it by)."
  [f]
  (first (str/split (first (form-help f)) #"(?<=\.)\s")))

(defn fingerprint
  "How a form is described now: a change tells Claude to read its data again."
  [f]
  (let [[use-when fields] (form-help f)] (str (hash (str use-when fields)))))

(def ^:private colour {:type "string" :enum ["green" "red" "amber" "blue" "grey" "violet"]})
(defn- s [description] {:type "string" :description description})
(defn- strings [description] {:type "array" :items {:type "string"} :description description})
(defn- obj [required props] (cond-> {:type "object" :properties props :additionalProperties false}
                              (seq required) (assoc :required required)))

(def ^:private node-item
  (obj ["id"] (array-map :id (s "Stable id, reused across turns")
                         :label {:type "string"}
                         :shape {:type "string" :enum ["circle" "box" "pill" "diamond"]
                                 :description "circle (default), box, pill, diamond (a decision)"}
                         :color colour
                         :note (s "One short line under the label")
                         :group (s "Id of a group frame to sit in")
                         :state {:type "string" :enum ["todo" "active" "done" "blocked"]})))

(def ^:private edge-item
  (obj ["from" "to"] (array-map :from {:type "string"} :to {:type "string"} :label {:type "string"}
                                :dashed {:type "boolean"} :color colour)))

(defn- block-item
  "One block of a draw call; forms: the ones on offer."
  [forms]
  (obj ["id"]
       (array-map
        :id (s "Stable block id, reused across turns")
        :name (s "Short telling name shown on the block's badge, e.g. \"Deploy steps\"")
        :kind {:type "string" :enum ["graph" "html"]
               :description "Only needed for a new graph block placed before its nodes come; html is assumed when html or template is given"}
        :form {:type "string" :enum (if (seq forms) (vec forms) ["none"]) :description "Makes this a form block of that kind"}
        :data {:type "object" :propertyNames {:type "string"} :additionalProperties {}
               :description "A form's data (see the forms in this tool's description): item lists are upserted by item id, other fields replaced"}
        :removeItems (strings "Ids of form items to remove")
        :template (s "Draw a saved template (by name) in this freeform block; fill it through parts")
        :width {:type "string" :enum ["full" "half"]
                :description "full (default) or half: two half blocks in a row sit side by side"}
        :before (s "Move or put this block before the block with this id; new blocks go last")
        :html (s "The block's page: a whole HTML page or just its body, with inline <style> and <script>. Runs sandboxed (no access to Claudia, no network except scripts/styles from cdn.jsdelivr.net, e.g. Chart.js or D3). Claudia's colours and fonts are set as CSS variables: --bg --panel --fg --muted --line --accent --green --red --amber --blue --grey --violet (each colour also as --<colour>-bg), --sans --mono --hand; the background is transparent. Elements with data-part=\"name\" can later be replaced through parts.")
        :parts {:type "object" :propertyNames {:type "string"} :additionalProperties {:type "string"}
                :description "New inner markup for the elements marked data-part=\"<name>\" (markup only: scripts in it do not run). The page is not reloaded"}
        :height {:type "number" :description "Not used: a page is always shown at its full height"})))

(defn- draw-schema
  "The draw tool's input (src/prompt.ts drawShape); forms: the ones on offer."
  [forms]
  {:type "object"
   :additionalProperties false
   :properties
   (array-map
    :title (s "Picture title, a few words")
    :clear {:type "boolean" :description "Start a fresh picture before applying the rest (new topic)"}
    :nodes {:type "array"
            :items node-item
            :description "Add or change nodes by id; fields left out stay as they were"}
    :edges {:type "array"
            :items edge-item
            :description "Add or change arrows; an arrow is identified by from + to"}
    :groups {:type "array"
             :items (obj ["id"] (array-map :id {:type "string"} :label {:type "string"} :color colour))}
    :graph (s "The graph block that nodes, edges, groups and remove apply to (made if missing). Default \"graph\"")
    :blocks {:type "array"
             :items (block-item forms)
             :description "Add or change blocks by id: HTML blocks, and the width/position of any block"}
    :removeBlocks (strings "Ids of whole blocks to remove")
    :remove (strings "Ids to remove from the graph block: nodes, groups, or arrows as \"from->to\""))})

(defn picture-mcp
  "The picture server's tools (src/prompt.ts pictureMcp). forms: the forms on offer here and now
  (deleted and not on this runner left out)."
  [forms]
  {:draw {:description (str "Change the picture on the right half of the user's screen: a canvas of named blocks, each a graph (nodes, arrows, groups), a predefined form or your own freeform HTML. Everything is upserted by id, so send only what changed: a few nodes, a few form items, one block, or one part of a freeform block.\n\n"
                            "Forms (blocks[].form + blocks[].data); pick one when it fits, before freeform:\n"
                            (help-lines forms))
          :inputSchema (draw-schema forms)}
   :templates {:description "List the saved templates (freeform blocks kept by name): name, description, parts."
               :inputSchema {:type "object" :properties {} :additionalProperties false}}
   :save_template {:description "Save or change a template: from a freeform block of the picture (block) and/or new html. Same name = change it."
                   :inputSchema (obj ["name"]
                                     (array-map
                                      :name (s "Short telling name, e.g. \"Plan usage tiles\"")
                                      :block (s "Id of a freeform block in the picture to take the page and parts from")
                                      :html (s "The page, as for a freeform block (replaces the old one)")
                                      :parts {:type "object" :propertyNames {:type "string"} :additionalProperties {:type "string"}
                                              :description "Default markup for its data-part elements"}
                                      :description (s "One line: what it shows and when to use it")))}})

(def workspace-mcp
  "The workspace server's tools (src/prompt.ts WORKSPACE_MCP, this version adds run_with_secret)."
  {:show_image {:description "Show a picture file in the chat (png, jpeg, gif or webp, up to 5 MB): a screenshot, a rendered image, a photo. The user sees it under your steps and you see it too. Use it when the user asks you to send or show an image; a diagram or explanation still goes in the picture on the right."
                :inputSchema (obj ["path"] (array-map :path (s "The image file (absolute, ~/…, or relative to the working folder)")
                                                      :caption (s "A few words about it")))}
   :show_video {:description "Show a video file in the chat (mp4, m4v, webm or mov, up to 1 GB): a screen recording, a rendered clip. The user plays it under your steps; you don't see it (show a frame with show_image when you need to look). The file is copied and kept with the chat."
                :inputSchema (obj ["path"] (array-map :path (s "The video file (absolute, ~/…, or relative to the working folder)")
                                                      :caption (s "A few words about it")))}
   :set_folder {:description "Set the project folder you are working in (absolute, ~/…, or relative to ~/Work). Returns that folder's CLAUDE.md and git status. Call it when you start on a project and whenever you switch projects."
                :inputSchema (obj ["path"] {:path (s "The project's root folder")})}
   :run_with_secret {:description "Run one shell command that needs a secret from 1Password, without you ever seeing the secret. The user is asked in Claudia to allow it (or to paste the value); the command then runs with sh -c in the working folder, with the value in the environment variable env_name only. Every occurrence of the value in the output is replaced by [secret]. Refused if the user says no or does not answer within 10 minutes."
                     :inputSchema (obj ["ref" "env_name" "reason" "command"]
                                       (array-map :ref (s "The 1Password reference: op://<vault>/<item>/<field>")
                                                  :env_name (s "The environment variable the command reads it from, e.g. GITHUB_TOKEN")
                                                  :reason (s "One line for the user: why it is needed")
                                                  :command (s "The shell command, using the variable (e.g. curl -H \"Authorization: Bearer $GITHUB_TOKEN\" ...)")))}})

(defn forms-prompt
  "This version's addition to the system prompt: Claude Code cuts long tool descriptions, so every form this
  runner offers is said here with its data too (the draw tool's description stays the live list)."
  [forms]
  (str "The forms of the draw tool and their data (blocks[].form + blocks[].data):\n" (help-lines forms)))

(def allowed-tools
  ["mcp__picture__draw" "mcp__picture__templates" "mcp__picture__save_template" "mcp__workspace__set_folder"
   "mcp__workspace__show_image" "mcp__workspace__show_video" "mcp__workspace__run_with_secret"])
