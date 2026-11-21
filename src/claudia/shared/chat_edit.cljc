(ns claudia.shared.chat-edit
  "What an agent may change of a chat by itself (README decision 88): its title and its colour, checked
  the same way in every chat's rename_chat and set_chat_colour (the runner)."
  (:require [clojure.string :as str]
            [claudia.shared.colours :as colours]))

(def max-title "The longest title a chat may have (the runner's rename cuts there too)." 200)

(def colours
  "The colours an agent may give a chat: the solids, the gradients, and ash (passive grey)."
  (conj colours/chat-colours "ash"))

(defn colour-ok? "Whether c is one of colours." [c] (boolean (some #{c} colours)))

(defn title-problem
  "What is wrong with title t (nil: not given), or nil."
  [t]
  (cond (nil? t) nil
        (not (string? t)) "title: text"
        (str/blank? t) "title: empty"
        (> (count (str/trim t)) max-title) (str "title: over " max-title " characters")
        :else nil))

(defn colour-problem
  "What is wrong with colour c (nil: not given), or nil."
  [c]
  (when (and (some? c) (not (colour-ok? c)))
    (str "color: one of " (str/join ", " colours))))

(defn title-change
  "rename_chat's args as the row's change {:title} (trimmed), or {:error}."
  [{:keys [title]}]
  (if-let [problem (if (nil? title) "title: needed" (title-problem title))]
    {:error problem}
    {:title (str/trim title)}))

(defn colour-change
  "set_chat_colour's args as the row's change {:color}, or {:error}."
  [{:keys [color]}]
  (if-let [problem (if (nil? color) "color: needed" (colour-problem color))]
    {:error problem}
    {:color color}))
