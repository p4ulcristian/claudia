(ns claudia.slave.chats.titles
  "A chat's title and the plain start of a reply: from its session, its first message, its reply."
  (:require [clojure.string :as str]
            [claudia.shared.files :as files]
            [claudia.shared.text :as text]
            [claudia.slave.history :as history]))

(defn- re-replace [s pattern flags to] (.replace (str s) (js/RegExp. pattern flags) to))

(defn- plain-text
  "Markdown as plain text on one line: marks taken out, code blocks kept as their text, links as
  their words."
  [md]
  (-> (history/strip-reminders md)
      (re-replace "^\\s*(```|~~~).*$" "gm" "")
      (re-replace "!\\[([^\\]]*)\\]\\([^)]*\\)" "g" "$1")
      (re-replace "\\[([^\\]]+)\\]\\([^)]*\\)" "g" "$1")
      (re-replace "^\\s{0,3}(#{1,6}\\s+|>\\s?|[-*+]\\s+(\\[[ xX]\\]\\s+)?|\\d+[.)]\\s+)" "gm" "")
      (re-replace "^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$" "gm" "")
      (re-replace "\\|" "g" " ")
      (re-replace "(\\*\\*|__|~~)(.+?)\\1" "g" "$2")
      (re-replace "(^|[^\\w*])[*_]([^*_\\n]+)[*_](?!\\w)" "g" "$1$2")
      (re-replace "`([^`]*)`" "g" "$1")
      (re-replace "<[^>\\n]+>" "g" "")
      (re-replace "\\s+" "g" " ")
      str/trim))

(defn plain-start
  "The start of a reply as plain text (src/chat.ts plainStart: a phone notification's body, the chat
  list's preview): markdown marks taken out, code blocks kept as their text, links as their words,
  all on one line, cut at a word. n: at most this many characters (code points)."
  [md n]
  (let [plain (plain-text md)
        cs (js/Array.from plain)]
    (if (<= (.-length cs) n)
      plain
      (let [shortened (.join (.slice cs 0 (dec n)) "")
            word (re-replace shortened "\\s+\\S*$" "" "")]
        (str (if (> (count word) (/ n 2)) word shortened) "…")))))

(defn first-prompt-title
  "A first message with attached files (runner.ts firstPromptTitle): its text, else the first file's
  name (the SDK may have cut the message short inside a tag)."
  [first-prompt]
  (let [p (str first-prompt)
        at (str/index-of p "<claudia-file ")]
    (if-not at
      p
      (or (not-empty (str/trim (subs p 0 at)))
          (not-empty (:name (first (:files (files/split-files p)))))
          (second (re-find #"\bname=\"([^\"]*)\"" p))
          ""))))

(defn session-title
  "A chat's title from its session (runner.ts shownRows): the custom title, the summary, the first
  prompt, or \"New chat\". (The SDK's summary falls back to the first prompt; then the first prompt's
  claudia-file tags are read as above.)"
  [^js info]
  (let [custom (not-empty (.-customTitle info))
        summary (not-empty (.-summary info))
        first-prompt (not-empty (.-firstPrompt info))]
    (or custom
        (when (and summary (not= summary first-prompt)) summary)
        (not-empty (first-prompt-title first-prompt))
        summary
        "New chat")))

(defn first-sent-title
  "The title of a chat whose session file isn't written yet (src/chat.ts send): the first line of
  the first message (120 characters), else its first file's name, else \"New chat\"."
  [message]
  (let [{t :text fs :files} (files/split-files (str message))
        line (text/cut (first (str/split-lines (str/trim t))) 120 "")]
    (or (not-empty line) (not-empty (:name (first fs))) "New chat")))

(defn title-updates
  "[id title] for each chat whose session (in the SDK's session list, JS) has another title now."
  [chats sessions]
  (let [have (into {} (map (juxt :id identity)) chats)]
    (vec (for [^js s sessions
               :let [r (get have (.-sessionId s))
                     t (session-title s)]
               :when (and r (not= t (:title r)))]
           [(:id r) t]))))
