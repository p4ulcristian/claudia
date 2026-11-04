(ns claudia.web.scene3d.folders
  "A folder of real code as a flat map on the floor, without three.js: every folder an area (a
  platform one thin step above its parent's, its name painted at its front edge), every file a flat
  tile inside its folder's area, as big as its share of the folder's lines (a squarified treemap),
  its name painted on it when it is big enough to read. Each folder at the hue depth has its own
  colour, so you see which part a file is in. Big trees are cut at a depth: deeper folders become one
  tile each. -> plain parts (a group, boxes and texts) the engine builds like any other."
  (:require [clojure.string :as str]))

;; ---- the tree ----

(defn under
  "The files under folder (\"\" = all) as [[path-inside lines]], 0-line files left out."
  [files folder]
  (let [pre (if (seq folder) (str (str/replace folder #"/+$" "") "/") "")]
    (vec (for [[p n] files :when (and (str/starts-with? p pre) (pos? n))] [(subs p (count pre)) n]))))

(defn- add-file [t [p n]]
  (let [segs (str/split p #"/")
        at (vec (interleave (repeat :dirs) (butlast segs)))
        add #(update % :files (fnil conj []) [(last segs) n])]
    (if (seq at) (update-in t at add) (add t))))

(defn- node
  "The nested map of one folder as a node: {:name :path :lines :dirs [nodes] :files [[name lines path]]}."
  [nm path m]
  (let [dirs (mapv (fn [[k v]] (node k (if (seq path) (str path "/" k) k) v)) (sort-by key (:dirs m)))
        fls (mapv (fn [[f n]] [f n (if (seq path) (str path "/" f) f)]) (:files m))]
    {:name nm :path path :dirs dirs :files fls
     :lines (+ (reduce + (map :lines dirs)) (reduce + (map second fls)))
     :count (+ (reduce + (map :count dirs)) (count fls))}))

(defn tree "The files [[path lines]] as the root node." [files]
  (node "" "" (reduce add-file {} files)))

(defn- tiles-at
  "How many tiles the tree has when cut at depth d (folders deeper than d become one tile)."
  [n d]
  (if (neg? d) 1 (+ (count (:files n)) (reduce + (map #(tiles-at % (dec d)) (:dirs n))))))

(defn cut-depth
  "The deepest cut (folders below it become one tile each) that keeps at most cap tiles."
  [root cap]
  (or (first (filter #(<= (tiles-at root %) cap) (range 12 -1 -1))) 0))

;; ---- the squarified treemap ----

(defn- worst [areas side]
  (let [s (reduce + areas)]
    (if (or (zero? s) (zero? side)) js/Infinity
        (max (/ (* side side (apply max areas)) (* s s)) (/ (* s s) (* side side (apply min areas)))))))

(defn- lay-row
  "One row of areas along the rect's short side: [the rects, the rect left over]."
  [areas {:keys [x z w d]}]
  (let [s (reduce + areas)]
    (if (>= w d)
      (let [cw (/ s (max d 1e-9))]
        [(second (reduce (fn [[off out] a] [(+ off (/ a cw)) (conj out {:x x :z (+ z off) :w cw :d (/ a cw)})]) [0 []] areas))
         {:x (+ x cw) :z z :w (- w cw) :d d}])
      (let [rh (/ s (max w 1e-9))]
        [(second (reduce (fn [[off out] a] [(+ off (/ a rh)) (conj out {:x (+ x off) :z z :w (/ a rh) :d rh})]) [0 []] areas))
         {:x x :z (+ z rh) :w w :d (- d rh)}]))))

(defn squarify
  "Rects for the weights (any order) filling rect {:x :z :w :d}, in the weights' order."
  [weights rect]
  (let [total (max 1e-9 (reduce + weights))
        scale (/ (* (:w rect) (:d rect)) total)
        order (sort-by #(- (nth weights %)) (range (count weights)))]
    (loop [todo order row [] rect rect out {}]
      (if (empty? todo)
        (let [[rs _] (lay-row (map #(* scale (nth weights %)) row) rect)
              all (into out (zipmap row rs))]
          (mapv all (range (count weights))))
        (let [i (first todo)
              side (min (:w rect) (:d rect))
              areas (map #(* scale (nth weights %)) row)
              with (concat areas [(* scale (nth weights i))])]
          (if (or (empty? row) (<= (worst with side) (worst areas side)))
            (recur (rest todo) (conj row i) rect out)
            (let [[rs left] (lay-row areas rect)]
              (recur todo [] left (into out (zipmap row rs))))))))))

;; ---- colours ----

(defn hue-of
  "The hue of folder i of n: each next folder about 137.5° further round the wheel (the golden angle),
  so folders side by side never get near the same colour, however many there are."
  [i _n]
  (js/Math.round (mod (* i 137.508) 360)))

(defn colour
  "A tile's or an area's colour in its top folder's hue (nil: grey, the root's own); deeper is darker."
  [hue level file?]
  (let [h (or hue 0)
        sat (cond (nil? hue) 0 file? 62 :else 30)]
    (if file?
      (str "hsl(" h ", " sat "%, " (- 64 (* 3 (min level 4))) "%)")
      (str "hsl(" h ", " sat "%, " (- 93 (* 4 (min level 5))) "%)"))))

;; ---- the parts ----

(defn- centre [{:keys [x z w d]} size] [(- (+ x (/ w 2)) (/ size 2)) (- (+ z (/ d 2)) (/ size 2))])

(defn- folder-id [cid path] (str cid ":" path "/"))
(defn- file-id [cid path] (str cid ":" path))

(defn- inner
  "A folder's rect inside its padding, the strip at its front (+z) for its name taken off."
  [{:keys [x z w d]} pad hdr]
  {:x (+ x pad) :z (+ z pad) :w (max 0 (- w (* 2 pad))) :d (max 0 (- d (* 2 pad) hdr))})

(defn- platform [{:keys [cid size step]} n level rect hue]
  (let [[cx cz] (centre rect size)]
    {:id (folder-id cid (:path n)) :shape "box" :ghost true :outline false :color (colour hue level false)
     :pos [cx (+ (* level step) (/ step 2)) cz] :size [(:w rect) step (:d rect)] :parent cid
     :note (str (or (not-empty (:path n)) "/") " · " (:count n) " files · " (:lines n) " lines")}))

(defn- name-text [{:keys [cid size step]} n level {:keys [x z w d]} pad hdr]
  (let [label (if (seq (:name n)) (:name n) (:path n))
        letter (min (* hdr 0.62) (/ (- w (* 2 pad)) (* 0.62 (max 1 (count label)))))]
    (when (and (seq label) (pos? hdr) (> letter (* size 0.003)))
      {:id (str (folder-id cid (:path n)) "#name") :shape "text" :text label :size letter :color "#1b1d22"
       :pos [(- (+ x pad (/ (* 0.62 letter (count label)) 2)) (/ size 2)) (+ (* (inc level) step) 0.002)
             (- (+ z d (- (/ pad 2)) (- (/ hdr 2))) (/ size 2))]
       :rot [-90 0 0] :parent cid})))

(defn- tile
  "A file (or a folder cut into one tile) lying flat on its folder's area at level."
  [{:keys [cid size step]} {:keys [id path note]} level rect hue]
  (let [[cx cz] (centre rect size)
        gap (min (* 0.09 (min (:w rect) (:d rect))) (* size 0.004))]
    (cond-> {:id id :shape "box" :ghost true :outline false :color (colour hue level true)
             :pos [cx (+ (* level step) (/ step 2)) cz] :size [(max 0.001 (- (:w rect) gap)) step (max 0.001 (- (:d rect) gap))]
             :note note :parent cid}
      path (assoc :file path :slab {:size (* size 0.34) :lift (* size 0.08)}))))

(defn- file-name
  "A file's name painted on its tile when it fits at a readable size (nil otherwise)."
  [{:keys [cid size step]} {:keys [id fname]} level rect]
  (let [letter (min (* (:d rect) 0.32) (/ (* 0.86 (:w rect)) (* 0.62 (max 1 (count fname)))))
        [cx cz] (centre rect size)]
    (when (and fname (> letter (* size 0.006)))
      {:id (str id "#name") :shape "text" :text fname :size letter :color "#1b1d22" :name-of id
       :pos [cx (+ (* (inc level) step) 0.002) cz] :rot [-90 0 0] :parent cid})))

(defn- items-of
  "A folder's children as tiles to lay out: its folders (cut ones as one tile) and its files."
  [{:keys [cid cut prefix]} n level]
  (concat (for [dn (:dirs n)]
            (if (> (inc level) cut)
              {:tile? true :w (:lines dn) :id (folder-id cid (:path dn)) :lines (:lines dn) :dir dn
               :note (str (:path dn) "/ · " (:count dn) " files · " (:lines dn) " lines")}
              {:w (:lines dn) :dir dn}))
          (for [[nm lines path] (:files n)]
            {:tile? true :w lines :id (file-id cid path) :lines lines :path (str prefix path) :fname nm
             :note (str nm " · " lines " lines")})))

(defn folders-at
  "The paths of the folders at depth d (1 = the root's own folders), in the tree's order."
  [n d]
  (if (= d 1) (mapv :path (:dirs n)) (vec (mapcat #(folders-at % (dec d)) (:dirs n)))))

(defn hues
  "A hue per folder at depth d, spread round the colour wheel: {path hue}."
  [root d]
  (let [ps (folders-at root d)]
    (zipmap ps (map #(hue-of % (count ps)) (range)))))

(defn- hue-for [ctx it hue]
  (or (some->> (:dir it) :path (get (:hues ctx))) hue))

(defn- walk
  "The parts of folder n at level in rect: its area, its name, its tiles and its folders' parts."
  [{:keys [size] :as ctx} n level rect hue]
  (let [pad (+ (* 0.025 (min (:w rect) (:d rect))) (* size 0.001))
        hdr (if (<= level 3) (min (* (:d rect) 0.14) (* size 0.035)) 0)
        items (vec (items-of ctx n level))
        rects (squarify (mapv #(max 1 (:w %)) items) (inner rect pad hdr))]
    (into (filterv some? [(platform ctx n level rect hue) (name-text ctx n level rect pad hdr)])
          (mapcat (fn [it r]
                    (let [h (hue-for ctx it hue)]
                      (if (:tile? it)
                        (filterv some? [(tile ctx it (inc level) r h) (file-name ctx it (inc level) r)])
                        (walk ctx (:dir it) (inc level) r h))))
                  items rects))))

(def max-names "File names painted at most (each is a texture), the biggest first." 160)

(defn- fewer-names
  "The parts with at most max-names file names, the biggest kept."
  [ps]
  (let [names (filter :name-of ps)
        kept (set (map :id (take max-names (sort-by #(- (:size %)) names))))]
    (filterv #(or (not (:name-of %)) (kept (:id %))) ps)))

(defn parts
  "The parts of folders part p (its id, pos, rot, size: the map's width, max: at most this many
  tiles, hue: the folder depth that gets its own colour, default 1) for the files [[path lines]] of
  the scene's root."
  [p files]
  (let [cid (str (:id p))
        folder (str/replace (str (or (:path p) "")) #"^\./|/+$" "")
        root (tree (under files folder))
        size (let [s (js/Number (:size p))] (if (pos? s) s 20))
        cap (let [c (js/Number (:max p))] (if (pos? c) (min c 4000) 1500))
        ctx {:cid cid :size size :step (* size 0.004) :cut (cut-depth root cap)
             :prefix (if (seq folder) (str folder "/") "")
             :hues (hues root (let [h (js/Number (:hue p))] (if (pos? h) (min 6 (js/Math.round h)) 1)))}]
    (into [{:id cid :shape "group" :pos (:pos p) :rot (:rot p)}]
          (fewer-names (walk ctx root 0 {:x 0 :z 0 :w size :d size} nil)))))
