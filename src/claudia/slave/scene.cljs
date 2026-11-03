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

(defn- upsert-edges
  "Arrows sent replace fields of the arrow with the same ends, or are added."
  [es sent]
  (reduce (fn [es e]
            (if-let [i (first (keep-indexed #(when (= (edge-id %2) (edge-id e)) %1) es))]
              (update es i merge e)
              (conj es e)))
          (vec es)
          sent))

(defn- draw-graph
  "One graph block after a draw call, and changed (a set of ids) with the ids that changed: arrows
  sent, then the graph's nodes and groups, less the ones removed. {:graph :changed}."
  [g {:keys [nodes edges groups] removed :remove} changed]
  (let [gone (set removed)
        labelled #(merge {:label (:id %)} %)
        kept-nodes (->> (upsert (:nodes g) nodes labelled) (filterv #(not (gone (:id %)))))
        kept-groups (->> (upsert (:groups g) groups labelled) (filterv #(not (gone (:id %)))))
        all-edges (upsert-edges (:edges g) edges)
        ids (set (map :id kept-nodes))
        group-ids (set (map :id kept-groups))]
    {:changed (as-> changed c
                (reduce conj c (map edge-id edges))
                (into c (map :id) (concat kept-nodes kept-groups))
                (reduce disj c gone))
     :graph (assoc g
                   :nodes (mapv #(if (and (:group %) (not (group-ids (:group %)))) (dissoc % :group) %) kept-nodes)
                   :edges (filterv #(and (not (gone (edge-id %))) (ids (:from %)) (ids (:to %))) all-edges)
                   :groups kept-groups)}))

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

(defn- item-ids
  "\"block:item\" for every item with an id in a form's data lists."
  [id data]
  (for [[_ v] data :when (sequential? v), it v :when (and (map? it) (contains? it :id))]
    (str id ":" (:id it))))

(defn- form-block
  "A form block after a draw: [block changed]."
  [old common {:keys [id data removeItems]} form-name changed]
  (let [f (if (and (= "form" (:kind old)) (= form-name (:form old)))
            old
            {:id id :kind "form" :form form-name :data {}})]
    [(merge f common {:data (merge-form (:data f) data removeItems)})
     (into (conj changed id) (item-ids id data))]))

(defn- html-block
  "A freeform (html) block after a draw: [block changed]."
  [old common {:keys [id html parts template height]} changed]
  (let [h (if (= "html" (:kind old)) old {:id id :kind "html" :html "" :parts {}})
        fresh (and (some? html) (not= html (:html h)))
        changed (cond-> changed (or fresh (some? height)) (conj id))]
    [(merge h common
            (when template {:template template})
            (when (some? height) {:height height})
            (when (some? html) {:html html})
            ;; New HTML starts from its own markup; parts sent with it still apply.
            {:parts (merge (if fresh {} (:parts h)) parts)})
     (into changed (map #(str id ":" (subs (str %) 1))) (keys parts))]))

(defn block-kind
  "What kind a block drawn as given is: a form, what it was, what it says, html, or else a graph."
  [old form-name {:keys [kind html template]}]
  (cond form-name "form"
        old (:kind old)
        kind kind
        (or (some? html) template) "html"
        :else "graph"))

(defn- draw-block
  "One block of a draw call into {:blocks :changed}."
  [{:keys [blocks changed]} {:keys [id form width before] block-name :name :as given}]
  (let [old (some #(when (= (:id %) id) %) blocks)
        ;; A form block keeps its form when later calls only send data.
        form-name (or form (when (= "form" (:kind old)) (:form old)))
        common (cond-> {} width (assoc :width width) block-name (assoc :name block-name))
        [drawn changed] (case (block-kind old form-name given)
                          "form" (form-block old common given form-name changed)
                          "graph" [(merge (if (= "graph" (:kind old)) old {:id id :kind "graph" :nodes [] :edges [] :groups []})
                                          common)
                                   changed]
                          (html-block old common given changed))]
    {:changed (cond-> changed (or (not old) width block-name) (conj id))
     :blocks (if (scene3d? drawn)
               (place-scene3d blocks drawn (some? old) before)
               (place blocks drawn before))}))

(defn- draw-into-graph
  "Nodes, arrows and groups of a draw call into its graph block (graph, default \"graph\")."
  [{:keys [blocks changed]} {:keys [graph] :as input}]
  (let [gid (or graph "graph")
        old (some #(when (= (:id %) gid) %) blocks)
        g (if (= "graph" (:kind old)) old {:id gid :kind "graph" :nodes [] :edges [] :groups []})
        drawn (draw-graph g input changed)]
    {:blocks (place blocks (:graph drawn) nil) :changed (:changed drawn)}))

(defn apply-draw
  "The scene after one draw call and the ids that changed (nodes, arrows, blocks, \"block:part\",
  \"block:item\"), or {:error text} when the call asks for a form this runner lacks. forms: the
  forms on offer (default the base ones)."
  ([scene input] (apply-draw scene input p/forms))
  ([scene {:keys [title clear nodes edges groups blocks removeBlocks] removed :remove :as input} forms]
   (if-let [bad (some #(when (and (:form %) (not (some #{(:form %)} forms))) (:form %)) blocks)]
     {:error (str "No form \"" bad "\" here. Forms: " (apply str (interpose ", " forms)) ".")}
     (let [base (if clear empty-scene scene)
           drawn (reduce draw-block {:blocks (vec (:blocks base)) :changed #{}} blocks)
           {bs :blocks changed :changed} (if (or (seq nodes) (seq edges) (seq groups) (seq removed))
                                           (draw-into-graph drawn input)
                                           drawn)
           gone (set removeBlocks)]
       {:scene {:title (or title (:title base) "")
                :blocks (filterv #(and (not (gone (:id %)))
                                       (or (not= "graph" (:kind %)) (seq (:nodes %))))
                                 bs)}
        :changed (vec changed)}))))

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
