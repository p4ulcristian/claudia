(ns claudia.shared.git-layout
  "The git form's commit graph layout (from GitGud's lib/graph-layout.js): which lane each commit sits
  in and which commits get a branch colour. Pure data, no git: the runner lays out what git log gave
  (claudia.slave.git), the Forms gallery lays out its sample.")

(defn- free
  "The first lane waiting for nothing (a new one past the end when all wait)."
  [lanes]
  (or (first (keep-indexed (fn [i x] (when (nil? x) i)) lanes)) (count lanes)))

(defn- put [lanes i v] (assoc (if (>= i (count lanes)) (conj lanes nil) lanes) i v))

(defn assign-lanes
  "Each commit's lane (:col). Commits newest first, children before parents (git's --topo-order).
  Only parents in view get a lane, so a merge whose second parent is off screen doesn't open a lane
  that never closes."
  [commits]
  (let [present (set (map :hash commits))]
    (loop [cs commits lanes [] out []]
      (if-let [c (first cs)]
        (let [col (or (first (keep-indexed #(when (= %2 (:hash c)) %1) lanes)) (free lanes))
              ;; a merge can have several lanes waiting on this commit: collapse them
              lanes (put (mapv #(if (= % (:hash c)) nil %) lanes) col nil)
              parents (filter present (:parents c))
              lanes (if (empty? parents)
                      (assoc lanes col nil)
                      ;; the first parent continues this lane
                      (reduce (fn [ls p] (if (some #{p} ls) ls (put ls (free ls) p)))
                              (assoc lanes col (first parents))
                              (rest parents)))]
          (recur (rest cs) lanes (conj out (assoc c :col col))))
        out))))

(defn colorize-trunks
  "A commit is coloured (:colorKey) when it is on a branch's trunk: reachable from a branch tip by
  first parents only. Merged-in history stays nil (grey), so a coloured line always leads to a real
  branch tip. Local tips first, then remote ones; tips that share history share the newest's colour."
  [commits]
  (let [idx (into {} (map-indexed (fn [i c] [(:hash c) i])) commits)
        tip? (fn [types c] (some #(types (:type %)) (:refs c)))
        local (filter #(tip? #{"branch" "head"} %) commits)
        remote (filter #(and (not (tip? #{"branch" "head"} %)) (tip? #{"remote"} %)) commits)]
    (first
     (reduce (fn [[cs n] tip]
               (loop [cs cs i (idx (:hash tip))]
                 (if (and i (nil? (:colorKey (nth cs i))))
                   (recur (assoc-in cs [i :colorKey] n) (some-> (first (:parents (nth cs i))) idx))
                   [cs (inc n)])))
             [(mapv #(assoc % :colorKey nil) commits) 0]
             (concat local remote)))))

(defn layout-graph [commits] (colorize-trunks (assign-lanes (vec commits))))
