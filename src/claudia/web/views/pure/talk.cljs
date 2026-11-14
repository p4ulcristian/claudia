(ns claudia.web.views.pure.talk
  "Talking mode, as data (README decision 21): what Claude's reply sounds like read aloud, which
  reply a turn ends with, and when a chat speaks at all."
  (:require [clojure.string :as str]
            [claudia.web.views.pure.switches :as switches]))

(defn- claude? [{:keys [role]}] (not (#{"user" "note"} role)))

(defn turn-reply
  "The text of the turn's final reply: Claude's last message after the person's last one (nil
  when there is none)."
  [msgs]
  (let [after (reverse (take-while #(not= "user" (:role %)) (reverse msgs)))]
    (some->> after (filter claude?) last :text str/trim not-empty)))

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

(defn speaks?
  "Whether a chat speaks now: it is the chat on screen and its speaker is on."
  [db k]
  (boolean (and (some? k) (= (:open db) k) (switches/talk-on? db k))))
