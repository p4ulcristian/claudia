(ns claudia.slave.http
  "What the front and the runner share: this checkout's version and commit (for /api/me), and
  reading and answering HTTP requests."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:child_process" :as cp]
            [clojure.string :as str]))

;; out/<program>.js lives in the checkout's out folder.
(def checkout (path/resolve (path/dirname (aget (.-argv js/process) 1)) ".."))

(def version
  (try (.-version (js/JSON.parse (fs/readFileSync (path/join checkout "package.json") "utf8")))
       (catch :default _ nil)))

(def commit
  (try (str/trim (str (cp/execFileSync "git" #js ["-C" checkout "rev-parse" "--short" "HEAD"]
                                       #js {:stdio #js ["ignore" "pipe" "ignore"]})))
       (catch :default _ nil)))

(defn read-body
  "Promise of a request's body as text (up to max bytes; more is refused)."
  [^js req max]
  (js/Promise.
   (fn [resolve reject]
     (let [chunks #js [] size (atom 0)]
       (.on req "data" (fn [c]
                         (when (> (swap! size + (.-length c)) max)
                           (reject (js/Error. "body too large"))
                           (.destroy req))
                         (.push chunks c)))
       (.on req "end" #(resolve (.toString (js/Buffer.concat chunks) "utf8")))
       (.on req "error" reject)))))

(defn json-reply [^js res status body]
  (.writeHead res status #js {"content-type" "application/json" "cache-control" "no-store"})
  (.end res (js/JSON.stringify (clj->js body))))
