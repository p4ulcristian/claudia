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

(defn- fold-step
  "One message of Claude's turn seen from its end: acc {:later (a later text of the turn exists)
  :live (the turn still runs) :out (the ids folded so far)}."
  [{:keys [later live out] :as acc} m]
  (cond
    (not (#{"assistant" "tool"} (:role m))) (assoc acc :later false :live false)
    (= "tool" (:role m)) acc
    (or (:aside m) (and later (not live))) (assoc acc :later true :out (conj out (:id m)))
    :else (assoc acc :later true)))

(defn folded-ids
  "The ids of Claude's texts the page folds into the steps: of every turn (Claude's messages up to
  the next one that is not Claude's) each text but the last; in the turn still running (running?)
  only a text the runner marked :aside (a reply handed back to be shortened)."
  [msgs running?]
  (:out (reduce fold-step {:later false :live (boolean running?) :out #{}} (rseq (vec msgs)))))

(defn- in-group
  "out with m in its last group of steps (a new group when the last block is not one)."
  [out m]
  (if (= :steps (:k (peek out)))
    (conj (pop out) (update (peek out) :tools conj m))
    (conj out {:k :steps :id (:id m) :tools [m]})))

(defn blocks
  "The messages as the page shows them: runs of tool steps folded into one group, with Claude's
  texts whose id is in folded among them; a step that showed a picture or a video stands on its
  own, what it showed after it."
  ([msgs] (blocks msgs #{}))
  ([msgs folded]
   (reduce (fn [out m]
             (cond
               (and (= "tool" (:role m)) (or (:image m) (:video m)))
               (conj out {:k :steps :id (:id m) :tools [m]} {:k :shown :tool m})
               (or (= "tool" (:role m)) (folded (:id m))) (in-group out m)
               :else (conj out {:k :msg :m m})))
           [] msgs)))

(defn group-count
  "How much a folded group holds: \"3 steps\", \"3 steps, 2 notes\" (Claude's folded texts), \"1 note\"."
  [items]
  (let [n (count (remove #(= "assistant" (:role %)) items))
        said (- (count items) n)
        plural (fn [k word] (str k " " word (when (not= 1 k) "s")))]
    (str/join ", " (remove nil? [(when (or (pos? n) (zero? said)) (plural n "step"))
                                 (when (pos? said) (plural said "note"))]))))

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
  (str/join ", " (take 4 (distinct (map #(tool-label (or (:tool %) "tool"))
                                        (remove #(= "assistant" (:role %)) tools))))))
