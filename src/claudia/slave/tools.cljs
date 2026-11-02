(ns claudia.slave.tools
  "What Claude is told: the system prompt's Claudia part and the tools the runner serves (their
  descriptions and input schemas, as plain JSON Schema for the host)."
  (:require [clojure.string :as str]
            [claudia.shared.protocol :as p]))

(def system-append
  (str/trim "
# Claudia

You are running inside Claudia. The screen has two halves:
- Left: the chat. Talk there like a friend: short, plain, one to three sentences. No headings, tables, long lists or walls of text in the chat. Don't describe or point at the picture (\"the boxes on the right show...\"): the user sees it.
- Right: the picture, which you control with the draw tool (mcp__picture__draw). The explaining lives there: what you are doing, how things connect, the plan, the options, what you found. Show most of it; say only a little.

The picture is a canvas of blocks, top to bottom (two \"half\" blocks sit side by side). There are three kinds, and each block wears a badge with its kind and its name:
- Graph blocks: circles, boxes, arrows and groups. The draw call's nodes, edges, groups and remove go to the graph block named by \"graph\" (\"graph\" if left out).
- Forms: predefined shapes for common things (pipeline, timeline, compare, chart, stats). Pick a form when one fits; it is faster to read and cheaper to update than your own HTML. Send only the items that changed (by id); removeItems drops some. Each form's data is in the draw tool's description.
- Freeform: your own small HTML page, for what no graph or form can show.

Give every block a short, telling name (blocks[].name, e.g. \"Deploy steps\", \"Latency by hour\"): the user sees it on the badge and uses it to ask for changes.

When to draw: whenever a turn changes something worth seeing (a new idea, a step done, a choice made, a file touched, a result found). Small talk leaves the picture as it is. Change only what changed and keep ids (blocks, nodes, parts) stable across turns, so the picture grows instead of jumping. Start a new picture (clear: true) when the topic changes.

How to draw well:
- Circles and boxes are things; arrows are how they connect or what flows. Label arrows with verbs when it helps.
- Use state to show progress: todo, active (what you're on now), done, blocked.
- Colours carry meaning: green = good / chosen / done, red = problem / risk, amber = caution / trade-off, blue = neutral focus, grey = background, violet = the user.
- Groups put related things in one frame (a server, a file, a phase).
- Keep a graph to what matters: about 3 to 12 nodes. Short labels (1 to 4 words); put the rest in note. Use more graph blocks for separate views, not one crowded graph.

Freeform blocks: a chart no form covers, sliders on a formula, UI mockups side by side, colours to compare. Use Claudia's CSS variables (--hand for headings) so it matches. Keep graphs and forms as the default. Keep each block small (a few KB) and self-contained. A page is shown at its full height and the picture scrolls, so never give it a fixed height or inner scroll areas.
To change a freeform block, don't resend it: mark what will change with data-part=\"name\" and send only parts: { name: \"<new markup>\" }. Send html again only when the layout itself changes.

When you need the user to choose, use AskUserQuestion: it shows as buttons, with each option's description (put pros and cons there) and your recommended option first. Don't also write the question out in the chat.

The draw call costs the user nothing to read, so prefer drawing over writing.

## Working folder
The chat runs in one fixed folder. When you start working in a project, call mcp__workspace__set_folder with its root folder; it hands you that project's CLAUDE.md and git status. Call it again whenever you move to another project. Run commands in the working folder (cd <folder> && ...).

## Secrets
When a command needs a password or token, never ask the user to paste it into the chat and never read it yourself. Call mcp__workspace__run_with_secret with the 1Password reference (op://vault/item/field), the environment variable name, the reason and the command: the user allows it in Claudia, the command runs with the value in that variable only, and the value is replaced by [secret] in the output you get."))

(def ^:private colour {:type "string" :enum ["green" "red" "amber" "blue" "grey" "violet"]})
(defn- s [description] {:type "string" :description description})
(defn- strings [description] {:type "array" :items {:type "string"} :description description})

(def ^:private forms-help
  {"pipeline" ["Steps that run in order, each with a status: a deploy, CI, a migration, a multi-step job. Update one stage as it moves."
               "stages: [{ id, label, status: todo|running|done|failed|skipped, note? }] in order"]
   "timeline" ["What happened when, or what is planned by date: an incident, a history, a schedule."
               "events: [{ id, at (a short time or date label), label, note?, status?: todo|running|done|failed|skipped, color? }] in time order"]
   "compare" ["Options side by side with their pros and cons, and the one picked (or recommended) marked."
              "options: [{ id, label, pros?: string[], cons?: string[], note?, picked?: boolean }]"]
   "chart" ["Numbers to see as a shape: trends, comparisons, distributions. Bar, line, area or point."
            "mark: bar|line|area|point, x: field name, y: field name, color?: field name (one series per value), title?, rows: [{ id, <x>: ..., <y>: ..., ... }]"]
   "stats" ["A few numbers that matter right now, big: usage, counts, durations, a progress."
            "tiles: [{ id, label, value, note?, progress?: 0..1, color? }]"]})

(def ^:private draw-description
  (str "Change the picture on the right half of the user's screen: a canvas of named blocks, each a graph (nodes, arrows, groups), a predefined form or your own freeform HTML. Everything is upserted by id, so send only what changed: a few nodes, a few form items, one block, or one part of a freeform block.\n\n"
       "Forms (blocks[].form + blocks[].data); pick one when it fits, before freeform:\n"
       (str/join "\n" (for [f p/forms :let [[when fields] (forms-help f)]]
                        (str "- " f ": " when " Data: " fields ".")))))

(def ^:private draw-schema
  {:type "object"
   :properties
   {:title (s "Picture title, a few words")
    :clear {:type "boolean" :description "Start a fresh picture before applying the rest (new topic)"}
    :graph (s "The graph block that nodes, edges, groups and remove apply to (made if missing). Default \"graph\"")
    :nodes {:type "array"
            :description "Add or change nodes by id; fields left out stay as they were"
            :items {:type "object"
                    :required ["id"]
                    :properties {:id (s "Stable id, reused across turns")
                                 :label {:type "string"}
                                 :shape {:type "string" :enum ["circle" "box" "pill" "diamond"]
                                         :description "circle (default), box, pill, diamond (a decision)"}
                                 :color colour
                                 :note (s "One short line under the label")
                                 :group (s "Id of a group frame to sit in")
                                 :state {:type "string" :enum ["todo" "active" "done" "blocked"]}}}}
    :edges {:type "array"
            :description "Add or change arrows; an arrow is identified by from + to"
            :items {:type "object"
                    :required ["from" "to"]
                    :properties {:from {:type "string"} :to {:type "string"} :label {:type "string"}
                                 :dashed {:type "boolean"} :color colour}}}
    :groups {:type "array"
             :items {:type "object"
                     :required ["id"]
                     :properties {:id {:type "string"} :label {:type "string"} :color colour}}}
    :remove (strings "Ids to remove from the graph block: nodes, groups, or arrows as \"from->to\"")
    :blocks {:type "array"
             :description "Add or change blocks by id: HTML blocks, forms, and the width/position of any block"
             :items {:type "object"
                     :required ["id"]
                     :properties {:id (s "Stable block id, reused across turns")
                                  :name (s "Short telling name shown on the block's badge, e.g. \"Deploy steps\"")
                                  :kind {:type "string" :enum ["graph" "html"]
                                         :description "Only needed for a new graph block placed before its nodes come; html is assumed when html is given"}
                                  :form {:type "string" :enum p/forms :description "Makes this a form block of that kind"}
                                  :data {:type "object" :additionalProperties {}
                                         :description "A form's data (see the forms in this tool's description): item lists are upserted by item id, other fields replaced"}
                                  :removeItems (strings "Ids of form items to remove")
                                  :width {:type "string" :enum ["full" "half"]
                                          :description "full (default) or half: two half blocks in a row sit side by side"}
                                  :before (s "Move or put this block before the block with this id; new blocks go last")
                                  :html (s "The block's page: a whole HTML page or just its body, with inline <style> and <script>. Runs sandboxed (no access to Claudia, no network except scripts/styles from cdn.jsdelivr.net). Claudia's colours and fonts are CSS variables: --bg --panel --fg --muted --line --accent --green --red --amber --blue --grey --violet (each colour also as --<colour>-bg), --sans --mono --hand; the background is transparent. Elements with data-part=\"name\" can later be replaced through parts.")
                                  :parts {:type "object" :additionalProperties {:type "string"}
                                          :description "New inner markup for the elements marked data-part=\"<name>\" (markup only: scripts in it do not run). The page is not reloaded"}}}}
    :removeBlocks (strings "Ids of whole blocks to remove")}})

(def mcp
  "The tools as the host's start spec wants them: {server: {tool: {description, inputSchema}}}."
  {:picture {:draw {:description draw-description :inputSchema draw-schema}}
   :workspace
   {:set_folder {:description "Set the project folder you are working in (absolute, ~/..., or relative to the chat's folder). Returns that folder's CLAUDE.md and git status. Call it when you start on a project and whenever you switch projects."
                 :inputSchema {:type "object" :required ["path"]
                               :properties {:path (s "The project's root folder")}}}
    :run_with_secret {:description "Run one shell command that needs a secret from 1Password, without you ever seeing the secret. The user is asked in Claudia to allow it (or to paste the value); the command then runs with sh -c in the working folder, with the value in the environment variable env_name only. Every occurrence of the value in the output is replaced by [secret]. Refused if the user says no or does not answer within 10 minutes."
                      :inputSchema {:type "object" :required ["ref" "env_name" "reason" "command"]
                                    :properties {:ref (s "The 1Password reference: op://<vault>/<item>/<field>")
                                                 :env_name (s "The environment variable the command reads it from, e.g. GITHUB_TOKEN")
                                                 :reason (s "One line for the user: why it is needed")
                                                 :command (s "The shell command, using the variable (e.g. curl -H \"Authorization: Bearer $GITHUB_TOKEN\" ...)")}}}}})

(def allowed-tools ["mcp__picture__draw" "mcp__workspace__set_folder" "mcp__workspace__run_with_secret"])
