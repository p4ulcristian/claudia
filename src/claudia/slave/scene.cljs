(ns claudia.slave.scene
  "The picture: a list of named blocks (graph, html, form). A draw call upserts by id, so Claude sends
  only what changed (the TypeScript Claudia's src/scene.ts applyDraw and describe)."
  (:require [claudia.shared.protocol :as p]))

(def empty-scene {:title "" :blocks []})

(defn- index-of [items id] (first (keep-indexed (fn [i x] (when (= (:id x) id) i)) items)))

(defn- upsert
  "Patches given replace fields, fields left out stay; new ids are made by fresh."
  [items patches fresh]
  (reduce (fn [items patch]
            (if-let [i (index-of items (:id patch))]
              (update items i merge patch)
              (conj items (fresh patch))))
          (vec items)
          patches))

(defn edge-id [{:keys [from to]}] (str from "->" to))

(defn- draw-graph
  "One graph block after a draw call; changed (a volatile set) gets the ids that changed: nodes,
  groups and arrows sent, less the ones removed."
  [g {:keys [nodes edges groups remove]} changed]
  (let [gone (set remove)
        labelled #(merge {:label (:id %)} %)
        nodes (->> (upsert (:nodes g) nodes labelled) (filterv #(not (gone (:id %)))))
        groups (->> (upsert (:groups g) groups labelled) (filterv #(not (gone (:id %)))))
        edges (reduce (fn [es e]
                        (vswap! changed conj (edge-id e))
                        (if-let [i (first (keep-indexed #(when (= (edge-id %2) (edge-id e)) %1) es))]
                          (update es i merge e)
                          (conj es e)))
                      (vec (:edges g))
                      edges)
        ids (set (map :id nodes))
        group-ids (set (map :id groups))]
    (vswap! changed into (map :id) (concat nodes groups))
    (vswap! changed #(reduce disj % gone))
    (assoc g
           :nodes (mapv #(if (and (:group %) (not (group-ids (:group %)))) (dissoc % :group) %) nodes)
           :edges (filterv #(and (not (gone (edge-id %))) (ids (:from %)) (ids (:to %))) edges)
           :groups groups)))

(defn- item-list? [x] (and (sequential? x) (every? #(and (map? %) (contains? % :id)) x)))

(defn merge-form
  "A form's data, field by field: lists of items with an id are upserted by id, anything else replaced;
  remove-items drops items by id from every list."
  [prev data remove-items]
  (let [out (reduce-kv (fn [out k v]
                         (let [old (get out k)]
                           (assoc out k (if (and (item-list? v) (item-list? old))
                                          (upsert old v identity)
                                          v))))
                       (or prev {})
                       (or data {}))
        gone (set remove-items)]
    (if (empty? gone)
      out
      (reduce-kv (fn [out k v]
                   (assoc out k (if (sequential? v) (filterv #(not (and (map? %) (gone (:id %)))) v) v)))
                 out out))))

(defn- place
  "Puts block b before the block `before`, or where it was, or last."
  [blocks b before]
  (let [rest-blocks (filterv #(not= (:id %) (:id b)) blocks)
        at (when before (index-of rest-blocks before))
        was (index-of blocks (:id b))]
    (cond at (vec (concat (subvec rest-blocks 0 at) [b] (subvec rest-blocks at)))
          was (assoc blocks was b)
          :else (conj rest-blocks b))))

(defn- scene3d? [b] (and (= "form" (:kind b)) (= "scene3d" (:form b))))

(defn- place-scene3d
  "A picture holds one 3D scene: a new one takes the old one's place (unless it says where to go),
  and every other scene3d block leaves."
  [blocks b old? before]
  (let [at (when (and (not old?) (not before)) (first (keep-indexed #(when (scene3d? %2) %1) blocks)))]
    (if at
      (filterv #(or (= (:id %) (:id b)) (not (scene3d? %))) (assoc blocks at b))
      (place (filterv #(or (= (:id %) (:id b)) (not (scene3d? %))) blocks) b before))))

(defn- draw-block [changed blocks {:keys [id form kind width name html parts data removeItems before template height]}]
  (let [old (some #(when (= (:id %) id) %) blocks)
        ;; A form block keeps its form when later calls only send data.
        form-name (or form (when (= "form" (:kind old)) (:form old)))
        kind (cond form-name "form"
                   old (:kind old)
                   kind kind
                   (or (some? html) template) "html"
                   :else "graph")
        common (cond-> {} width (assoc :width width) name (assoc :name name))
        next (case kind
               "form" (let [f (if (and (= "form" (:kind old)) (= form-name (:form old)))
                                old
                                {:id id :kind "form" :form form-name :data {}})]
                        (vswap! changed conj id)
                        (doseq [[_ v] data :when (sequential? v), it v :when (and (map? it) (contains? it :id))]
                          (vswap! changed conj (str id ":" (:id it))))
                        (merge f common {:data (merge-form (:data f) data removeItems)}))
               "graph" (merge (if (= "graph" (:kind old)) old {:id id :kind "graph" :nodes [] :edges [] :groups []})
                              common)
               (let [h (if (= "html" (:kind old)) old {:id id :kind "html" :html "" :parts {}})
                     fresh (and (some? html) (not= html (:html h)))]
                 (when (or fresh (some? height)) (vswap! changed conj id))
                 (doseq [k (keys parts)] (vswap! changed conj (str id ":" (subs (str k) 1))))
                 (merge h common
                        (when template {:template template})
                        (when (some? height) {:height height})
                        (when (some? html) {:html html})
                        ;; New HTML starts from its own markup; parts sent with it still apply.
                        {:parts (merge (if fresh {} (:parts h)) parts)})))]
    (when (or (not old) width name) (vswap! changed conj id))
    (if (scene3d? next)
      (place-scene3d blocks next (some? old) before)
      (place blocks next before))))

(defn apply-draw
  "The scene after one draw call and the ids that changed (nodes, arrows, blocks, \"block:part\",
  \"block:item\"), or {:error text} when the call asks for a form this runner lacks. forms: the
  forms on offer (default the base ones)."
  ([scene input] (apply-draw scene input p/forms))
  ([scene {:keys [title clear graph nodes edges groups remove blocks removeBlocks] :as input} forms]
   (if-let [bad (some #(when (and (:form %) (not (some #{(:form %)} forms))) (:form %)) blocks)]
     {:error (str "No form \"" bad "\" here. Forms: " (apply str (interpose ", " forms)) ".")}
     (let [changed (volatile! #{})
           base (if clear empty-scene scene)
           bs (reduce (partial draw-block changed) (vec (:blocks base)) blocks)
           bs (if (or (seq nodes) (seq edges) (seq groups) (seq remove))
                (let [gid (or graph "graph")
                      old (some #(when (= (:id %) gid) %) bs)
                      g (if (= "graph" (:kind old)) old {:id gid :kind "graph" :nodes [] :edges [] :groups []})]
                  (place bs (draw-graph g input changed) nil))
                bs)
           gone (set removeBlocks)]
       {:scene {:title (or title (:title base) "")
                :blocks (filterv #(and (not (gone (:id %)))
                                       (or (not= "graph" (:kind %)) (seq (:nodes %))))
                                 bs)}
        :changed (vec @changed)}))))

(defn describe
  "What the draw tool tells Claude back: \"2 blocks: flow (graph, 5 nodes, 4 arrows), steps (pipeline)\"."
  [{:keys [blocks]}]
  (if (empty? blocks)
    "The picture is empty."
    (str (count blocks) (if (= 1 (count blocks)) " block: " " blocks: ")
         (apply str (interpose ", " (for [b blocks]
                                      (case (:kind b)
                                        "graph" (str (:id b) " (graph, " (count (:nodes b)) " nodes, " (count (:edges b)) " arrows)")
                                        "form" (str (:id b) " (" (:form b) ")")
                                        (str (:id b) " (freeform"
                                             (when (seq (:template b)) (str " from template \"" (:template b) "\""))
                                             (when (seq (:parts b)) (str ", parts " (apply str (interpose ", " (map #(subs (str %) 1) (keys (:parts b)))))))
                                             ")"))))))))
