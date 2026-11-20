(ns claudia.shared.chat-move
  "Moving a chat to another server (README decision 80), the rules the hub and the runners share,
  on plain data: what a move asks for, which files of a session a copy may hold, and whether the
  copy on the target is the chat on the source (the check)."
  (:require [clojure.string :as str]))

(def ^:private chat-id-re #"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

(defn chat-id?
  "Whether s is a chat id (a Claude Code session id: a lower-case uuid)."
  [s]
  (boolean (and (string? s) (re-matches chat-id-re s))))

(def ^:private plain-name #"^[A-Za-z0-9_][A-Za-z0-9._-]{0,199}$")

(defn rel-ok?
  "Whether rel (a path relative to the session's project folder) is a file of chat id's session a
  copy may write: <id>.jsonl, or a file under <id>/ (its subagents, its tool results), at most five
  folders deep, every part a plain name (no `.` or `..`, no hidden name)."
  [id rel]
  (let [parts (str/split (str rel) #"/" -1)]
    (boolean (and (chat-id? id)
                  (or (= rel (str id ".jsonl"))
                      (and (= id (first parts))
                           (<= 2 (count parts) 6)
                           (every? #(re-matches plain-name %) (rest parts))))))))

(def max-folder "The longest folder a move may name." 4096)

(defn request-problem
  "What is wrong with a move asked for (body {chat from to folder?}) by a person whose servers (with
  their profile) are servers (names), or nil."
  [{:keys [chat from to folder]} servers]
  (let [mine (set servers)]
    (cond
      (not (chat-id? chat)) "chat: a chat id"
      (not (and (string? from) (mine from))) (str "not one of your servers: " from)
      (not (and (string? to) (mine to))) (str "not one of your servers: " to)
      (= from to) "the chat is on that server already"
      (not (or (nil? folder) (and (string? folder) (<= (count folder) max-folder)))) "folder: a path or nothing"
      :else nil)))

(defn messages-check
  "A session's messages as the check counts them, from its entries ([{:type :uuid}], the main
  session file's, in order): how many a person or Claude wrote (user and assistant entries with a
  uuid) and the uuid of the last of them."
  [entries]
  (let [ms (filter #(and (#{"user" "assistant"} (:type %)) (string? (:uuid %))) entries)]
    {:messages (count ms) :last (:uuid (last ms))}))

(defn- first-file-difference
  "The first file (by path) whose size or SHA-256 differs between two lists of {:path :size :sha256},
  or that only one of them has."
  [a b]
  (let [by (fn [xs] (into {} (map (juxt :path identity)) xs))
        am (by a)
        bm (by b)]
    (first (filter #(not= (get am %) (get bm %)) (sort (distinct (concat (keys am) (keys bm))))))))

(defn check-problem
  "Why the copy on server to (its check dst) is not the chat on server from (src), as the person
  reads it; nil when they match: the same number of messages, the same last message, every file
  the same size and SHA-256, as many pictures and file changes. A check:
  {:messages :last :files [{:path :size :sha256}] :pictures :changes}."
  [from to src dst]
  (cond
    (not (map? dst)) (str to " gave no check of its copy.")
    (not= (:messages src) (:messages dst))
    (str "The copy on " to " holds " (:messages dst) " messages, the chat on " from " " (:messages src) ".")
    (not= (:last src) (:last dst)) (str "The copy on " to " ends on another message than the chat on " from ".")
    (first-file-difference (:files src) (:files dst))
    (str "The copy of " (first-file-difference (:files src) (:files dst)) " on " to " is not the one on " from ".")
    (not= (:pictures src) (:pictures dst)) (str "The copy on " to " has " (:pictures dst) " pictures, " from " " (:pictures src) ".")
    (not= (:changes src) (:changes dst)) (str "The copy on " to " has " (:changes dst) " file changes, " from " " (:changes src) ".")
    :else nil))
