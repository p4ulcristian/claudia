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
  #{"hello" "open" "close" "new" "send" "stop" "answer" "secret" "deny" "rename" "archive" "folder" "seen"
    "stop_task" "settings" "agent" "agent_git" "diff" "picture" "older" "code_edit" "code_send"})

(def runner->page
  #{"chats" "chat" "created" "msg" "delta" "scene" "turn" "ask" "asked" "error"
    "agents" "changes" "context" "settings" "meta" "agent" "agent_git" "diff" "pictures" "picture" "older"
    ;; The working line's note (Thinking, Retrying…, Compacting); no note: plain working.
    "status"})

(def read-only-ok
  "What a read-only viewer may send on the socket."
  #{"hello" "open" "close" "agent" "agent_git" "diff" "picture" "older"})

(def forms
  "The forms the draw tool offers in this version."
  ["pipeline" "timeline" "compare" "chart" "stats" "scene3d"])
