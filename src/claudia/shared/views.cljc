(ns claudia.shared.views
  "The views the right half shows (README decision 91), as data: the Drawing board (Claude's picture)
  is the table, always under everything; every other view is a sheet of paper laid on it, and the
  sheets pile up. The pile is a vector of view ids, bottom to top, the same on the page and the hub,
  which keeps each person's pile (GET/PUT /hub/right-view)."
  (:require [clojure.string :as str]))

(def all
  "Every view: [id label]. :picture is the board; the rest open as sheets."
  [[:picture "Drawing board"]
   [:files "File Browser"]
   [:search "Search"]
   [:settings "Settings"]
   [:system "System"]
   [:prompts "Prompts"]
   [:forms "Forms"]])

(def ^:private ids (set (map first all)))

(def ^:private aliases
  "Old names still read as the view they became: Machines became System (README decision 96), so a
  pile kept, a slot or a link that says machines opens System."
  {:machines :system})

(defn clean
  "A view id from what was stored or sent (a keyword or its name, an old name read as its new one),
  :picture for anything else."
  [v]
  (let [k (keyword (if (keyword? v) (name v) (str v)))
        k (get aliases k k)]
    (if (ids k) k :picture)))

(defn label "A view's name (a sheet's title)." [v]
  (some (fn [[id l]] (when (= id v) l)) all))

;; ---- the pile of sheets, bottom to top ----

(defn clean-pile
  "A pile from what was stored or sent (ids or their names, bottom to top): known sheets only (the
  board is never a sheet), each once, where it lies highest."
  [vs]
  (->> (if (sequential? vs) vs [])
       (map clean)
       (remove #{:picture})
       reverse
       distinct
       reverse
       vec))

(defn top "The view on top: the top sheet, or :picture (the board) when none." [pile]
  (or (peek (vec pile)) :picture))

(defn close "The pile without v." [pile v]
  (vec (remove #{(clean v)} (clean-pile pile))))

(defn open
  "The pile with v on top (brought up when it already lies there); :picture takes every sheet off."
  [pile v]
  (let [v (clean v)]
    (if (= v :picture) [] (conj (close pile v) v))))

(defn toggle "v closed when it is the top sheet, else opened on top." [pile v]
  (if (= (clean v) (top pile)) (close pile v) (open pile v)))

(defn shown
  "The sheets shown: the pile without the File Browser in a read-only view (another person's runner
  answers no folder calls)."
  [pile ro]
  (if ro (vec (remove #{:files} pile)) (vec pile)))

;; ---- on the wire and in the hub's store ----

(defn to-wire "The pile as JSON data: {views (bottom to top), view (the top one, for older pages)}." [pile]
  {:views (mapv name pile) :view (name (top pile))})

(defn from-wire
  "The pile from {views} or, from an older page, {view}: a pile of that one view (none for picture)."
  [{:keys [views view]}]
  (if (sequential? views) (clean-pile views) (clean-pile [view])))

(defn to-stored "The pile as the hub keeps it: the names, bottom to top, space separated." [pile]
  (str/join " " (map name pile)))

(defn from-stored
  "The pile from the hub's text: names separated by spaces (one name, or picture, before decision 91)."
  [text]
  (clean-pile (str/split (str/trim (str text)) #"\s+")))
