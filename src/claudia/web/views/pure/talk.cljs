(ns claudia.web.views.pure.talk
  "Chats' voices, as data (README decision 21): what Claude's reply sounds like read aloud, which
  language it is in, and the line of replies waiting to be read: one at a time, in arrival order,
  each once, none while the person holds the mic.

  The line st: {:queue [item…] :current item-or-nil :held bool :said [id…]}; an item is {:id (the
  turn's or ask's id) :k [server chat] :voice (a voice id) :text (the words to say)}."
  (:require [clojure.string :as str]))

(defn ask-text
  "An ask's questions, one after the other."
  [ask]
  (not-empty (str/join " " (keep #(some-> (:question %) str/trim not-empty) (:questions ask)))))

(def ^:private code-note "There's code in the chat.")
(def ^:private table-note "There's a table in the chat.")

(defn- without-blocks
  "Text without fenced code and tables, and the notes saying they were there."
  [text]
  (let [code? (boolean (re-find #"```" text))
        lines (str/split-lines (str/replace text #"```[\s\S]*?(```|$)" "\n"))
        table? (boolean (some #(re-find #"^\s*\|" %) lines))]
    [(str/join "\n" (remove #(re-find #"^\s*\|" %) lines))
     (cond-> [] code? (conj code-note) table? (conj table-note))]))

(defn- plain
  "Markdown marks and links as the words they show."
  [text]
  (-> text
      (str/replace #"!\[[^\]]*\]\([^)]*\)" "")
      (str/replace #"\[([^\]]+)\]\([^)]*\)" "$1")
      (str/replace #"<[^>]+>" "")
      (str/replace #"https?://\S+" "")
      (str/replace #"`([^`]*)`" "$1")
      (str/replace #"(?m)^\s{0,3}(#{1,6}|>|[-*+]|\d+\.)\s+" "")
      (str/replace #"[*_~]{1,3}([^*_~\n]+)[*_~]{1,3}" "$1")
      (str/replace #"\s+" " ")
      str/trim))

(defn speakable
  "A reply as words to say: no code, tables, links or Markdown marks; a short note where code or a
  table was. nil when nothing is left."
  [text]
  (let [[body notes] (without-blocks (str text))]
    (not-empty (str/join " " (filter seq (cons (plain body) notes))))))

(def ^:private said-kept "How many said ids the line remembers (a reply is read once)." 200)

(def empty-line {:queue [] :current nil :held false :said []})

(defn- known? [{:keys [queue current said]} id]
  (boolean (or (= id (:id current)) (some #(= id (:id %)) queue) (some #{id} said))))

(defn with-item
  "The line with item at its end, unless that reply is already waiting, being read or was read."
  [st item]
  (if (known? st (:id item)) st (update st :queue conj item)))

(defn with-item-first
  "The line with item read next (a reply's play button): first in the queue, read again even when it was."
  [st item]
  (update st :queue #(into [item] (remove (fn [q] (= (:id q) (:id item)))) %)))

(defn next-up
  "The line with its next item being read: nothing changes while one is read or the mic is held."
  [{:keys [queue current held] :as st}]
  (if (or current held (empty? queue))
    st
    (let [item (first queue)]
      (assoc st :queue (subvec queue 1) :current item
             :said (vec (take-last said-kept (conj (:said st) (:id item))))))))

(defn without-current
  "The line with the item being read dropped (read to its end, skipped, failed)."
  [st]
  (assoc st :current nil))

(defn held
  "The line while the mic is held (on: true) or let go: holding drops the item being read."
  [st on]
  (cond-> (assoc st :held on) on without-current))

(defn without-chat
  "The line without chat k's waiting replies, and without its item being read (its voice is Off)."
  [st k]
  (cond-> (update st :queue #(filterv (fn [q] (not= k (:k q))) %))
    (= k (:k (:current st))) without-current))

(defn spoken
  "What is said for a reply {:k :colour-name :text}: its chat's colour name first (\"Red here.\",
  decision 89), unless that chat is the open one (k = open): the person already sees who speaks."
  [{:keys [k colour-name text]} open]
  (if (and colour-name (not= k open)) (str colour-name " here. " text) text))

(def ^:private hungarian-words
  #"(?i)(^|\s)(és|hogy|nem|egy|az|ez|van|igen|már|csak|meg|kell|vagy|mert|még)(?=\s|[.,!?]|$)")

(defn language-of
  "The language a reply is read in: \"hu\" for Hungarian (its own letters ő ű, or three of its
  commonest words), else nil (English)."
  [text]
  (let [t (str text)]
    (when (or (re-find #"[őűŐŰ]" t)
              (>= (count (re-seq hungarian-words t)) 3))
      "hu")))
