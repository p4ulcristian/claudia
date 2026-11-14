(ns claudia.shared.colours
  "The chat colours, one list for the runner and the page, and how a new chat gets one: random
  among those the other chats wear least (src/colors.ts pickColor of the TypeScript Claudia).")

(def chat-colours
  "The colours a chat can have (passive ash aside, which is only picked by hand)."
  ["burgundy" "red" "orange" "amber" "yellow" "lime" "green" "teal" "cyan"
   "sky" "blue" "indigo" "violet" "purple" "magenta" "pink" "brown"])

(defn uncoloured
  "The ids of the chats without a colour (nil or blank)."
  [chats]
  (vec (keep #(when (empty? (:color %)) (:id %)) chats)))

(defn least-worn
  "The colours the other chats in the list (not archived, not except) wear least."
  [colours chats except]
  (let [used (frequencies (keep #(when (and (not (:archived %)) (not= except (:id %))) (:color %)) chats))
        least (apply min (map #(get used % 0) colours))]
    (filter #(= least (get used % 0)) colours)))

(defn chat-colour?
  "Whether c is a colour a new chat may be given."
  [c]
  (boolean (some #{c} chat-colours)))
