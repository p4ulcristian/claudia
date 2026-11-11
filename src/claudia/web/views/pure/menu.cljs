(ns claudia.web.views.pure.menu
  "Where a menu opened at the mouse sits, the chat menu's Move to project, and the rail's subscription
  button, as plain data."
  (:require [clojure.string :as str]
            [claudia.web.views.pure.sidebar :as sidebar]))

(defn menu-pos
  "A menu opened at x y kept 6 px inside the page's visible layer (l: its left top width height),
  the menu being w by h."
  [x y {:keys [left top width height]} w h]
  {:left (max 6 (min (- x left) (- width w 6)))
   :top (max 6 (min (- y top) (- height h 6)))})

(defn initials
  "\"Paul\" -> \"P\", \"Ananda Mishung\" -> \"AM\"."
  [person]
  (str/upper-case (apply str (take 2 (map first (str/split (str/trim (str person)) #"\s+"))))))

(defn k-tokens
  "Tokens in thousands: 155k (0 for none)."
  [n]
  (str (js/Math.round (/ (or n 0) 1000)) "k"))

(defn move-targets
  "The projects a chat on server can move to: the ones that server's chats (rows of every server)
  are in, [{:key (its root) :label :count}], most chats first; only those whose name holds text."
  [rows server text]
  (let [q (sidebar/search-key text)]
    (->> (sidebar/project-chips (filter #(= server (:server %)) rows))
         (remove #(= "" (:key %)))
         (filter #(or (empty? q) (str/includes? (str/lower-case (:label %)) q)))
         vec)))

(defn typed-folder
  "What Enter in the move box sends: the one project left by the text (targets: move-targets for it)
  when the text is no path, else the text as a folder; nil for no text."
  [targets text]
  (let [t (str/trim (str text))]
    (cond (str/blank? t) nil
          (and (not (re-find #"[/~]" t)) (= 1 (count targets))) (:key (first targets))
          :else t)))
