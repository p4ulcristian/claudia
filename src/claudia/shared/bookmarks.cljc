(ns claudia.shared.bookmarks
  "A person's bookmarked folders (README decision 61), as data. The hub keeps one list per person,
  the same on every device: a bookmark is {:server :folder (an absolute path) :label (optional)}.
  The hub cleans what a page sends; the page adds, removes and filters with the same functions."
  (:require [clojure.string :as str]))

(def max-items "The most bookmarks a person keeps." 200)
(def max-label "The longest label (characters)." 80)
(def max-path "The longest folder path (characters)." 4096)

(def ^:private server-re #"^[a-z][a-z0-9-]{0,30}$")

(defn- cut [s n] (subs s 0 (min n (count s))))

(defn clean-folder
  "An absolute folder path as kept: no trailing slash (but \"/\"), no empty, `.` or `..` parts;
  nil when it is no absolute path."
  [s]
  (when (and (string? s) (str/starts-with? s "/") (<= (count s) max-path))
    (let [parts (remove str/blank? (str/split s #"/"))]
      (when-not (some #{"." ".."} parts)
        (str "/" (str/join "/" parts))))))

(defn clean-label
  "A label as kept: trimmed, at most max-label characters; nil when blank or not a string."
  [s]
  (when (string? s)
    (let [t (str/trim s)] (when-not (str/blank? t) (cut t max-label)))))

(defn clean-bookmark
  "One bookmark as kept ({:server :folder} and :label when given), or nil when it is not one."
  [b]
  (let [server (:server b)
        folder (clean-folder (:folder b))
        label (clean-label (:label b))]
    (when (and (string? server) (re-matches server-re server) folder)
      (cond-> {:server server :folder folder} label (assoc :label label)))))

(defn clean-items
  "A list from the hub or a page, kept as far as it is well formed: each [server folder] once (the
  first), at most max-items."
  [items]
  (->> (if (sequential? items) items [])
       (keep #(when (map? %) (clean-bookmark %)))
       (reduce (fn [{:keys [seen] :as acc} b]
                 (let [k [(:server b) (:folder b)]]
                   (if (or (seen k) (>= (count (:out acc)) max-items))
                     acc
                     {:seen (conj seen k) :out (conj (:out acc) b)})))
               {:seen #{} :out []})
       :out))

(defn on-server "The bookmarks of server, in their order." [items server]
  (filterv #(= server (:server %)) items))

(defn bookmarked?
  "True when folder on server is bookmarked."
  [items server folder]
  (boolean (some #(and (= server (:server %)) (= folder (:folder %))) items)))

(defn toggled
  "items with folder on server added at the end (when not there) or removed (when there)."
  [items server folder]
  (if (bookmarked? items server folder)
    (filterv #(not (and (= server (:server %)) (= folder (:folder %)))) items)
    (clean-items (conj (vec items) {:server server :folder folder}))))

(defn pinned
  "items with folder on server bookmarked: added at the end with label (when not there), or its label
  set to label (when there and label is given)."
  [items server folder label]
  (if (bookmarked? items server folder)
    (clean-items (mapv #(if (and label (= server (:server %)) (= folder (:folder %))) (assoc % :label label) %) items))
    (clean-items (conj (vec items) (cond-> {:server server :folder folder} label (assoc :label label))))))

(defn unpinned
  "items without the bookmark of folder on server."
  [items server folder]
  (filterv #(not (and (= server (:server %)) (= folder (:folder %)))) items))

(defn label-of
  "What a bookmark shows: its label, else its folder's last part (\"/\" for the root)."
  [{:keys [label folder]}]
  (or label (let [n (last (str/split (str folder) #"/"))] (if (str/blank? n) "/" n))))
