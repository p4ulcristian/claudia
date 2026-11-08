(ns claudia.web.scene3d.structures
  "Which real anatomy structures a part {shape: \"anatomy\", ref, side?, system?} names, and the
  parts it becomes (claudia.web.scene3d.anatomy loads them). Pure.

  A ref (or a list of them) names structures by id (femur.r), English name (Femur, Adductor longus
  muscle), Latin name (Os femoris) or tag: a region (Right lower limb, Pelvis), a compartment
  (Medial compartment of thigh), a nerve's territory (Obturator nerve). side: right, left or both
  (the default); system: skeletal, muscular, nervous, joints or attachment (muscles' origin and
  insertion patches, only when asked for). One structure keeps the part's id; several get
  \"<id>:<structure id>\".

  A nerve or vessel (index shape \"lines\") is drawn as a tube along its centre lines, radius? metres
  thick (default 3 mm for nerves, 3.5 for vessels).

  An index entry: {:id :name :latin :side (r, l or nil) :system :tags :kind (origin|insertion)
  :shape (lines, or none for a mesh) :file :centre :size}."
  (:require [clojure.string :as str]))

(defn- norm [s] (-> (str s) str/lower-case str/trim (str/replace #"\s+" " ")))

(defn- names-of
  "Every name a structure answers to: its id, English name (also without \"muscle\"), Latin."
  [st]
  (set (map norm (remove nil? [(:id st) (:name st) (:latin st) (str/replace (str (:name st)) #"(?i) muscle$" "")]))))

(defn- sides [side]
  (case (norm side)
    ("right" "r") #{"r" nil}
    ("left" "l") #{"l" nil}
    #{"r" "l" nil}))

(defn- named? [ref st]
  (let [r (norm ref)]
    (or (contains? (names-of st) r) (some #(= r (norm %)) (:tags st)))))

(defn structures-of
  "The index's structures a part names (in index order): matched by any of its refs, on its side,
  in its systems (attachment patches only when asked for)."
  [index {:keys [ref side system]}]
  (let [refs (if (sequential? ref) ref [ref])
        systems (set (if (sequential? system) system (when system [system])))]
    (filterv (fn [st]
               (and (some #(named? % st) refs)
                    (contains? (sides side) (:side st))
                    (if (seq systems) (contains? systems (:system st)) (not= "attachment" (:system st)))))
             index)))

(def ^:private colours
  {"skeletal" "#e8dcc4" "muscular" "#b8524a" "nervous" "#e6c84a" "joints" "#c9c0ad"
   "artery" "#d23b3b" "vein" "#3d6fd6" "origin" "#e0453c" "insertion" "#3d7fe0"})

(def ^:private radii "How thick a nerve or vessel is drawn by default (metres)."
  {"nervous" 0.003 "vascular" 0.0035})

(defn- colour-key
  "Which colour a structure takes: its attachment kind, artery or vein, else its system."
  [st]
  (or (:kind st)
      (when (= "vascular" (:system st)) (if (re-find #"(?i)\bvein|vena\b" (str (:name st))) "vein" "artery"))
      (:system st)))

(defn- side-word [s] (case s "r" "right" "l" "left" nil))

(defn- label-of
  "A structure's label: the part's own text for a single structure, else the Latin name (the
  English one when there is none); none when the part's label is false."
  [{:keys [label]} st single?]
  (cond (false? label) nil
        (and (string? label) single?) label
        :else (or (:latin st) (:name st))))

(defn- note-of [st]
  (str/join ", " (remove nil? [(when (:latin st) (:name st)) (:kind st) (side-word (:side st))])))

(defn expand
  "The parts a part becomes: one per structure, with its file, colour, label and note."
  [part structures]
  (let [single? (= 1 (count structures))]
    (mapv (fn [st]
            (merge (dissoc part :ref :side :system)
                   {:id (if single? (:id part) (str (:id part) ":" (:id st)))
                    :shape "anatomy" :file (:file st) :structure (:id st)
                    :color (or (:color part) (colours (colour-key st)) "#cccccc")
                    :label (label-of part st single?)
                    :note (or (:note part) (note-of st))}
                   (when (= "lines" (:shape st))
                     {:lines true :radius (or (:radius part) (radii (:system st)) 0.003)})))
          structures)))

