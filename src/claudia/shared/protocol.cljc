(ns claudia.shared.protocol
  "What every program shares: JSON in and out (keys become keywords once, here), and the names in
  PROTOCOL.md.")

(defn read-json
  "JSON text -> ClojureScript data with keyword keys."
  [text]
  #?(:cljs (js->clj (js/JSON.parse text) :keywordize-keys true)
     :clj  (throw (ex-info "read-json is ClojureScript only" {}))))

(defn write-json
  "ClojureScript data -> JSON text (keywords become strings). A key read from JSON that holds a slash
  (an AskUserQuestion answer keyed by its question, a chart field) became a namespaced keyword, so the
  whole keyword is written, not only its name."
  [data]
  #?(:cljs (js/JSON.stringify (clj->js data :keyword-fn #(subs (str %) 1)))
     :clj  (throw (ex-info "write-json is ClojureScript only" {}))))

(def page->runner
  #{"hello" "open" "close" "new" "claudia" "send" "stop" "answer" "secret" "deny" "rename" "archive" "folder" "seen"
    "stop_task" "settings" "agent" "agent_git" "picture" "older" "code_edit" "code_send"
    ;; The person's keys for list_secrets (README decision 77), answering a keys_ask.
    "keys"
    ;; A queued message taken back with its × (README decision 83).
    "unqueue"
    ;; The person touched this page (README decisions 21, 94): a new voice message plays only there.
    "here"
    ;; Voice messages heard to their end or swiped away (README decision 93).
    "heard"})

(def runner->page
  #{"chats" "chat" "created" "claudia" "msg" "delta" "scene" "turn" "ask" "asked" "error"
    "agents" "context" "settings" "meta" "agent" "agent_git" "pictures" "picture" "older"
    ;; The working line's note (Thinking, Retrying…, Compacting); no note: plain working.
    "status"
    ;; list_secrets wants the person's keys: the page fetches them and answers with keys.
    "keys_ask"
    ;; The chats' voice messages, every one waiting (README decision 93; replaces speak).
    "voices"})

(def read-only-ok
  "What a read-only viewer may send on the socket."
  #{"hello" "here" "open" "close" "agent" "agent_git" "picture" "older"})

(def ^:private max-here-ago
  "How long ago a page may say it was touched: a day (older or broken: never)."
  (* 24 60 60 1000))

(defn touched-at
  "When a `here` frame says its page was touched (now: this clock, ago: ms before, sent by the page;
  0 when missing or out of range, so it never wins)."
  [now ago]
  (if (and (number? ago) (<= 0 ago max-here-ago)) (- now ago) 0))

(defn last-touched
  "Of the pages cs, the one touched last (touched: its time), or nil when none was touched."
  [cs touched]
  (let [c (when (seq cs) (apply max-key touched cs))]
    (when (and c (pos? (touched c))) c)))

(def forms
  "The forms the draw tool offers in this version."
  ["graph" "pipeline" "timeline" "compare" "chart" "stats" "notes" "scene3d" "html"])
