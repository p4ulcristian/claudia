(ns claudia.web.views.pure.picture
  "The picture as plain data: what changed since the last one shown, and the files blocks the person
  keeps on the live picture after Claude took them off.")

(defn- by-id [xs] (into {} (map (juxt :id identity)) xs))
(defn- eid [{:keys [from to]}] (str from "->" to))

(defn changed-ids
  "Ids of the blocks, nodes and arrows that are new or different in scene against prev."
  [prev scene]
  (let [old (by-id (:blocks prev))]
    (into #{}
          (mapcat (fn [b]
                    (let [p (old (:id b))]
                      (concat
                       (when (not= p b) [(:id b)])
                       (when (and p (= "graph" (:kind b)) (not= p b))
                         (let [on (by-id (:nodes p))
                               oe (into {} (map (juxt eid identity)) (:edges p))]
                           (concat (for [n (:nodes b) :when (not= n (on (:id n)))] (:id n))
                                   (for [e (:edges b) :when (not= e (oe (eid e)))] (eid e)))))))))
          (:blocks scene))))

(defn next-shown
  "The last picture shown {:k :scene :changed :seq} after scene of chat k: anew for another chat,
  the same for the same scene, else what changed and the next draw number."
  [p k scene]
  (cond
    (or (nil? p) (not= (:k p) k)) {:k k :scene scene :changed #{} :seq 0}
    (identical? (:scene p) scene) p
    :else {:k k :scene scene :changed (changed-ids (:scene p) scene) :seq (inc (:seq p))}))

(defn copies-of
  "The copies of the kept blocks {:k chat, id {:block :at}}: started over for another chat, then
  each kept block on the picture copied with its place."
  [copies k blocks kept-ids]
  (reduce (fn [cs [i b]] (if (some #{(:id b)} kept-ids) (assoc cs (:id b) {:block b :at i}) cs))
          (if (not= (:k copies) k) {:k k} copies)
          (map-indexed vector blocks)))

(defn gone-ids
  "The kept blocks Claude took off the live picture (none on an earlier one)."
  [blocks kept-ids copies live?]
  (if live?
    (let [present (set (map :id blocks))]
      (set (remove present (filter #(get copies %) kept-ids))))
    #{}))

(defn with-gone
  "The blocks with each gone kept block back at its place."
  [blocks kept-ids gone copies]
  (reduce (fn [bs id]
            (let [{:keys [block at]} (get copies id)
                  [a b] (split-at (min at (count bs)) bs)]
              (vec (concat a [block] b))))
          (vec blocks) (filter gone kept-ids)))

(defn kept-with
  "The kept ids {:k :ids} after the person touched block id of chat k."
  [kp k id]
  (if (and (= (:k kp) k) (some #{id} (:ids kp)))
    kp
    {:k k :ids (conj (if (= (:k kp) k) (:ids kp) []) id)}))
