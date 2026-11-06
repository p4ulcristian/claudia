(ns claudia.web.views.pure.steps
  "Claude's tool steps as plain data: a tool's name as the chat shows it, runs of steps folded into
  groups, and what a folded group says."
  (:require [clojure.string :as str]))

(defn tool-label
  "A tool's name as the chat shows it: Draw, Folder, Ask; an MCP tool without its mcp__ prefix."
  [tool-name]
  (let [s (str tool-name)]
    (case s
      "mcp__picture__draw" "Draw"
      "mcp__workspace__set_folder" "Folder"
      "AskUserQuestion" "Ask"
      (if (str/starts-with? s "mcp__") (str/join " " (rest (str/split s #"__"))) s))))

(defn blocks
  "The messages as the page shows them: runs of tool steps folded into one group; a step that showed
  a picture or a video stands on its own, what it showed after it."
  [msgs]
  (reduce (fn [out m]
            (cond
              (and (= "tool" (:role m)) (or (:image m) (:video m)))
              (conj out {:k :steps :id (:id m) :tools [m]} {:k :shown :tool m})
              (= "tool" (:role m))
              (if (= :steps (:k (peek out)))
                (conj (pop out) (update (peek out) :tools conj m))
                (conj out {:k :steps :id (:id m) :tools [m]}))
              :else (conj out {:k :msg :m m})))
          [] msgs))

(defn step-summary
  "A step's one line: the runner sends an old step's as :title."
  [t]
  (if (contains? t :title) (:title t) (:summary t)))

(defn step-result
  "A step's result as text: the runner sends an old step's as {error text}."
  [result]
  (if (map? result) (:text result) result))

(defn group-names
  "What a folded group of steps was: its first four tools, each once."
  [tools]
  (str/join ", " (take 4 (distinct (map #(tool-label (or (:tool %) "tool")) tools)))))
