(ns claudia.slave.http
  "What the front and the runner share: this checkout's folder, version and commit (for /api/me).
  Reading and answering requests is claudia.node.http."
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
  ;; A release folder carries commit.txt (written by the release workflow); a checkout asks git.
  (or (try (some-> (fs/readFileSync (path/join checkout "commit.txt") "utf8") str/trim not-empty (subs 0 7))
           (catch :default _ nil))
      (try (str/trim (str (cp/execFileSync "git" #js ["-C" checkout "rev-parse" "--short" "HEAD"]
                                           #js {:stdio #js ["ignore" "pipe" "ignore"]})))
           (catch :default _ nil))))
