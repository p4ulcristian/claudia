(ns claudia.slave.codeforms
  "The runner's side of the code forms (files, git, editor, flow) and templates: which forms this
  runner has (opt-in ones by CLAUDIA_FORMS), what a draw call needs before it is applied (a template
  filled in, roots made absolute, the editor's guard), the page's reads (/api/chats/:id/files, /git,
  /api/forms, /api/templates) and the editor's frames (code_edit, code_send)."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [claudia.shared.protocol :as p]
            [claudia.slave.clojure :as clojure]
            [claudia.slave.editor :as editor]
            [claudia.slave.files :as files]
            [claudia.slave.git :as git]
            [claudia.slave.http :as h]
            [claudia.slave.scene :as scene]
            [claudia.slave.store :as store]
            [claudia.slave.tools :as tools]))

(def ^:private env (.-env js/process))

(def turned-on
  "Opt-in forms this runner has: CLAUDIA_FORMS=git,clojure,calls."
  (set (remove str/blank? (str/split (or (.-CLAUDIA_FORMS env) "") #"[\s,]+"))))

(def opt-in
  "Forms only runners that name them in CLAUDIA_FORMS have (the TypeScript Claudia's optIn)."
  #{"git" "clojure" "calls"})

(def all-forms
  "Every form this runner has, in the draw tool's order (src/forms.ts FORM_NAMES): opt-in ones only
  when CLAUDIA_FORMS names them."
  (vec (filter #(or (not (opt-in %)) (turned-on %)) (keys tools/form-help))))

(def not-here
  "Opt-in forms this runner doesn't have (/api/prefs notHere)."
  (vec (filter #(and (opt-in %) (not (turned-on %))) (keys tools/form-help))))

;; ---- the profile's preferences (/api/prefs) ----------------------------------------------------

(defn prefs
  "{:chatColors \"each\"|\"none\" :hiddenForms [forms the user deleted in the Forms window]}."
  []
  (let [p (store/kv "" "prefs")]
    {:chatColors (if (= "none" (:chatColors p)) "none" "each")
     :hiddenForms (vec (filter string? (:hiddenForms p)))}))

(defn set-prefs!
  "Changes the given preferences; returns them all."
  [next]
  (let [p (prefs)]
    (store/kv! "" "prefs"
               (cond-> p
                 (#{"each" "none"} (:chatColors next)) (assoc :chatColors (:chatColors next))
                 (sequential? (:hiddenForms next)) (assoc :hiddenForms (vec (filter string? (:hiddenForms next))))))
    (prefs)))

(defn hidden-forms
  "Forms Claude doesn't get here: the ones the user deleted and the opt-in ones not on this runner."
  []
  (into (set (:hiddenForms (prefs))) not-here))

;; ---- before a draw ----------------------------------------------------------------------------

(defn- home-path [s] (str/replace (str s) #"^~(?=/|$)" (os/homedir)))

(defn- known-block [scene id] (some #(when (= (:id %) id) %) (:blocks scene)))

(defn- form-of
  "The form a block of the call is or already was."
  [scene b]
  (or (:form b) (let [k (known-block scene (:id b))] (when (= "form" (:kind k)) (:form k)))))

(defn- with-root
  "A files/git block's folder made absolute (relative to the chat's folder), so the page can read
  inside it. k = :root or :repo. Left out on a block that has one, it stays. Claudia's own block
  (auto-files) is the user's live folder: a root left out or null keeps the one it has."
  [scene folder b k]
  (let [given (get-in b [:data k])
        given (when (string? given) given)
        known (known-block scene (:id b))]
    (cond
      (and (= "auto-files" (:id b)) (= k :root) (nil? given))
      (assoc-in b [:data k] (if (and (= "form" (:kind known)) (string? (get-in known [:data :root])))
                              (get-in known [:data :root])
                              folder))
      (get-in b [:data :demo]) b
      (and (nil? given) (or (= "form" (:kind known)) (and (= k :root) (sequential? (get-in b [:data :sketch]))))) b
      :else (assoc-in b [:data k] (path/resolve folder (home-path (or given ".")))))))

(defn prepare
  "A draw call's input made ready for claudia.slave.scene/apply-draw (src/chat.ts draw): templates
  filled in (the block keeps the template's name), roots made absolute, editor code guarded.
  Returns {:input :missing [template names not found] :notes [editor refusals for Claude]}."
  [scene input folder]
  (let [notes (atom [])
        missing (atom [])
        blocks
        (vec
         (keep
          (fn [b]
            (let [b (if (and (:template b) (nil? (:html b)))
                      (if-let [t (store/template (str (:template b)))]
                        (-> b (assoc :template (:name t) :html (:html t) :parts (merge (:parts t) (:parts b)))
                            (update :name #(or % (:name t))))
                        (do (swap! missing conj (str (:template b))) nil))
                      b)]
              (when b
                (case (form-of scene b)
                  "files" (with-root scene folder b :root)
                  "git" (with-root scene folder b :repo)
                  ("clojure" "calls") (with-root scene folder b :root)
                  "editor" (if (:data b)
                             (let [known (known-block scene (:id b))
                                   {:keys [data refused]} (editor/guard-draw (when (= "editor" (:form known)) (:data known)) (:data b))]
                               (swap! notes into (map #(str "Editor block \"" (:id b) "\", " %) refused))
                               (assoc b :data data))
                             b)
                  b))))
          (:blocks input)))]
    {:input (cond-> input (seq (:blocks input)) (assoc :blocks blocks))
     :missing @missing
     :notes @notes}))

;; ---- templates -------------------------------------------------------------------------------

(defn template-list []
  (let [all (store/templates)]
    (if (empty? all)
      "No templates saved yet."
      (str (str/join "\n" (for [t all]
                            (str "- \"" (:name t) "\": " (if (seq (:description t)) (:description t) "(no description)")
                                 (when (seq (:parts t)) (str " Parts: " (str/join ", " (map #(subs (str %) 1) (keys (:parts t)))) ".")))))
           "\nDraw one with blocks: [{ id, template: \"<name>\", parts? }]; change one with save_template."))))

(defn save-template!
  "Saves a template from a freeform block of scene and/or html; the text for Claude or the page."
  [scene {:keys [name block html parts description]}]
  (let [name (let [n (str/trim (str name))] (subs n 0 (min 80 (count n))))
        old (when (seq name) (store/template name))
        b (when block (known-block scene block))]
    (cond
      (not (seq name)) "A template needs a name."
      (and block (not= "html" (:kind b))) (str "No freeform block \"" block "\" in the picture.")
      :else
      (let [html (or html (:html b) (:html old))
            parts (if b (merge (:parts b) parts) (or parts (:parts old) {}))]
        (if-not (seq html)
          (str "No template \"" name "\" yet: give its html or a block to take it from.")
          (do (store/save-template! {:name (or (:name old) name) :html html :parts parts
                                     :description (or description (:description old) "")})
              (str (if old "Updated" "Saved") " template \"" (or (:name old) name) "\".")))))))

(def tools
  "The picture server's template tools, for the host's start spec."
  {:templates {:description "List the saved templates (freeform blocks kept by name): name, description, parts."
               :inputSchema {:type "object" :properties {}}}
   :save_template {:description "Save or change a template: from a freeform block of the picture (block) and/or new html. Same name = change it."
                   :inputSchema {:type "object" :required ["name"]
                                 :properties {:name {:type "string" :description "Short telling name, e.g. \"Plan usage tiles\""}
                                              :block {:type "string" :description "Id of a freeform block in the picture to take the page and parts from"}
                                              :html {:type "string" :description "The page, as for a freeform block (replaces the old one)"}
                                              :parts {:type "object" :additionalProperties {:type "string"} :description "Default markup for its data-part elements"}
                                              :description {:type "string" :description "One line: what it shows and when to use it"}}}}})

(def tool-names ["mcp__picture__templates" "mcp__picture__save_template"])

(def prompt
  "The system prompt's lines about code forms and templates."
  (str "Code always goes in the files form (the file opened, lines highlighted, notes pinned) or, when the user should change it, the editor form; never in freeform HTML.\n"
       "Templates: the user can save a freeform block as a named template (or ask you to: save_template). The templates tool lists them; draw one with blocks: [{ id, template: \"<name>\", parts }] and fill its parts. When the user asks to change a template, change it with save_template (same name), then draw it again."))

;; ---- the page's reads --------------------------------------------------------------------------

(defn- drawn
  "The values of field k of every block of form f in the scene."
  [scene f k]
  (set (for [b (:blocks scene) :when (and (= "form" (:kind b)) (= f (:form b)) (string? (get-in b [:data k])))]
         (get-in b [:data k]))))

(defn- root-ok?
  "A drawn root, a folder inside one, or one above it up to the home folder (the form's path can be
  clicked to go up)."
  [roots root]
  (let [home (os/homedir)]
    (some #(or (= % root)
               (str/starts-with? root (str % "/"))
               (and (str/starts-with? % (str root "/")) (or (= root home) (str/starts-with? root (str home "/")))))
          roots)))

(defn- query [^js req]
  (let [u (js/URL. (.-url req) "http://x")]
    (fn [k] (.get (.-searchParams u) k))))

(defn- files-read [^js res scene q]
  (let [root (or (q "root") "")
        rel (or (q "path") "")]
    (cond
      (not (root-ok? (drawn scene "files" :root) root)) (h/json-reply res 403 {:error "not a folder of this chat's picture"})
      (= "1" (q "read")) (h/json-reply res 200 (files/read-text root rel (some-> (q "since") js/Number)))
      (= "1" (q "image")) (if-let [abs (files/image-file root rel)]
                            (do (.writeHead res 200 #js {"content-type" (get files/mime (str/lower-case (path/extname abs)) "application/octet-stream")
                                                         "cache-control" "max-age=60"
                                                         "content-security-policy" "default-src 'none'; style-src 'unsafe-inline'"})
                                (.pipe (fs/createReadStream abs) res))
                            (h/json-reply res 404 {:error "not an image"}))
      :else (if-let [entries (files/list-dir root rel)]
              (h/json-reply res 200 {:entries entries})
              (h/json-reply res 404 {:error "no such folder"})))))

(defn- git-read [^js res scene q]
  (let [repo (or (q "repo") "")]
    (cond
      (not (turned-on "git")) (h/json-reply res 404 {:error "no git form here"})
      (not (contains? (drawn scene "git" :repo) repo)) (h/json-reply res 403 {:error "not a repo of this chat's picture"})
      :else (-> (if-let [rev (q "commit")]
                  (git/commit-detail repo rev (q "file"))
                  (git/graph repo {:fp (q "fp") :limit (q "limit") :branches (q "branches")}))
                (.then #(h/json-reply res 200 %))
                (.catch #(h/json-reply res 500 {:error (str (or (.-message %) %))}))))))

(defn- clojure-read [^js res scene q form]
  (let [root (or (q "root") "")
        reply (fn [p] (-> p (.then #(h/json-reply res 200 %)) (.catch #(h/json-reply res 500 {:error (str (or (.-message %) %))}))))]
    (cond
      (not (turned-on form)) (h/json-reply res 404 {:error (str "no " form " form here")})
      (not (contains? (drawn scene form :root) root)) (h/json-reply res 403 {:error "not a project of this chat's picture"})
      (= form "calls")
      (reply (let [v (q "var")]
               (cond (nil? v) (clojure/calls-fp root (q "fp"))
                     (= "1" (q "map")) (clojure/calls-map root v (let [n (js/parseInt (or (q "up") "2"))] (if (js/isNaN n) 2 n))
                                                          (let [n (js/parseInt (or (q "down") "3"))] (if (js/isNaN n) 3 n)))
                     :else (clojure/calls-var root v))))
      :else
      (reply (let [from (q "from") to (q "to") ns (q "ns") v (q "var")]
               (cond (and from to) (clojure/edge-uses root from (= "1" (q "fx")) to (= "1" (q "tx")))
                     (and ns v) (clojure/var-source root ns v)
                     ns (clojure/ns-detail root ns)
                     :else (clojure/namespaces root (q "fp"))))))))

(defn handle
  "The page's code-form calls; true when this was one. scene-of: chat id -> its scene (nil when
  there is no such chat). on-scene: a chat's scene changed by the page (the template button)."
  [^js req ^js res url who scene-of]
  (let [method (.-method req)
        [_ chat sub block] (re-matches #"/api/chats/([^/]+)/(files|git|clojure|calls|blocks/([^/]+)/template)" url)
        q (query req)]
    (cond
      (and (= url "/api/forms") (= method "GET"))
      (do (h/json-reply res 200 {:forms all-forms :hidden (:hiddenForms (prefs)) :templates (store/templates)}) true)

      (and (= url "/api/templates") (= method "GET"))
      (do (h/json-reply res 200 (store/templates)) true)

      (and (str/starts-with? url "/api/templates/") (= method "DELETE"))
      (do (if (:read-only who)
            (h/json-reply res 403 {:error "Read only."})
            (do (store/delete-template! (js/decodeURIComponent (subs url (count "/api/templates/"))))
                (h/json-reply res 200 {:ok true})))
          true)

      chat
      (let [scene (scene-of chat)]
        (cond
          (nil? scene) (h/json-reply res 404 {:error "No such chat."})
          (and (= sub "files") (= method "GET")) (files-read res scene q)
          (and (= sub "git") (= method "GET")) (git-read res scene q)
          (and (#{"clojure" "calls"} sub) (= method "GET")) (clojure-read res scene q sub)
          (and block (= method "POST"))
          (if (:read-only who)
            (h/json-reply res 403 {:error "Read only."})
            (-> (h/read-body req 65536)
                (.then (fn [body]
                         (let [{:keys [name description]} (p/read-json body)
                               text (save-template! scene {:name name :description description :block (js/decodeURIComponent block)})]
                           (h/json-reply res 200 {:ok (boolean (re-find #"^(Saved|Updated)" text)) :text text}))))
                (.catch #(h/json-reply res 400 {:error (str (.-message %))}))))
          :else (h/json-reply res 404 {:error "Not found."}))
        true)

      :else false)))

;; ---- the editor's frames ------------------------------------------------------------------------

(defn edit-block
  "The scene with the editor block's data changed by f (nil from f: no change), or nil."
  [scene block f]
  (let [b (known-block scene block)]
    (when (and (= "form" (:kind b)) (= "editor" (:form b)))
      (when-let [data (f (:data b))]
        (update scene :blocks (fn [bs] (mapv #(if (= (:id %) block) (assoc % :data data) %) bs)))))))

(defn code-edit
  "code_edit {block file text base}: the new scene or nil."
  [scene {:keys [block file text base]}]
  (when (and (string? block) (string? file) (string? text) (< (count text) 1000000))
    (edit-block scene block #(editor/user-edit % file text (let [n (js/Number base)] (if (js/isNaN n) 0 n))))))

(defn code-send
  "code_send {block note}: {:scene :text} or nil."
  [scene {:keys [block note]}]
  (let [out (atom nil)
        s (when (string? block)
            (edit-block scene block (fn [data]
                                      (when-let [r (editor/send-code block data (subs (str note) 0 (min 20000 (count (str note)))))]
                                        (reset! out (:text r))
                                        (:data r)))))]
    (when s {:scene s :text @out})))

;; ---- the form row: per chat, each kind allowed (default), required or off ------------------------

(def kinds-extra
  "Kinds in the form row that are not forms: graphs, freeform pages, questions."
  ["graph" "freeform" "questions"])

(def auto-forms
  "Forms that need no thinking: turned required, Claudia puts the chat folder's block on the picture."
  {"files" {:k :root :name "Project files"}
   "git" {:k :repo :name "History"}
   "clojure" {:k :root :name "Namespaces"}})

(defn- choices
  "A chat's form row choices with string keys (JSON made them keywords)."
  [settings]
  (into {} (for [[k v] (:forms settings) :when (#{"required" "off"} v)] [(if (keyword? k) (subs (str k) 1) (str k)) v])))

(defn chat-settings
  "A chat's settings as the page gets them: {:forms {kind choice} :cols n}."
  [id]
  (let [s (store/settings id)] {:forms (choices s) :cols (:cols s)}))

(defn off? [id kind] (= "off" (get (choices (store/settings id)) kind)))

(defn- off-set [ch] (set (for [[k v] ch :when (= v "off")] k)))
(defn- required-kinds [ch] (vec (for [[k v] ch :when (and (= v "required") (not= k "questions"))] k)))

(defn- kind-of-block [b]
  (case (:kind b) "form" (:form b) "html" "freeform" "graph"))

(defn- kind-of-call [scene b]
  (cond (:form b) (:form b)
        (or (some? (:html b)) (:template b)) "freeform"
        :else (if-let [k (known-block scene (:id b))] (kind-of-block k) (if (= "html" (:kind b)) "freeform" "graph"))))

(defn- forms-now
  "The forms a chat can draw now (src/chat.ts formsNow), in the draw tool's order, each with a
  fingerprint of how it is described: compared with what the chat's Claude last saw."
  [ch]
  (let [off (into (hidden-forms) (off-set ch))]
    (apply array-map (mapcat (fn [f] [f (tools/fingerprint f)]) (remove off all-forms)))))

(defn- forms-news
  "What changed in the forms since the chat's Claude last heard (seen nil: the whole list once)."
  [seen now]
  (let [seen (when seen (into {} (for [[k v] seen] [(if (keyword? k) (subs (str k) 1) (str k)) v])))
        line #(str % " (" (tools/first-sentence %) ")")]
    (if-not seen
      (str "Claudia's forms (blocks[].form) you can draw now: " (str/join "; " (map line (keys now)))
           ". Each one's data is in the draw tool's description; use them rather than hand-made HTML when they fit.")
      (let [added (remove #(contains? seen %) (keys now))
            changed (filter #(and (contains? seen %) (not= (seen %) (now %))) (keys now))
            gone (remove #(contains? now %) (keys seen))]
        (when (or (seq added) (seq changed) (seq gone))
          (str/join " " (remove nil? ["Claudia's forms changed since you last drew."
                                      (when (seq added) (str "New: " (str/join "; " (map line added)) "."))
                                      (when (seq changed) (str "Changed (read their data again in the draw tool's description): " (str/join ", " changed) "."))
                                      (when (seq gone) (str "Gone (draw refuses them): " (str/join ", " gone) "."))])))))))

(defn see-forms!
  "The chat's Claude hears of the forms as they stand now: the news since it last did (or nil), and
  they are kept as what it saw (src/chat.ts seeForms)."
  [id]
  (let [st (store/settings id)
        now (forms-now (choices st))
        news (forms-news (:forms-seen st) now)]
    (store/save-settings! id (assoc st :forms-seen now))
    news))

(defn forms-seen-now!
  "A session starts: its tools are the forms as they stand, so it knows them (no news)."
  [id]
  (let [st (store/settings id)]
    (store/save-settings! id (assoc st :forms-seen (forms-now (choices st))))))

(defn- kind-name [f]
  (case f
    "freeform" "freeform (your own HTML page)"
    "graph" "graphs (circles, boxes, arrows)"
    "questions" "questions (AskUserQuestion buttons: decide yourself instead)"
    (str "the " f " form")))

(defn forms-note
  "What the form row now asks, in words for Claude."
  [ch]
  (let [req (required-kinds ch)
        off (vec (off-set ch))]
    (str/join " " (remove nil?
                          [(when (seq req)
                             (str "The user made these REQUIRED in Claudia's form row: " (str/join ", " (map kind-name req))
                                  ". The picture must always hold at least one block of each: add " (if (> (count req) 1) "them" "it")
                                  " now where missing; draw refuses a change that takes the last one away. Every other kind not switched off stays allowed"
                                  (when (some #{"freeform"} req) "; freeform required means: be creative, build an interactive page where it helps") "."))
                           (when (seq off)
                             (str "Switched OFF: " (str/join ", " (map kind-name off)) "; don't use " (if (> (count off) 1) "them" "it") " (draw refuses)."))
                           (when (and (empty? req) (empty? off)) "The user reset Claudia's form row: choose forms yourself again.")]))))

;; chat id -> the form row note waiting for the next message (said once, hidden from the chat).
(defonce pending-notes (atom {}))

(defonce ^:private noted (atom #{}))

(defn- note-after-start!
  "After a runner start, choices made in the form row are said again with the first message."
  [id]
  (when-not (@noted id)
    (swap! noted conj id)
    (let [ch (choices (store/settings id))]
      (when (and (seq ch) (not (contains? @pending-notes id)))
        (swap! pending-notes assoc id (forms-note ch))))))

(defn take-note!
  "The text to send to Claude for a typed message (src/chat.ts send): the forms news (live? true: a
  session already ran with the old tools) and the form row's note go with it as a reminder after
  the text, said once and hidden from the chat."
  [id text live?]
  (note-after-start! id)
  (let [news (when live? (see-forms! id))
        row (get @pending-notes id)
        note (not-empty (str/join " " (remove str/blank? [news row])))]
    (swap! pending-notes dissoc id)
    (if note (str text "\n\n<system-reminder>" note "</system-reminder>") text)))

(defn- offered
  "The forms a chat's draw tool offers: this runner's, less the deleted ones and the row's off ones."
  [ch]
  (let [off (into (hidden-forms) (off-set ch))]
    (vec (remove off all-forms))))

(defn mcp-for
  "The tools for a chat (src/chat.ts tools): the draw tool's forms and description follow its form row."
  [id]
  (let [ch (choices (store/settings id))]
    {:picture (tools/picture-mcp (offered ch) (required-kinds ch) (contains? (off-set ch) "freeform"))
     :workspace tools/workspace-mcp}))

(defn prompt-for
  "The system prompt's Claudia part: src/prompt.ts SYSTEM_APPEND, then (beta) every form this runner
  offers with its data, since Claude Code cuts long tool descriptions. Not per chat: a change of the
  form row reaches Claude through the tools and a reminder, without a new prompt."
  []
  (str tools/system-append "\n\n" (tools/forms-prompt (vec (remove (hidden-forms) all-forms)))))

(defn draw
  "One draw call on a chat's scene, through the form row and the code forms (src/chat.ts draw):
  {:scene :changed :text} (the text for Claude) or {:error text}."
  [id scene input folder]
  (let [st (store/settings id)
        ch (choices st)
        hidden (into (hidden-forms) (off-set ch))
        {input :input notes :notes missing :missing} (prepare scene input folder)
        refused (atom [])
        blocks (filterv (fn [b]
                          ;; A block only moved, renamed or resized is no new drawing.
                          (let [touches (or (:form b) (:data b) (some? (:html b)) (:template b) (:parts b) (:kind b))
                                k (kind-of-call scene b)]
                            (if (and touches (hidden k)) (do (swap! refused conj k) false) true)))
                        (:blocks input))
        input (cond-> (assoc input :blocks blocks)
                (and (hidden "graph") (or (seq (:nodes input)) (seq (:edges input)) (seq (:groups input))))
                (-> (dissoc :nodes :edges :groups) (#(do (swap! refused conj "graph") %))))
        {s :scene changed :changed error :error} (scene/apply-draw scene input all-forms)]
    (if error
      {:error error}
      (let [;; Claudia's block of a required auto form is the user's: Claude's clear or removeBlocks
            ;; doesn't take it off (its last copy comes back, last).
            back (for [[f {:keys [k] nm :name}] auto-forms
                       :let [bid (str "auto-" f)]
                       :when (and (= "required" (ch f)) (not (hidden f)) (some #{f} all-forms)
                                  (not (some #(= (:id %) bid) (:blocks s))))]
                   (or (known-block scene bid) {:id bid :kind "form" :form f :name nm :data {k folder}}))
            s (update s :blocks into back)
            changed (into (vec changed) (map :id back))
            req (filterv #(not (hidden %)) (required-kinds ch))
            had (set (map kind-of-block (:blocks scene)))
            has (set (map kind-of-block (:blocks s)))
            lost (filterv #(and (had %) (not (has %))) req)
            absent (filterv #(not (has %)) req)]
        (if (seq lost)
          {:error (str "Not drawn: this change takes the last " (str/join ", " lost) " block out of the picture, and the user made "
                       (if (> (count lost) 1) "those" "that") " required in this chat (form row). Keep one, or put a new one in the same call.")}
          (let [news (see-forms! id)]
            {:scene s
             :changed changed
             :text (str "Picture updated: " (scene/describe s)
                        (when news (str "\n\n" news))
                        (when (seq missing) (str ". No template named " (str/join ", " (map #(str "\"" % "\"") missing)) " (see the templates tool)."))
                        (when (seq @refused) (str ". Not drawn: " (str/join ", " (distinct @refused)) ": switched off by the user; use another kind"))
                        (when (seq absent) (str ". Still missing, required by the user in this chat: " (str/join ", " absent) "; add " (if (> (count absent) 1) "them" "it") "."))
                        (when (seq notes) (str "\n\nNot drawn, kept for the user:\n" (str/join "\n\n" notes))))}))))))

(defn set-settings
  "The page changed a chat's form row or columns: {:settings (for the page) :scene (new, or nil)}.
  A form turned required that needs no thinking (files, git) shows its block at once; a kind
  turned off leaves the picture."
  [id scene folder {:keys [forms cols]}]
  (let [st (store/settings id)
        before (choices st)
        now (if (some? forms) (choices {:forms forms}) before)
        cols (if (some? cols) (max 0 (min 3 (or (js/parseInt cols) 0))) (:cols st))]
    (store/save-settings! id (assoc st :forms now :cols cols))
    (if-not (some? forms)
      {:settings {:forms now :cols cols} :scene nil}
      (let [add (for [[f {:keys [k] nm :name}] auto-forms
                      :let [bid (str "auto-" f)]
                      :when (and (= "required" (now f)) (not= "required" (before f)) (some #{f} all-forms)
                                 (not (known-block scene bid)))]
                  {:id bid :kind "form" :form f :name nm :data {k folder}})
            gone (set (concat (for [[f _] auto-forms :when (and (= "required" (before f)) (not= "required" (now f)))] (str "auto-" f))
                              (for [b (:blocks scene) :when (and (= "off" (now (kind-of-block b))) (not= "off" (before (kind-of-block b))))] (:id b))))]
        (swap! pending-notes assoc id
               (str (forms-note now)
                    (when (seq add) (str " Claudia already put " (str/join ", " (map #(str "a " (:form %) " block (id " (:id %) ": " (:name %) ")") add))
                                         " on the picture; update or keep " (if (> (count add) 1) "them" "it") ", don't draw " (if (> (count add) 1) "them" "it") " again."))))
        {:settings {:forms now :cols cols}
         :scene (when (or (seq add) (seq gone))
                  (update scene :blocks #(into (filterv (fn [b] (not (gone (:id b)))) %) add)))}))))
