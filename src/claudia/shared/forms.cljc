(ns claudia.shared.forms
  "The code forms (files, editor, flow, git): what Claude is told about each (when to use it, its
  data in one line, for the draw tool's description) and the sample the Forms gallery shows. Shared:
  the runner builds the draw tool from it, the page the gallery. Opt-in forms exist only on runners
  that name them in CLAUDIA_FORMS (claudia.slave.codeforms).")

(def specs
  "form name -> {:label :when :fields :sample :opt-in}, in the order the gallery shows them."
  (array-map
   "files"
   {:label "Files"
    :when "A project's files as a tree the user can browse and open: where things live, what a change touched, which files matter, and any code to show: open the file (file) with the lines that matter highlighted (lines) and short notes pinned to lines (notes) instead of explaining in the chat; code that isn't on disk (a proposal, a snippet) goes in sketch. Read live from disk (git status shown), so it stays current; you send only the folder and what to point out. For files that don't exist yet (a planned layout, a sketch of a library), send sketch: alone it is a made-up tree; with root it adds planned files to the real one. Each is labelled for the user (on disk / sketch / planned), so never present made-up files as real."
    :fields "root? (a folder: absolute, ~/..., or relative to the working folder; default the working folder), file? (the selected file, relative to root: shown open, whole, with only the folders down to it unfolded; without it the first marked file is selected), lines?: [line numbers or \"a-b\" ranges to highlight in that file; it scrolls to the first], notes?: [{ id, line, text, color? }] pinned under lines of that file, marks?: [{ id, path (relative to root), note?, color? }] files or folders to point out. Planned files: sketch: [{ path (relative to root when root is given), code?, status? }] (folders come from the paths), title? (what a root-less sketch is); marks work the same"
    :sample {:demo [{:path "src" :dir true}
                    {:path "src/orders" :dir true}
                    {:path "src/orders/total.ts" :status "M"
                     :code "export function total(lines: Line[]): number {\n  return lines.reduce((sum, l) => sum + l.qty * l.unitCents, 0);\n}\n"}
                    {:path "src/orders/total.test.ts" :status "U"}
                    {:path "src/shops.ts"}
                    {:path "README.md"}]
             :file "src/orders/total.ts"
             :lines ["2"]
             :notes [{:id "n" :line 2 :text "cancelled lines still count" :color "amber"}]
             :marks [{:id "t" :path "src/orders/total.ts" :note "the fix" :color "amber"}]}}

   "git"
   {:label "Git"
    :opt-in true
    :when "A repo's history as a live commit graph, like GitGud: branches in coloured lanes (local, remote or both, ahead/behind), HEAD, tags, the uncommitted changes as a dashed node above HEAD; the user clicks a commit for its files and diff. Read live from the repo and redrawn when it changes (a commit, a checkout, an edit), so you send only the repo and what to point out. Read only: it never changes the repo."
    :fields "repo? (a folder in the repo: absolute, ~/..., or relative to the working folder; default the working folder), branches?: [branch names to graph; default all], limit? (commits, default 100), commit? (a hash, branch, tag or \"WORKING\" to open for the user), marks?: [{ id, commit (a hash, branch, tag, \"HEAD\" or \"WORKING\"), note?, color? }] commits to point out"
    :sample {:demo {:head "c4"
                    :currentBranch "fix/tax"
                    :dirty {:total 2 :files [{:path "src/orders/total.ts" :status "M"} {:path "src/orders/tax.test.ts" :status "?"}]}
                    :commits [{:hash "c4" :parents ["c2"] :author "Paul" :when "20 minutes ago" :refs [{:type "head" :name "fix/tax"}] :subject "Tax on shipping too"}
                              {:hash "c3" :parents ["c1"] :author "Ananda" :when "5 hours ago" :refs [{:type "remote" :name "origin/shops"}] :subject "Shop list paging"}
                              {:hash "c2" :parents ["c1" "c0"] :author "Paul" :when "yesterday" :refs [{:type "branch" :name "main"}] :subject "Merge orders: quantities"}
                              {:hash "c0" :parents ["c1"] :author "Peter" :when "2 days ago" :refs [] :subject "Bill each coat's quantity"}
                              {:hash "c1" :parents [] :author "Paul" :when "3 days ago" :refs [{:type "tag" :name "v1.4"}] :subject "Orders: totals"}]}
             :marks [{:id "m" :commit "c0" :note "the quantity fix" :color "amber"}]}}

   "editor"
   {:label "Editor"
    :when "Code you and the user both edit: an example for them to change, an exercise, a draft to finish, a snippet to try. One tab per file, a real editor; the user types in it and presses Send, which hands you a <claudia-code> message with their diff against your version and their full code. While the user has unsent edits in a file, draw refuses new code for that file and gives you their code: merge your change into it and draw again with seen set to the revision it names. Pick it when the user should write or change code; files for code on disk to read."
    :fields "files: [{ id, name (a file name: its extension picks the language), lang? (when the name has none), code, seen? (the user's edit revision your code merges, after a refusal) }] tabs in order, open? (the file id shown), note? (one line over the editor: what to try)"
    :sample {:note "Make total() skip cancelled lines."
             :files [{:id "total" :name "total.ts"
                      :code "type Line = { qty: number; unitCents: number; cancelled?: boolean };\n\nexport function total(lines: Line[]): number {\n  return lines.reduce((sum, l) => sum + l.qty * l.unitCents, 0);\n}\n"}
                     {:id "test" :name "total.test.ts"
                      :code "import { expect, test } from \"bun:test\";\nimport { total } from \"./total\";\n\ntest(\"two coats\", () => {\n  expect(total([{ qty: 2, unitCents: 1500 }])).toBe(3000);\n});\n"}]}}

   "flow"
   {:label "Flow"
    :when "Something travelling through a system, step by step: a request through the layers, an order through checkout, a job through a queue. Stations side by side (with steps inside if one has them), cards carrying what travels slide between them frame by frame, a caption per frame; the user clicks back / next. Several scenarios (allowed vs refused, cache hit vs miss) to switch between. Use it when what moves and how it changes is the point; for a job's progress use pipeline."
    :fields "stations: [{ id, label, note?, steps?: [{ id, label }] }] left to right, lanes?: [{ id, label }] one row per kind of thing that travels (default one), scenarios?: [{ id, label, frames }] or frames for one way through; a frame: { caption, cards?: [{ lane? (default the first), at (a station or step id), title?, body? (text, kept to a few lines), lang? (colours body), new? (a piece of body to mark as just added), color?, dim? }], on?: [station or step ids lit], leave?: [step ids, dashed: on the way out], skip?: [step ids, struck: never run] }. A lane's card slides when its at changes; a lane without a card in a frame fades out"
    :sample {:stations [{:id "browser" :label "Browser" :note "the screen"}
                        {:id "api" :label "API" :note "checks" :steps [{:id "who" :label "who?"} {:id "may" :label "may they?"}]}
                        {:id "db" :label "Postgres" :note "the data"}]
             :lanes [{:id "req" :label "the request"} {:id "work" :label "work + replies"}]
             :scenarios
             [{:id "ok" :label "Anna (her shop)"
               :frames [{:caption "The screen asks to hand order 42 over." :cards [{:lane "req" :at "browser" :title "request" :body "POST /orders/42/hand" :color "grey"}]}
                        {:caption "The API reads the cookie: Anna is asking." :on ["who"] :cards [{:lane "req" :at "who" :title "request" :body "order 42\nperson: Anna" :new "person: Anna" :color "violet"}]}
                        {:caption "Is order 42 in her shop? A SELECT goes to Postgres." :on ["may"]
                         :cards [{:lane "req" :at "may" :title "request (waiting)" :body "order 42\nperson: Anna" :color "violet" :dim true}
                                 {:lane "work" :at "db" :title "SQL" :body "SELECT shop_id FROM orders\nWHERE id = 42" :lang "sql" :color "amber"}]}
                        {:caption "Shop 7: hers. The order is handed over." :on ["may"] :cards [{:lane "req" :at "may" :title "answer" :body "status: handed" :color "green"}]}
                        {:caption "The screen shows \"handed\"." :leave ["may" "who"] :cards [{:lane "req" :at "browser" :title "done" :body "status: handed" :color "green"}]}]}
              {:id "no" :label "Bob (not his)"
               :frames [{:caption "Bob asks to hand order 42 over." :cards [{:lane "req" :at "browser" :title "request" :body "POST /orders/42/hand" :color "grey"}]}
                        {:caption "The cookie says Bob; order 42 is in shop 7, not his." :on ["may"] :cards [{:lane "req" :at "may" :title "answer" :body "status: not found" :color "red"}]}
                        {:caption "Bob sees \"not found\". Nothing changed." :leave ["may" "who"] :cards [{:lane "req" :at "browser" :title "done" :body "status: not found" :color "red"}]}]}]}}))

(def names (vec (keys specs)))

(def opt-in
  "The forms a runner has only when CLAUDIA_FORMS names them."
  (set (for [[n s] specs :when (:opt-in s)] n)))

(defn help-line [n]
  (let [{:keys [when fields]} (get specs n)]
    (str "- " n ": " when " Data: " fields ".")))

(def base-samples
  "Samples for the forms that are not code forms (pipeline ... stats), for the gallery."
  {"pipeline" {:stages [{:id "build" :label "Build" :status "done" :note "41 s"}
                        {:id "test" :label "Test" :status "done" :note "212 passed"}
                        {:id "deploy" :label "Deploy" :status "running" :note "stage"}
                        {:id "verify" :label "Verify" :status "todo"}]}
   "timeline" {:events [{:id "a" :at "09:12" :label "Alert fired" :note "p95 over 2 s" :color "red"}
                        {:id "b" :at "09:20" :label "Rolled back" :status "done"}
                        {:id "c" :at "09:31" :label "Root cause found" :note "N+1 query in orders"}
                        {:id "d" :at "tomorrow" :label "Fix ships" :status "todo"}]}
   "compare" {:options [{:id "pwa" :label "Home Screen app" :pros ["minutes to ship" "updates instantly"] :cons ["Safari underneath"] :picked true}
                        {:id "native" :label "Native wrapper" :pros ["native extras later"] :cons ["hours of work" "TestFlight per update"]}]}
   "chart" {:mark "bar" :x "hour" :y "turns"
            :rows [{:id "0" :hour "0h" :turns 12} {:id "1" :hour "1h" :turns 19} {:id "2" :hour "2h" :turns 15}
                   {:id "3" :hour "3h" :turns 26} {:id "4" :hour "4h" :turns 14}]}
   "stats" {:tiles [{:id "five" :label "5-hour used" :value "85%" :progress 0.85 :color "amber"}
                    {:id "week" :label "Week used" :value "15%" :progress 0.15}
                    {:id "reset" :label "Resets in" :value "34m"}]}})

(def base-when
  {"pipeline" "Steps that run in order, each with a status: a deploy, CI, a migration, a multi-step job."
   "timeline" "What happened when, or what is planned by date: an incident, a history, a schedule."
   "compare" "Options side by side with their pros and cons, and the one picked (or recommended) marked."
   "chart" "Numbers to see as a shape: trends, comparisons, distributions. Bar, line, area or point."
   "stats" "A few numbers that matter right now, big: usage, counts, durations, a progress."})
