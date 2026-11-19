(ns claudia.web.views.pure.folders
  "The Folders modal's data (README decisions 61 to 64), pure: the tree's nodes from the folders
  loaded so far, the folders opened at first, the filter field, the contents pane's items, how a
  file is previewed and when a second click opens a folder, and the detail panel's lines."
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
  "The tree node of folder p: {:id :name :loaded :pinned :link :children}, children from loaded
  (path -> entries, dot folders only when hidden?); [] while not loaded, so it can be opened
  (react-arborist's internal node); pinned: the bookmarked folders (a set); link: the target when p
  is a symlink, left out otherwise; depth guards the walk (a link back up never ends)."
  [{:keys [loaded hidden? pinned] :as m} p depth link]
  (let [entries (when (< depth 64) (get loaded p))]
    (cond-> {:id p
             :name (base-name p)
             :loaded (some? entries)
             :pinned (contains? pinned p)
             :children (mapv #(tree-node m (:path %) (inc depth) (:link %)) (shown-entries entries hidden?))}
      link (assoc :link link))))

(defn tree-data
  "The whole tree from \"/\", as react-arborist takes it (one root node). m: {:loaded :hidden?
  :pinned}."
  [m]
  [(tree-node m "/" 0 nil)])

(defn link-title
  "A row's hover text: its path, and for a symlink where it points (\"broken\" when nowhere)."
  [p link]
  (cond (nil? link) p
        (= "" link) (str p "\nSymlink, broken")
        :else (str p "\nSymlink to " link)))

(def link-mark
  "The mark a symlink wears beside its icon: a bent arrow, drawn in the chalk line."
  [:svg.fd-link {:viewBox "0 0 12 12" :aria-label "symlink" :role "img"}
   [:path {:d "M3 2.5v3.5a2 2 0 0 0 2 2h4.5M7.5 5.5L10 8l-2.5 2.5"}]])

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

;; ---- the contents pane and the preview ----

(defn shown-items
  "A folder's contents as the pane shows them: dot entries only when hidden?."
  [entries hidden?]
  (vec (if hidden? entries (remove :hidden entries))))

(defn count-text
  "The contents pane's count: \"3 folders · 12 files\" (\"Empty\" for none), and how many were
  left out (more)."
  [items more]
  (let [dirs (count (filter :dir items))
        n (fn [k word] (str k " " word (when (not= 1 k) "s")))
        parts (cond-> []
                (pos? dirs) (conj (n dirs "folder"))
                (pos? (- (count items) dirs)) (conj (n (- (count items) dirs) "file"))
                (pos? more) (conj (str more " more not shown")))]
    (if (seq parts) (str/join " · " parts) "Empty")))

(def ^:private readme-re #"(?i)^readme(\.(md|markdown|txt))?$")

(defn readme-of
  "The folder's README among its contents (a Markdown one first), as an item, or nil."
  [entries]
  (first (sort-by #(if (re-find #"(?i)\.(md|markdown)$" (:name %)) 0 1)
                  (filter #(and (not (:dir %)) (re-matches readme-re (:name %))) entries))))

(def thumb-max "The largest image the grid draws as its own thumbnail (bigger ones show their tag)." (* 8 1024 1024))

(defn thumb?
  "True when the grid draws this item from its own bytes."
  [{:keys [kind size]}]
  (and (= kind "image") (number? size) (<= size thumb-max)))

(def text-max "The largest file the preview reads as text (the runner's limit)." 2000000)

(defn preview-way
  "How the preview shows an item: :folder, :image, :video, :audio, :pdf, :markdown, :text, or :none
  (too big to read as text)."
  [{:keys [dir kind size] item-name :name}]
  (cond dir :folder
        (#{"image" "video" "audio" "pdf"} kind) (keyword kind)
        (and (number? size) (> size text-max)) :none
        (re-find #"(?i)\.(md|markdown)$" (str item-name)) :markdown
        :else :text))

(def double-click-ms "How close two clicks on one folder come to open it." 450)

(defn opens?
  "True when click ({:path :at}) on item opens it: a folder, clicked right after last-click (the same path,
  within double-click-ms)."
  [item last-click click]
  (boolean (and (:dir item) last-click (= (:path last-click) (:path click))
                (< (- (:at click) (:at last-click)) double-click-ms))))

(defn view-mode
  "The contents pane's mode from what the page stored: :list, else :grid."
  [stored]
  (if (= stored "list") :list :grid))

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

;; ---- bookmarks on the screen ----

(defn bookmark-rows
  "The bookmarks of server as the left column shows them: {:folder :label :missing}."
  [items server missing]
  (mapv (fn [b] {:folder (:folder b) :label (bookmarks/label-of b) :missing (contains? missing (:folder b))})
        (bookmarks/on-server items server)))
