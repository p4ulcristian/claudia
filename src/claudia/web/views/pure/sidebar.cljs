(ns claudia.web.views.pure.sidebar
  "The chat list and the rail as plain data: which chats a search keeps, the order on the rail, a
  row's second line, how long ago."
  (:require [clojure.string :as str]
            [claudia.shared.text :as text]))

(defn search-key
  "The search text as compared: trimmed, lower case."
  [s]
  (str/lower-case (str/trim (str s))))

(defn by-title
  "The rows whose title holds q (a search-key); all of them for an empty q."
  [rows q]
  (if (seq q) (filter #(str/includes? (str/lower-case (str (:title %))) q) rows) rows))

(defn passive-last
  "Passive (ash) chats last, each part in its own order."
  [rows]
  (vec (concat (remove #(= "ash" (:color %)) rows) (filter #(= "ash" (:color %)) rows))))

(defn ago-text
  "How long ago t was at now: now, 5m, 3h, 2d; nil past a week (the date shows then)."
  [t now]
  (let [s (/ (- now t) 1000)]
    (cond (< s 60) "now"
          (< s 3600) (str (js/Math.floor (/ s 60)) "m")
          (< s 86400) (str (js/Math.floor (/ s 3600)) "h")
          (< s (* 86400 7)) (str (js/Math.floor (/ s 86400)) "d"))))

(defn second-line
  "What the chat does while it works or waits on you, else the start of Claude's last reply:
  [kind text]."
  [{:keys [waiting running agents asks preview]}]
  (let [n (or agents 0)
        ask (first asks)]
    (cond
      waiting (cond (= "question" (:kind ask)) ["waiting" (str "Asks you: " (text/one-line (:text ask)))]
                    (seq preview) ["waiting" (text/one-line preview)]
                    :else ["waiting" "Waiting for you"])
      running ["working" (if (pos? n) (str "Claude working · " n " agent" (when (not= n 1) "s")) "Claude working")]
      (pos? n) ["agents" (str "Agents working · " n)]
      :else ["reply" (text/one-line preview)])))

(defn hits-label
  "A found chat's count of hits."
  [n]
  (str n " hit" (when (not= 1 n) "s")))

(defn empty-note
  "What an empty list says."
  [q archived?]
  (cond (seq q) "No match." archived? "Nothing archived." :else "No chats yet."))
