(ns claudia.slave.scene
  "The picture: a list of named blocks, each one form with its data (graph, html and the predefined
  forms). A draw call upserts by id, so Claude sends only what changed (README decision 39)."
  (:require [clojure.string :as str]
            [claudia.shared.protocol :as p]))

(def empty-scene {:title "" :blocks []})

(defn- index-of [items id] (first (keep-indexed (fn [i x] (when (= (:id x) id) i)) items)))

(defn- upsert
  "Patches given replace fields of the item with their id, fields left out stay; new ids are added."
  [items patches]
  (reduce (fn [items patch]
            (if-let [i (index-of items (:id patch))]
              (update items i merge patch)
              (conj items patch)))
          (vec items)
          patches))

(defn- item-list? [x] (and (sequential? x) (every? #(and (map? %) (contains? % :id)) x)))

(defn- without-items
  "Every list of the data without the items whose id is in gone."
  [data gone]
  (reduce-kv (fn [out k v]
               (assoc out k (if (sequential? v) (filterv #(not (and (map? %) (gone (:id %)))) v) v)))
             data data))

(defn merge-form
  "A form's data, field by field: lists of items with an id are upserted by id, anything else replaced;
  remove-items drops items by id from every list."
  [prev data remove-items]
  (let [out (reduce-kv (fn [out k v]
                         (let [old (get out k)]
                           (assoc out k (if (and (item-list? v) (item-list? old)) (upsert old v) v))))
                       (or prev {})
                       (or data {}))
        gone (set remove-items)]
    (if (empty? gone) out (without-items out gone))))

;; ---- graph ----

(defn edge-id [{:keys [from to]}] (str from "->" to))

(defn- with-edge-ids
  "Every arrow sent named by its ends (\"from->to\"): an arrow is identified by from + to."
  [data]
  (cond-> data
    (sequential? (:edges data)) (update :edges (fn [es] (mapv #(assoc % :id (edge-id %)) es)))))

(defn- labelled [items] (mapv #(merge {:label (:id %)} %) items))

(defn tidy-graph
  "A graph's data after a merge: nodes and groups without a label labelled by their id, a node's
  group dropped when that group is gone, arrows whose ends are gone dropped."
  [{:keys [nodes edges groups] :as data}]
  (let [groups (labelled (or groups []))
        group-ids (set (map :id groups))
        nodes (mapv #(if (and (:group %) (not (group-ids (:group %)))) (dissoc % :group) %)
                    (labelled (or nodes [])))
        ids (set (map :id nodes))]
    (assoc data
           :nodes nodes
           :groups groups
           :edges (filterv #(and (ids (:from %)) (ids (:to %))) (or edges [])))))

;; ---- html ----

(defn merge-html
  "An html page's data: new html starts from its own markup (parts sent with it still apply);
  parts sent are merged into the ones it has; anything else replaced."
  [prev {:keys [html parts] :as data}]
  (let [prev (merge {:html "" :parts {}} prev)
        fresh (and (some? html) (not= html (:html prev)))]
    (merge prev (dissoc data :parts) {:parts (merge (if fresh {} (:parts prev)) parts)})))

(defn merge-data
  "A block's data after a draw: the graph and html forms have their own rules, the rest upsert."
  [form prev data remove-items]
  (case form
    "graph" (tidy-graph (merge-form prev (with-edge-ids data) remove-items))
    "html" (merge-html prev data)
    (merge-form prev data remove-items)))

;; ---- blocks ----

(defn- place
  "Puts block b before the block `before`, or where it was, or last."
  [blocks b before]
  (let [rest-blocks (filterv #(not= (:id %) (:id b)) blocks)
        at (when before (index-of rest-blocks before))
        was (index-of blocks (:id b))]
    (cond at (vec (concat (subvec rest-blocks 0 at) [b] (subvec rest-blocks at)))
          was (assoc blocks was b)
          :else (conj rest-blocks b))))

(defn- scene3d? [b] (= "scene3d" (:form b)))

(defn- place-scene3d
  "A picture holds one 3D scene: a new one takes the old one's place (unless it says where to go),
  and every other scene3d block leaves."
  [blocks b old? before]
  (let [at (when (and (not old?) (not before)) (first (keep-indexed #(when (scene3d? %2) %1) blocks)))]
    (if at
      (filterv #(or (= (:id %) (:id b)) (not (scene3d? %))) (assoc blocks at b))
      (place (filterv #(or (= (:id %) (:id b)) (not (scene3d? %))) blocks) b before))))

(defn- item-ids
  "\"block:item\" for every item with an id in a form's data lists, and every html part sent
  (an html block's parts are a map; a scene3d block's parts are a list, counted with the lists)."
  [id data]
  (concat (for [[_ v] data :when (sequential? v), it v :when (and (map? it) (contains? it :id))]
            (str id ":" (:id it)))
          (when (map? (:parts data))
            (for [k (keys (:parts data))] (str id ":" (name k))))))

(defn- drawn-block
  "The block after a draw: a form other than the one it was starts from empty data."
  [old {:keys [id form data removeItems width] block-name :name}]
  (let [form (or form (:form old))
        start (if (= form (:form old)) old {:id id :form form :data {}})]
    (cond-> (assoc start :data (merge-data form (:data start) data removeItems))
      width (assoc :width width)
      block-name (assoc :name block-name))))

(defn- draw-block
  "One block of a draw call into {:blocks :changed}."
  [{:keys [blocks changed]} {:keys [id before] :as given}]
  (let [old (some #(when (= (:id %) id) %) blocks)
        b (drawn-block old given)
        sent (cond-> (:data given) (= "graph" (:form b)) with-edge-ids)]
    {:changed (into (conj changed id) (item-ids id sent))
     :blocks (if (scene3d? b)
               (place-scene3d blocks b (some? old) before)
               (place blocks b before))}))

;; ---- a draw call ----

(def ^:private old-call-fields
  "Fields of the draw call before every block was a form (2026-11-16)."
  [:nodes :edges :groups :graph :remove])

(def ^:private old-block-fields [:kind :html :parts :template :height])

(defn- old-shape-error
  "Why a call in the shape before every block was a form is refused, or nil."
  [{:keys [blocks] :as input}]
  (when (or (some #(contains? input %) old-call-fields)
            (some (fn [b] (some #(contains? b %) old-block-fields)) blocks))
    (str "The draw tool changed: every block is a form now. A graph is blocks: [{ id, form: \"graph\", "
         "data: { nodes, edges, groups } }] (removeItems drops nodes, groups or arrows \"from->to\"); an "
         "html page is blocks: [{ id, form: \"html\", data: { html, parts, template } }]. "
         "Nothing was drawn; draw again in this shape.")))

(defn- call-error
  "Why a draw call is refused: an old shape, a new block without a form, a form not offered."
  [scene {:keys [blocks] :as input} forms]
  (let [known (set (map :id (:blocks scene)))
        bad (some #(when (and (:form %) (not (some #{(:form %)} forms))) (:form %)) blocks)
        formless (some #(when (and (not (:form %)) (not (known (:id %)))) (:id %)) blocks)]
    (or (old-shape-error input)
        (when bad (str "No form \"" bad "\" here. Forms: " (str/join ", " forms) "."))
        (when formless (str "Block \"" formless "\" is new: give it a form.")))))

(defn- shown?
  "Whether a block stays on the picture: a graph only while it has nodes."
  [b]
  (or (not= "graph" (:form b)) (seq (get-in b [:data :nodes]))))

(defn apply-draw
  "The scene after one draw call and the ids that changed (blocks, \"block:item\", \"block:part\"),
  or {:error text} when the call is refused. forms: the forms on offer (default the base ones)."
  ([scene input] (apply-draw scene input p/forms))
  ([scene {:keys [title clear blocks removeBlocks] :as input} forms]
   (let [base (if clear empty-scene scene)]
     (if-let [error (call-error base input forms)]
       {:error error}
       (let [{bs :blocks changed :changed} (reduce draw-block {:blocks (vec (:blocks base)) :changed #{}} blocks)
             gone (set removeBlocks)]
         {:scene {:title (or title (:title base) "")
                  :blocks (filterv #(and (not (gone (:id %))) (shown? %)) bs)}
          :changed (vec changed)})))))

(defn- describe-block [{:keys [id form data]}]
  (case form
    "graph" (str id " (graph, " (count (:nodes data)) " nodes, " (count (:edges data)) " arrows)")
    "html" (str id " (html"
                (when (seq (:template data)) (str " from template \"" (:template data) "\""))
                (when (seq (:parts data)) (str ", parts " (str/join ", " (map name (keys (:parts data))))))
                ")")
    (str id " (" form ")")))

(defn describe
  "What the draw tool tells Claude back: \"2 blocks: flow (graph, 5 nodes, 4 arrows), steps (pipeline)\"."
  [{:keys [blocks]}]
  (if (empty? blocks)
    "The picture is empty."
    (str (count blocks) (if (= 1 (count blocks)) " block: " " blocks: ")
         (str/join ", " (map describe-block blocks)))))
