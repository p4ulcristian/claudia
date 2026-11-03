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
(defn- lang-of [f] (if-let [l (get langs (lang-key f))] (l) #js []))

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

(defn- file-list [v] (if (sequential? v) (filterv #(string? (:id %)) v) []))
(defn- shown [f] (or (:edit f) (:code f) ""))

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
        ;; Per file: its editor state (undo kept across tabs), what the picture last had (Claude's
        ;; version v and the text), its language compartment and a pending save.
        mem (h/use-ref nil)
        _ (when-not (.-current mem)
            (set! (.-current mem) #js {:states (js/Map.) :synced (js/Map.) :langs (js/Map.) :timers (js/Map.)
                                       :access (Compartment.) :live nil :send nil}))
        ^js m (.-current mem)
        access-of (fn [on] #js [(.of (.-readOnly EditorState) (not on)) (.of (.-editable EditorView) on)])
        _ (set! (.-live m) #js {:editable editable :openId open-id :server server :chat chat :block block})
        doc-of (fn [id] (let [^js st (if (and (= id (.. m -live -openId)) (.-current view))
                                       (.-state (.-current view))
                                       (.get (.-states m) id))]
                          (if st (.toString (.-doc st)) "")))
        save (fn [id]
               (some-> (.get (.-timers m) id) js/clearTimeout)
               (.delete (.-timers m) id)
               (let [^js s (.get (.-synced m) id)
                     ^js l (.-live m)
                     text (doc-of id)]
                 (when (and s (.-editable l) (not= text (.-text s)))
                   (set! (.-text s) text)
                   (ws/send! (.-server l) {:type "code_edit" :chat (.-chat l) :block (.-block l) :file id :text text :base (.-v s)}))))
        make (fn [f]
               (let [lang (Compartment.)
                     id (:id f)]
                 (.set (.-langs m) id lang)
                 (.create EditorState
                          #js {:doc (shown f)
                               :extensions
                               #js [(lineNumbers) (highlightActiveLineGutter) (highlightActiveLine) (drawSelection) (history)
                                    (indentOnInput) (bracketMatching) (closeBrackets) (highlightSelectionMatches)
                                    (syntaxHighlighting dark-plus) (.of lang (lang-of f)) theme (.-lineWrapping EditorView)
                                    (.of (.-access m) (access-of editable))
                                    (.of keymap (.concat #js [#js {:key "Mod-Enter" :run (fn [] ((.-send m)) true)}]
                                                         closeBracketsKeymap defaultKeymap searchKeymap historyKeymap #js [indentWithTab]))
                                    (.of (.-updateListener EditorView)
                                         (fn [^js u]
                                           (when (and (.-docChanged u) (not (every? #(.annotation ^js % remote) (array-seq (.-transactions u)))))
                                             (some-> (.get (.-timers m) id) js/clearTimeout)
                                             (.set (.-timers m) id (js/setTimeout #(save id) save-ms))
                                             (set-tick inc))))]})))
        files-key (pr-str (map (juxt :id :v :edit :code lang-key) files))]
    (h/use-effect (fn [] (when (string? (:open data)) (set-open (:open data))) nil) [(:open data)])
    ;; The picture changed: bring each file's editor up to it, without losing typing not saved yet.
    (h/use-effect
     (fn []
       (let [ids (set (map :id files))]
         (doseq [id (vec (es6-iterator-seq (.keys (.-states m)))) :when (not (ids id))]
           (.delete (.-states m) id) (.delete (.-synced m) id) (.delete (.-langs m) id))
         (doseq [f files
                 :let [id (:id f) text (shown f) v (or (:v f) 0) lk (lang-key f)
                       ^js s (.get (.-synced m) id)]]
           (if-not s
             (do (.set (.-states m) id (make f))
                 (.set (.-synced m) id #js {:v v :text text :lang lk}))
             (let [local (doc-of id)
                   ;; Claude's new code replaces the text (undo brings back what was typed); another
                   ;; page's typing only when nothing typed here waits to be saved.
                   replace? (if (not= (.-v s) v) true (and (not= text (.-text s)) (= local (.-text s))))
                   _ (when (not= (.-v s) v) (some-> (.get (.-timers m) id) js/clearTimeout) (.delete (.-timers m) id))
                   effects (if (not= lk (.-lang s)) #js [(.reconfigure ^js (.get (.-langs m) id) (lang-of f))] #js [])
                   change? (and replace? (not= local text))]
               (when (or change? (pos? (.-length effects)))
                 (let [spec (cond-> {:effects effects :annotations (.of remote true)}
                              change? (assoc :changes {:from 0 :to (count local) :insert text}))
                       spec (clj->js spec)]
                   (if (and (= id open-id) (.-current view))
                     (.dispatch (.-current view) spec)
                     (.set (.-states m) id (.-state (.update ^js (.get (.-states m) id) spec))))))
               (.set (.-synced m) id #js {:v v :text (if (or replace? (= local text)) text (.-text s)) :lang lk}))))
         (set-tick inc))
       nil)
     [files-key])
    ;; Editable only on the live picture of the user's own chat.
    (h/use-effect
     (fn []
       (let [spec #js {:effects (.reconfigure (.-access m) (access-of editable))}]
         (doseq [id (vec (es6-iterator-seq (.keys (.-states m))))
                 :when (not (and (= id open-id) (.-current view)))]
           (.set (.-states m) id (.-state (.update ^js (.get (.-states m) id) spec))))
         (some-> (.-current view) (.dispatch spec)))
       nil)
     [editable])
    ;; One view; a tab switch swaps in that file's state.
    (h/use-effect
     (fn []
       (when-let [el (.-current host)]
         (when-let [state (and open-id (.get (.-states m) open-id))]
           (if-not (.-current view)
             (set! (.-current view)
                   (EditorView. #js {:state state :parent el
                                     :dispatchTransactions (fn [trs ^js v]
                                                             (.update v trs)
                                                             (.set (.-states m) (.. m -live -openId) (.-state v)))}))
             (when-not (identical? (.-state (.-current view)) state) (.setState (.-current view) state)))))
       nil)
     [open-id files-key])
    (h/use-effect
     (fn [] #(do (doseq [id (vec (es6-iterator-seq (.keys (.-timers m))))] (save id))
                 (some-> (.-current view) .destroy)
                 (set! (.-current view) nil)))
     [])
    (let [changed (set (for [f files :when (and (.has (.-states m) (:id f)) (not= (doc-of (:id f)) (or (:code f) "")))] (:id f)))
          do-send (fn []
                    (when (and editable (seq changed))
                      (doseq [f files] (save (:id f)))
                      (ws/send! server {:type "code_send" :chat chat :block block :note note})
                      (set-note "")))
          pick (fn [id]
                 (when (and (.-current view) open-id) (.set (.-states m) open-id (.-state (.-current view))))
                 (set-open id))]
      (set! (.-send m) do-send)
      (if (empty? files)
        [:p.f-note "No files in this editor yet."]
        [:div.f-editor
         (when (seq (:note data)) [:p.ed-note (:note data)])
         [:div.f-code
          [:div.f-code-head.ed-head
           [:div.ed-tabs {:role "tablist"}
            (for [f files]
              ^{:key (:id f)}
              [:button {:type "button" :role "tab" :aria-selected (= (:id f) open-id) :class (when (= (:id f) open-id) "on")
                        :on-click #(pick (:id f)) :title (when (changed (:id f)) "Changed: not sent yet")}
               (or (:name f) (:id f)) (when (changed (:id f)) [:span.ed-dot {:aria-label "changed"}])])]
           (when-not editable [:span.f-lang (if live "open this chat to edit" "read only")])]
          [:div.ed-host {:ref #(set! (.-current host) %)}]]
         (when editable
           [:div.ed-send
            [:input {:value note
                     :on-change #(set-note (.. % -target -value))
                     :on-key-down #(when (and (= "Enter" (.-key %)) (not (.-shiftKey %))) (.preventDefault %) (do-send))
                     :placeholder (if (seq changed) "A note for Claude (optional)" "Edit the code, then send it to Claude")}]
            [:button.primary {:type "button" :disabled (empty? changed) :on-click do-send :title "Send your edits to Claude (Ctrl+Enter in the code)"}
             (str "Send" (when (> (count changed) 1) (str " " (count changed) " files")))]])]))))
