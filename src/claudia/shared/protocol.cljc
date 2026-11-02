(ns claudia.shared.protocol
  "What every program shares: JSON in and out (keys become keywords once, here), and the names in
  PROTOCOL.md.")

(defn read-json
  "JSON text -> ClojureScript data with keyword keys."
  [text]
  #?(:cljs (js->clj (js/JSON.parse text) :keywordize-keys true)
     :clj  (throw (ex-info "read-json is ClojureScript only" {}))))

(defn write-json
  "ClojureScript data -> JSON text (keywords become strings)."
  [data]
  #?(:cljs (js/JSON.stringify (clj->js data))
     :clj  (throw (ex-info "write-json is ClojureScript only" {}))))

(def page->runner
  #{"hello" "open" "close" "new" "send" "stop" "answer" "secret" "deny" "rename" "archive" "seen"})

(def runner->page
  #{"chats" "chat" "created" "msg" "delta" "scene" "turn" "ask" "asked" "error"})

(def read-only-ok
  "What a read-only viewer may send on the socket."
  #{"hello" "open" "close"})

(def forms
  "The forms the draw tool offers in this version."
  ["pipeline" "timeline" "compare" "chart" "stats"])
