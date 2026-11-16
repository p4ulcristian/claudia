(ns claudia.slave.upgrade
  "Saved pictures rewritten once into the shape where every block is a form (README decision 39):
  blocks with a kind (graph, html, form) and the pictures from before blocks (a graph and cards).
  The store runs it when it opens a database whose user_version is below 1."
  (:require [clojure.string :as str]
            [claudia.slave.scene :as scene]))

(defn- longest-run
  "The longest run of backticks in s."
  [s]
  (reduce max 0 (map count (re-seq #"`+" (str s)))))

(defn- fenced
  "Code as a Markdown code block, its fence longer than any backtick run inside."
  [code lang]
  (let [fence (apply str (repeat (max 3 (inc (longest-run code))) "`"))]
    (str fence (or lang "") "\n" code "\n" fence)))

(defn- cell [c] (str/replace (str/replace (str c) "|" "\\|") #"\n+" " "))

(defn- table-md
  "A card's table as a Markdown table (a header is required: blank when it had none)."
  [columns rows]
  (let [n (reduce max (count columns) (map count rows))
        row (fn [cs] (str "| " (str/join " | " (map cell (take n (concat cs (repeat ""))))) " |"))]
    (str/join "\n" (concat [(row columns) (row (repeat n "---"))] (map row rows)))))

(defn- card-text
  "What a card showed, as Markdown: by its kind (text when none), as the old renderer drew it."
  [{:keys [kind text items columns rows code lang]}]
  (case (or kind "text")
    "list" (str/join "\n" (map #(str "- " %) items))
    "table" (table-md columns rows)
    "code" (fenced (or code text) lang)
    (str text)))

(defn card-block
  "A card of a picture from before blocks as a block: a page as html, the rest as notes."
  [{:keys [id kind title html] :as card}]
  (let [block-id (str "card-" id)]
    (if (= kind "page")
      (cond-> {:id block-id :form "html" :data {:html (str html) :parts {}}} title (assoc :name title))
      {:id block-id :form "notes" :data (cond-> {:text (card-text card)} title (assoc :title title))})))

(defn graph-data
  "A graph's nodes, arrows and groups as the graph form's data (each arrow named by its ends)."
  [{:keys [nodes edges groups]}]
  {:nodes (vec nodes)
   :edges (mapv #(assoc % :id (scene/edge-id %)) edges)
   :groups (vec groups)})

(defn kind-block
  "A block with a kind as a form block; a block without a kind is already one."
  [{:keys [kind] :as b}]
  (let [common (select-keys b [:id :name :width])]
    (case kind
      "graph" (assoc common :form "graph" :data (graph-data b))
      "html" (assoc common :form "html"
                    :data (cond-> {:html (str (:html b)) :parts (or (:parts b) {})}
                            (seq (:template b)) (assoc :template (:template b))))
      "form" (dissoc b :kind)
      b)))

(defn- old-blocks
  "The blocks of a picture from before blocks: its graph first, then its cards."
  [{:keys [nodes cards] :as s}]
  (vec (concat (when (seq nodes) [{:id "graph" :form "graph" :data (graph-data s)}])
               (map card-block cards))))

(defn scene
  "A saved picture in the shape where every block is a form."
  [s]
  (cond
    (not (map? s)) s
    (contains? s :blocks) (update s :blocks #(mapv kind-block %))
    :else {:title (or (:title s) "") :blocks (old-blocks s)}))
