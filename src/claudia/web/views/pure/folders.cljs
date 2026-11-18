(ns claudia.web.views.pure.folders
  "The Folders modal's data (README decisions 61 to 64), pure: the tree's nodes from the folders
  loaded so far, the folders opened at first, the filter field, the detail panel's lines, and what
  the delete dialog says about work not pushed."
  (:require [clojure.string :as str]
            [claudia.shared.bookmarks :as bookmarks]
            [claudia.web.format :as format]))

(def folder-icon
  "A folder, drawn in the chalk line (the tree's rows, the header's Folders button)."
  [:svg.fd-icon {:viewBox "0 0 16 16" :aria-hidden true}
   [:path {:d "M1.5 4a1 1 0 0 1 1-1h3.6l1.4 1.6h6a1 1 0 0 1 1 1V12a1 1 0 0 1-1 1h-11a1 1 0 0 1-1-1z"}]])

(defn parent-of
  "An absolute path's parent folder (\"/\" for a top-level one), nil for \"/\"."
  [p]
  (when (and (string? p) (not= p "/"))
    (let [i (str/last-index-of p "/")]
      (if (pos? i) (subs p 0 i) "/"))))

(defn lineage
  "\"/\" and every folder down to p, p last: \"/a/b\" -> [\"/\" \"/a\" \"/a/b\"]."
  [p]
  (vec (reverse (take-while some? (iterate parent-of p)))))

(defn base-name "A path's last part (\"/\" for the root)." [p]
  (bookmarks/label-of {:folder p}))

(defn- shown-entries [entries hidden?]
  (if hidden? entries (remove :hidden entries)))

(defn tree-node
  "The tree node of folder p: {:id :name :loaded :pinned :children}, children from loaded (path ->
  entries, dot folders only when hidden?); [] while not loaded, so it can be opened (react-arborist's
  internal node); pinned: the bookmarked folders (a set); depth guards the walk."
  [{:keys [loaded hidden? pinned] :as m} p depth]
  (let [entries (when (< depth 64) (get loaded p))]
    {:id p
     :name (base-name p)
     :loaded (some? entries)
     :pinned (contains? pinned p)
     :children (mapv #(tree-node m (:path %) (inc depth)) (shown-entries entries hidden?))}))

(defn tree-data
  "The whole tree from \"/\", as react-arborist takes it (one root node). m: {:loaded :hidden?
  :pinned}."
  [m]
  [(tree-node m "/" 0)])

(defn name-matches?
  "The filter field's match: the folder's name holds term (case folded); the path is not searched,
  or every folder below a match would match too."
  [folder-name term]
  (str/includes? (str/lower-case (str folder-name)) (str/lower-case (str/trim (str term)))))

(defn filter-term
  "What the filter field filters the tree by: its text, unless it is a path (starts with \"/\"),
  which is revealed instead (typed-path)."
  [text]
  (let [t (str/trim (str text))] (when-not (str/starts-with? t "/") t)))

(defn typed-path
  "The folder a pasted or typed absolute path names (cleaned: no trailing slash), or nil."
  [text]
  (bookmarks/clean-folder (str/trim (str text))))

(defn open-map
  "react-arborist's first open state: every folder down to cwd open."
  [cwd]
  (into {} (map (fn [p] [p true])) (lineage cwd)))

;; ---- the detail panel ----

(defn git-text
  "A repo summary in one line: \"main · 3 not committed · 2 ahead · 1 behind\" (\"clean\" when
  nothing), nil for none."
  [{:keys [branch dirty ahead behind] :as g}]
  (when g
    (let [parts (cond-> []
                  (pos? dirty) (conj (str dirty " not committed"))
                  (pos? ahead) (conj (str ahead " ahead"))
                  (pos? behind) (conj (str behind " behind")))]
      (str/join " · " (cons (or branch "detached") (if (seq parts) parts ["clean"]))))))

(defn size-text
  "A folder's size for its tile: the size, \"measuring…\" while du runs, \"unknown\" otherwise."
  [{:keys [size measuring]}]
  (cond (number? size) (format/bytes-text size)
        measuring "measuring…"
        :else "unknown"))

(defn- repo-lines
  "What is only here in one repo summary g, as lines (its folder named when named? is true)."
  [{:keys [top dirty ahead noUpstream]} named?]
  (let [where (when named? (str (format/tilde top) ": "))]
    (cond-> []
      (pos? dirty) (conj (str where dirty " file" (when (not= 1 dirty) "s") " not committed"))
      (pos? ahead) (conj (str where ahead " commit" (when (not= 1 ahead) "s") " not pushed"))
      (seq noUpstream) (conj (str where "branches without an upstream: " (str/join ", " noUpstream))))))

(defn unpushed-lines
  "The delete dialog's warnings from a folder's facts: work in its repo, and in repos inside it,
  that exists only here."
  [{:keys [git inner]}]
  (vec (concat (when git (repo-lines git false))
               (mapcat #(repo-lines % true) inner))))

;; ---- bookmarks on the screen ----

(defn bookmark-rows
  "The bookmarks of server as the left column shows them: {:folder :label :missing}."
  [items server missing]
  (mapv (fn [b] {:folder (:folder b) :label (bookmarks/label-of b) :missing (contains? missing (:folder b))})
        (bookmarks/on-server items server)))
