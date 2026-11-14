(ns claudia.hub.announce
  "Telling every server and the gate that something is there to install (README, Hub operations:
  following main): a poke to each front (POST /api/update-check) and a line for the gate's root
  helper (HUB_UPDATE_REQUEST). Each only says \"look now\" or \"fetch this tree ahead\": what
  installs is fetched and verified by each machine itself."
  (:require [claudia.node.env :refer [env]]
            ["node:fs" :as fs]
            ["node:http" :as http]
            [claudia.hub.config :as config]
            [claudia.shared.protocol :as protocol]))

(defn poke!
  "POST /api/update-check on one front (body: nil, or {:stage tree}, {:main commit}, with
  :releases when the hub has release files); logs the answer."
  [server body]
  (let [text (if body (protocol/write-json body) "")
        ^js req (http/request #js {:host (:host server) :port (:port server) :method "POST"
                                   :path "/api/update-check" :timeout 5000
                                   :headers #js {"content-type" "application/json"
                                                 "content-length" (js/Buffer.byteLength text)}}
                              (fn [^js res]
                                (.resume res)
                                (js/console.log (str "announce: poked " (:name server) ": " (.-statusCode res)))))]
    (.on req "timeout" #(.destroy req (js/Error. "timeout")))
    (.on req "error" #(js/console.log (str "announce: poked " (:name server) ": " (.-message %))))
    (.end req text)))

(defn releases-url
  "Where servers download release files from (HUB_PUBLIC_URL + /hub/releases/files), or nil when the
  hub names no https address of its own."
  []
  (when-let [base (re-matches #"^https://[A-Za-z0-9.-]+(:\d+)?$" (str (env "HUB_PUBLIC_URL" "")))]
    (str (first base) "/hub/releases/files")))

(defn poke-all!
  "Every server poked with body (the release files' address added when the hub has one)."
  [body]
  (let [url (releases-url)
        b (cond-> body (and body url) (assoc :releases url))]
    (doseq [s (config/all-servers)] (poke! s b))))

(defn ask-gate!
  "Adds a line for the gate's helper to HUB_UPDATE_REQUEST (a path unit starts it)."
  [line]
  (if-let [file (env "HUB_UPDATE_REQUEST" nil)]
    (do (fs/appendFileSync file (str line "\n"))
        (js/console.log (str "announce: asked the gate: " line)))
    (js/console.log (str "announce: HUB_UPDATE_REQUEST not set, so the gate was not asked: " line))))
