(ns claudia.shared.anatomy
  "The anatomy models of 3D scenes (one small GLB per structure, from Z-Anatomy, CC BY-SA 4.0; in
  the repository p4ulcristian/claudia-anatomy): which version, and which paths may be asked for. The
  hub and the runner's render page serve them under /anatomy/<version>/, each file fetched once
  from that version and kept on disk."
  (:require [clojure.string :as str]))

(def repo "p4ulcristian/claudia-anatomy")
(def version "v1.0.3")

(defn path-ok?
  "Whether rel (after /anatomy/) is a file of this version: its index or one structure (a GLB
  mesh, or a nerve's or vessel's centre lines as JSON)."
  [rel]
  (boolean (and (not (str/includes? (str rel) ".."))
                (re-matches (re-pattern (str (str/replace version "." "\\.") "/(index\\.json|[a-z]+/[a-z0-9][a-z0-9.\\-]*\\.(glb|json))"))
                            (str rel)))))

(defn source-url
  "Where a path (version/file) is fetched from: the repository at that version's tag."
  [rel]
  (let [[v & more] (str/split (str rel) #"/")]
    (str "https://raw.githubusercontent.com/" repo "/" v "/models/" (str/join "/" more))))

(defn page-path "The page's path of a file of this version (index.json or a model)." [file]
  (str "/anatomy/" version "/" file))
