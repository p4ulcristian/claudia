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

(defn sync-state
  "Where a server is with the hub's token in use: :off (not reached), :on, or :behind."
  [{:keys [ok current]} in-use]
  (cond (not ok) :off (= current in-use) :on :else :behind))

(defn sync-title
  "The hover text for a server's state."
  [{:keys [label current error]} state]
  (case state
    :off (str label " not reached: " error)
    :on (str label " runs on " current)
    (str label " still runs on " (or current "a token set by hand") "; the hub writes it within a minute")))
