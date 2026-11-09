(ns claudia.web.views.editor
  "The editor form: code Claude and the user both edit, in CodeMirror 6. The user's typing is saved
  into the picture as they type (code_edit); Send hands their edits to Claude as one message
  (code_send). Writable only on the open chat's own live picture."
  (:require ["@codemirror/state" :refer [Annotation Compartment EditorState]]
            ["@codemirror/view" :refer [EditorView keymap lineNumbers highlightActiveLine highlightActiveLineGutter drawSelection]]
            ["@codemirror/commands" :refer [defaultKeymap history historyKeymap indentWithTab]]
            ["@codemirror/language" :refer [bracketMatching indentOnInput syntaxHighlighting HighlightStyle StreamLanguage]]
            ["@codemirror/autocomplete" :refer [closeBrackets closeBracketsKeymap]]
            ["@codemirror/search" :refer [highlightSelectionMatches searchKeymap]]
            ["@lezer/highlight" :refer [tags]]
            ["@codemirror/lang-javascript" :refer [javascript]]
            ["@codemirror/lang-python" :refer [python]]
            ["@codemirror/lang-sql" :refer [sql]]
            ["@codemirror/lang-html" :refer [html]]
            ["@codemirror/lang-css" :refer [css]]
            ["@codemirror/lang-json" :refer [json]]
            ["@codemirror/lang-markdown" :refer [markdown]]
            ["@codemirror/lang-yaml" :refer [yaml]]
            ["@codemirror/lang-rust" :refer [rust]]
            ["@codemirror/lang-go" :refer [go]]
            ["@codemirror/lang-java" :refer [java]]
            ["@codemirror/lang-cpp" :refer [cpp]]
            ["@codemirror/legacy-modes/mode/clojure" :refer [clojure]]
            ["@codemirror/legacy-modes/mode/shell" :refer [shell]]
            ["@codemirror/legacy-modes/mode/toml" :refer [toml]]
            ["@codemirror/legacy-modes/mode/ruby" :refer [ruby]]
            ["@codemirror/legacy-modes/mode/swift" :refer [swift]]
            ["@codemirror/legacy-modes/mode/lua" :refer [lua]]
            [clojure.string :as str]
            [reagent.hooks :as h]
            [claudia.web.shiki :as shiki]
            [claudia.web.ws :as ws]))

(defn- legacy [mode] #(.define StreamLanguage mode))

(def ^:private langs
  (let [ts #(javascript #js {:typescript true})
        clj (legacy clojure) sh (legacy shell)]
    {"js" javascript "mjs" javascript "cjs" javascript "jsx" #(javascript #js {:jsx true})
     "ts" ts "mts" ts "tsx" #(javascript #js {:typescript true :jsx true})
     "javascript" javascript "typescript" ts
     "py" python "python" python "sql" sql "html" html "css" css "json" json
     "rs" rust "rust" rust "go" go "java" java
     "c" cpp "h" cpp "cpp" cpp "cc" cpp "hpp" cpp
     "md" markdown "markdown" markdown "yaml" yaml "yml" yaml
     "clj" clj "cljs" clj "cljc" clj "edn" clj "bb" clj "clojure" clj
     "sh" sh "bash" sh "zsh" sh "shell" sh
     "toml" (legacy toml) "rb" (legacy ruby) "ruby" (legacy ruby) "swift" (legacy swift) "lua" (legacy lua)}))

(defn- lang-key [f] (str/lower-case (or (:lang f) (last (str/split (str (:name f)) #"\.")) "")))
(defn- lang-of
  "File f's language and wrapping: long lines wrap, except Clojure's, which scroll sideways."
  [f]
  #js [(if-let [l (get langs (lang-key f))] (l) #js [])
       (if (shiki/clojure? (lang-key f)) #js [] (.-lineWrapping EditorView))])

;; VS Code's Dark+ colours, as in the files form.
(def ^:private dark-plus
  (.define HighlightStyle
           (clj->js
            [{:tag #js [(unchecked-get tags "keyword") (unchecked-get tags "bool") (unchecked-get tags "null") (unchecked-get tags "atom") (unchecked-get tags "tagName") (unchecked-get tags "self")] :color "#569cd6"}
             {:tag #js [(unchecked-get tags "controlKeyword") (unchecked-get tags "moduleKeyword")] :color "#c586c0"}
             {:tag #js [(unchecked-get tags "string") ((unchecked-get tags "special") (unchecked-get tags "string"))] :color "#ce9178"}
             {:tag (unchecked-get tags "regexp") :color "#d16969"}
             {:tag #js [(unchecked-get tags "number") (unchecked-get tags "integer") (unchecked-get tags "float")] :color "#b5cea8"}
             {:tag #js [(unchecked-get tags "comment") (unchecked-get tags "lineComment") (unchecked-get tags "blockComment")] :color "#6a9955"}
             {:tag #js [((unchecked-get tags "function") (unchecked-get tags "variableName")) ((unchecked-get tags "function") (unchecked-get tags "propertyName")) (unchecked-get tags "macroName")] :color "#dcdcaa"}
             {:tag #js [(unchecked-get tags "variableName") (unchecked-get tags "propertyName") (unchecked-get tags "attributeName") (unchecked-get tags "labelName")] :color "#9cdcfe"}
             {:tag #js [(unchecked-get tags "typeName") (unchecked-get tags "className") (unchecked-get tags "namespace")] :color "#4ec9b0"}
             {:tag #js [(unchecked-get tags "meta") (unchecked-get tags "processingInstruction")] :color "#9b9b9b"}
             {:tag (unchecked-get tags "heading") :color "#569cd6" :fontWeight "bold"}
             {:tag (unchecked-get tags "emphasis") :fontStyle "italic"}
             {:tag (unchecked-get tags "strong") :fontWeight "bold"}
             {:tag (unchecked-get tags "link") :color "#4ec9b0" :textDecoration "underline"}
             {:tag (unchecked-get tags "invalid") :color "#f44747"}])))

(def ^:private theme
  (.theme EditorView
          (clj->js
           {"&" {:color "#d4d4d4" :backgroundColor "transparent" :fontSize "12.5px"}
            "&.cm-focused" {:outline "none"}
            ".cm-scroller" {:fontFamily "var(--mono)" :lineHeight "1.6" :overflow "visible"}
            ".cm-content" {:caretColor "var(--accent)" :padding "6px 0"}
            ".cm-cursor, .cm-dropCursor" {:borderLeftColor "var(--accent)" :borderLeftWidth "2px"}
            ".cm-gutters" {:backgroundColor "transparent" :border "none" :color "color-mix(in srgb, var(--muted) 60%, transparent)"}
            ".cm-lineNumbers .cm-gutterElement" {:padding "0 8px 0 12px"}
            ".cm-activeLine" {:backgroundColor "color-mix(in srgb, var(--line) 22%, transparent)"}
            ".cm-activeLineGutter" {:backgroundColor "transparent" :color "var(--fg)"}
            "&.cm-focused > .cm-scroller > .cm-selectionLayer .cm-selectionBackground, .cm-selectionBackground, .cm-content ::selection"
            {:backgroundColor "color-mix(in srgb, var(--blue) 30%, transparent)"}
            ".cm-matchingBracket" {:backgroundColor "color-mix(in srgb, var(--accent) 22%, transparent)" :outline "none"}
            ".cm-selectionMatch" {:backgroundColor "color-mix(in srgb, var(--amber) 16%, transparent)"}
            ".cm-panels" {:backgroundColor "var(--panel)" :color "var(--fg)"}})
          #js {:dark true}))

;; A change that came from the picture (Claude's code, another page's typing), not from these keys.
(def ^:private remote (.define Annotation))
(def ^:private save-ms 300)


(defn file-list [v] (if (sequential? v) (filterv #(string? (:id %)) v) []))
(defn shown [f] (or (:edit f) (:code f) ""))

(defn sync-plan
  "How a file's editor follows the picture. synced: what the picture last had {:v :text}; f: the
  picture's file now; local: the editor's text. Claude's new code (a new v) replaces the text (undo
  brings back what was typed); another page's typing only when nothing typed here waits to be
  saved. {:replace :change (the text must change) :text (what the picture has, as kept)}."
  [synced f local]
  (let [text (shown f)
        v (or (:v f) 0)
        replace? (if (not= (:v synced) v) true (and (not= text (:text synced)) (= local (:text synced))))]
    {:replace replace?
     :change (and replace? (not= local text))
     :text (if (or replace? (= local text)) text (:text synced))}))

;; ---- the editors, kept per file in one mutable store (CodeMirror's states are objects) ----

(defn- new-mem []
  #js {:states (js/Map.) :synced (js/Map.) :langs (js/Map.) :timers (js/Map.)
       :access (Compartment.) :live nil :send nil})

(defn- access-of [on] #js [(.of (.-readOnly EditorState) (not on)) (.of (.-editable EditorView) on)])

(defn- doc-of
  "File id's text: from the view when it is the open one, else from its kept state."
  [^js m view id]
  (let [^js st (if (and (= id (.. m -live -openId)) (.-current view))
                 (.-state (.-current view))
                 (.get (.-states m) id))]
    (if st (.toString (.-doc st)) "")))

(defn- clear-timer! [^js m id]
  (some-> (.get (.-timers m) id) js/clearTimeout)
  (.delete (.-timers m) id))

(defn- save!
  "File id's typing into the picture (code_edit), when it changed since the picture last had it."
  [^js m view id]
  (clear-timer! m id)
  (let [^js s (.get (.-synced m) id)
        ^js l (.-live m)
        text (doc-of m view id)]
    (when (and s (.-editable l) (not= text (.-text s)))
      (set! (.-text s) text)
      (ws/send! (.-server l) {:type "code_edit" :chat (.-chat l) :block (.-block l) :file id :text text :base (.-v s)}))))

(defn- on-update
  "Typing (not a change from the picture) saves the file save-ms after the last key."
  [^js m view id set-tick]
  (fn [^js u]
    (when (and (.-docChanged u) (not (every? #(.annotation ^js % remote) (array-seq (.-transactions u)))))
      (some-> (.get (.-timers m) id) js/clearTimeout)
      (.set (.-timers m) id (js/setTimeout #(save! m view id) save-ms))
      (set-tick inc))))

(defn- make-state [^js m view set-tick editable f]
  (let [lang (Compartment.)
        id (:id f)]
    (.set (.-langs m) id lang)
    (.create EditorState
             #js {:doc (shown f)
                  :extensions
                  #js [(lineNumbers) (highlightActiveLineGutter) (highlightActiveLine) (drawSelection) (history)
                       (indentOnInput) (bracketMatching) (closeBrackets) (highlightSelectionMatches)
                       (syntaxHighlighting dark-plus) (.of lang (lang-of f)) theme
                       (.of (.-access m) (access-of editable))
                       (.of keymap (.concat #js [#js {:key "Mod-Enter" :run (fn [] ((.-send m)) true)}]
                                            closeBracketsKeymap defaultKeymap searchKeymap historyKeymap #js [indentWithTab]))
                       (.of (.-updateListener EditorView) (on-update m view id set-tick))]})))

(defn- update-state!
  "Applies spec to file id: through the view when it is open, else to its kept state."
  [^js m view open-id id spec]
  (if (and (= id open-id) (.-current view))
    (.dispatch ^js (.-current view) spec)
    (.set (.-states m) id (.-state (.update ^js (.get (.-states m) id) spec)))))

(defn- sync-file!
  "Brings file f's editor up to the picture, without losing typing not saved yet."
  [^js m view open-id f ^js s]
  (let [id (:id f) lk (lang-key f)
        local (doc-of m view id)
        {:keys [change text]} (sync-plan {:v (.-v s) :text (.-text s)} f local)
        effects (if (not= lk (.-lang s)) #js [(.reconfigure ^js (.get (.-langs m) id) (lang-of f))] #js [])]
    (when (not= (.-v s) (or (:v f) 0)) (clear-timer! m id))
    (when (or change (pos? (.-length effects)))
      (update-state! m view open-id id (clj->js (cond-> {:effects effects :annotations (.of remote true)}
                                                  change (assoc :changes {:from 0 :to (count local) :insert (shown f)})))))
    (.set (.-synced m) id #js {:v (or (:v f) 0) :text text :lang lk})))

(defn- sync-files!
  "The picture changed: files gone are dropped, new ones get an editor, the rest follow it."
  [^js m view set-tick editable files open-id]
  (let [ids (set (map :id files))]
    (doseq [id (vec (es6-iterator-seq (.keys (.-states m)))) :when (not (ids id))]
      (.delete (.-states m) id) (.delete (.-synced m) id) (.delete (.-langs m) id))
    (doseq [f files :let [^js s (.get (.-synced m) (:id f))]]
      (if-not s
        (do (.set (.-states m) (:id f) (make-state m view set-tick editable f))
            (.set (.-synced m) (:id f) #js {:v (or (:v f) 0) :text (shown f) :lang (lang-key f)}))
        (sync-file! m view open-id f s)))
    (set-tick inc)))

(defn- set-editable!
  "Every file's editor made writable or read only."
  [^js m view editable open-id]
  (let [spec #js {:effects (.reconfigure (.-access m) (access-of editable))}]
    (doseq [id (vec (es6-iterator-seq (.keys (.-states m))))
            :when (not (and (= id open-id) (.-current view)))]
      (.set (.-states m) id (.-state (.update ^js (.get (.-states m) id) spec))))
    (some-> ^js (.-current view) (.dispatch spec))))

(defn- show-file!
  "One view; a tab switch swaps in that file's state."
  [^js m view host open-id]
  (when-let [el (.-current host)]
    (when-let [state (and open-id (.get (.-states m) open-id))]
      (if-not (.-current view)
        (set! (.-current view)
              (EditorView. #js {:state state :parent el
                                :dispatchTransactions (fn [trs ^js v]
                                                        (.update v trs)
                                                        (.set (.-states m) (.. m -live -openId) (.-state v)))}))
        (when-not (identical? (.-state (.-current view)) state) (.setState ^js (.-current view) state))))))

(defn- close!
  "Leaving: pending saves go out, the view goes."
  [^js m view]
  (doseq [id (vec (es6-iterator-seq (.keys (.-timers m))))] (save! m view id))
  (some-> (.-current view) .destroy)
  (set! (.-current view) nil))

(defn- use-editors
  "The CodeMirror side: per file its editor state (undo kept across tabs), what the picture last had
  (Claude's version v and the text), its language compartment and a pending save. Returns the store."
  [{:keys [editable files open-id server chat block]} host view set-tick]
  (let [mem (h/use-ref nil)
        _ (when-not (.-current mem) (set! (.-current mem) (new-mem)))
        ^js m (.-current mem)
        files-key (pr-str (map (juxt :id :v :edit :code lang-key) files))]
    (set! (.-live m) #js {:editable editable :openId open-id :server server :chat chat :block block})
    ;; The picture changed: bring each file's editor up to it, without losing typing not saved yet.
    (h/use-effect (fn [] (sync-files! m view set-tick editable files open-id) nil) [files-key])
    ;; Editable only on the live picture of the user's own chat.
    (h/use-effect (fn [] (set-editable! m view editable open-id) nil) [editable])
    (h/use-effect (fn [] (show-file! m view host open-id) nil) [open-id files-key])
    (h/use-effect (fn [] #(close! m view)) [])
    m))

;; ---- the form ----

(defn- tabs [files open-id changed pick]
  [:div.ed-tabs {:role "tablist"}
   (for [f files]
     ^{:key (:id f)}
     [:button {:type "button" :role "tab" :aria-selected (= (:id f) open-id) :class (when (= (:id f) open-id) "on")
               :on-click #(pick (:id f)) :title (when (changed (:id f)) "Changed: not sent yet")}
      (or (:name f) (:id f)) (when (changed (:id f)) [:span.ed-dot {:aria-label "changed"}])])])

(defn- send-bar [note set-note changed do-send]
  [:div.ed-send
   [:input {:value note
            :on-change #(set-note (.. % -target -value))
            :on-key-down #(when (and (= "Enter" (.-key %)) (not (.-shiftKey %))) (.preventDefault %) (do-send))
            :placeholder (if (seq changed) "A note for Claude (optional)" "Edit the code, then send it to Claude")}]
   [:button.primary {:type "button" :disabled (empty? changed) :on-click do-send :title "Send your edits to Claude (Ctrl+Enter in the code)"}
    (str "Send" (when (> (count changed) 1) (str " " (count changed) " files")))]])

(defn- changed-ids
  "The files whose editor differs from Claude's code."
  [^js m view files]
  (set (for [f files :when (and (.has (.-states m) (:id f)) (not= (doc-of m view (:id f)) (or (:code f) "")))] (:id f))))

(defn- send-edits!
  "Send: every file's typing saved, then Claude is told (code_send) with the note."
  [m view server chat block files note set-note]
  (doseq [f files] (save! m view (:id f)))
  (ws/send! server {:type "code_send" :chat chat :block block :note note})
  (set-note ""))

(defn- keep-open-state!
  "Before a tab switch: the open file's state kept from the view."
  [^js m view open-id]
  (when (and (.-current view) open-id) (.set (.-states m) open-id (.-state (.-current view)))))

(defn editor-form
  "ctx: {:server :chat :block :live} (live: the open chat's own picture, writable)."
  [data {:keys [server chat block live]}]
  (let [editable (boolean (and live server chat block))
        files (file-list (:files data))
        [open set-open] (h/use-state (when (string? (:open data)) (:open data)))
        open-id (if (some #(= (:id %) open) files) open (:id (first files)))
        [note set-note] (h/use-state "")
        [_ set-tick] (h/use-state 0)
        host (h/use-ref nil)
        view (h/use-ref nil)
        _ (h/use-effect (fn [] (when (string? (:open data)) (set-open (:open data))) nil) [(:open data)])
        m (use-editors {:editable editable :files files :open-id open-id :server server :chat chat :block block} host view set-tick)
        changed (changed-ids m view files)
        do-send #(when (and editable (seq changed)) (send-edits! m view server chat block files note set-note))
        pick (fn [id] (keep-open-state! m view open-id) (set-open id))]
    (set! (.-send m) do-send)
    (if (empty? files)
      [:p.f-note "No files in this editor yet."]
      [:div.f-editor
       (when (seq (:note data)) [:p.ed-note (:note data)])
       [:div.f-code
        [:div.f-code-head.ed-head
         (tabs files open-id changed pick)
         (when-not editable [:span.f-lang (if live "open this chat to edit" "read only")])]
        [:div.ed-host {:ref #(set! (.-current host) %)}]]
       (when editable (send-bar note set-note changed do-send))])))
