(ns claudia.web.views.pure.changes
  "The header's changes pill as plain data: which turn it shows and that turn's files."
  (:require [clojure.string :as str]
            [claudia.web.format :as format]))

(defn short-path
  "A changed file's path: inside the working folder from there; else with ~ for a home folder."
  [p folder]
  (let [p (str p)]
    (if (and folder (str/starts-with? p (str folder "/")))
      (subs p (inc (count folder)))
      (format/tilde p))))

(defn totals
  "Lines added and removed over changes."
  [changes]
  {:added (reduce + (map :added changes)) :removed (reduce + (map :removed changes))})

(defn turn-changes
  "The turn looked at (viewing: an earlier one whose picture shows, else the latest with changes),
  its changes, how it is named, its totals."
  [{:keys [changes messages]} viewing]
  (let [last-user (:id (last (filter #(= "user" (:role %)) messages)))
        turn (or viewing (:turn (peek (vec changes))))
        cs (filterv #(= turn (:turn %)) changes)]
    (merge {:turn turn
            :changes cs
            :label (cond viewing "Earlier turn" (= turn last-user) "This turn" :else "Last turn with changes")}
           (totals cs))))
