(ns claudia.web.views.codesend
  "Code sent from an editor block, in the user's bubble: their note, then a chip per file
  (\"Sent total.ts +3 −1\") that opens the diff."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [reagent.hooks :as h]
            [claudia.web.md :as md]
            [claudia.web.views.git :as git]))

(def ^:private sent-re
  #"<claudia-code ([^>]*)>\n<diff>\n([\s\S]*?)\n</diff>\n<code>\n[\s\S]*?\n</code>\n</claudia-code>")

(defn- attr [attrs k]
  (-> (or (second (re-find (re-pattern (str "\\b" k "=\"([^\"]*)\"")) attrs)) "")
      (str/replace "&quot;" "\"") (str/replace "&amp;" "&")))

(defn code-sends
  "{:note :sends [{:name :added :removed :diff}]} for a message with sent code, else nil."
  [text]
  (when (str/includes? (str text) "<claudia-code ")
    (let [sends (vec (for [[_ attrs diff] (re-seq sent-re text)]
                       {:name (attr attrs "name") :added (attr attrs "added") :removed (attr attrs "removed") :diff diff}))]
      (when (seq sends)
        {:note (str/trim (str/replace text sent-re "")) :sends sends}))))

(defn- chip [{:keys [name added removed diff]}]
  (let [[open set-open] (h/use-state false)]
    [:div.code-sent
     [:button.code-chip {:type "button" :class (when open "on") :on-click #(set-open not)
                         :title (if open "Hide the diff" "Show what you changed")}
      [:span.code-chip-name (str "Sent " name)]
      [:span.add (str "+" added)]
      [:span.del (str "−" removed)]]
     (when open [:f> git/diff-view diff name])]))

(defn bubble
  "A user message's content: plain text, or the note and a chip per sent file."
  [text]
  (if-let [{:keys [note sends]} (code-sends text)]
    [:<>
     (when (seq note) [:div.md {:dangerouslySetInnerHTML (r/unsafe-html (md/html note))}])
     (for [[i s] (map-indexed vector sends)] ^{:key i} [:f> chip s])]
    text))
