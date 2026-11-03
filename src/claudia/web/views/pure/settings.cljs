(ns claudia.web.views.pure.settings
  "The list's title menu and the Settings screen as plain data."
  (:require [claudia.web.views.pure.words :as words]))

(defn list-title
  "The list's title: archived, another person's chats, or mine."
  [archived? view]
  (cond archived? "Archived chats" view (str (words/capital view) "'s chats") :else "My chats"))

(defn servers-in-view
  "The servers the list can show (in another person's view: those with their profile) and the ticked
  ones among them."
  [servers view mes hidden]
  (let [usable (filter #(or (nil? view) (some #{view} (get-in mes [(:name %) :profiles]))) servers)]
    {:usable usable :ticked (remove #(hidden (:name %)) usable)}))

(defn servers-hint
  "After the title, when only some servers are ticked: the one, or how many."
  [usable ticked]
  (when (and (seq ticked) (< (count ticked) (count usable)))
    (if (= 1 (count ticked)) (:label (first ticked)) (str (count ticked) " servers"))))

(defn whose-items
  "The Whose chats entries: [person label], mine first (person nil)."
  [people]
  (cons [nil "My chats"] (map (fn [p] [p (str (words/capital p) "'s chats")]) people)))
