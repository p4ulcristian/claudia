(ns claudia.shared.files
  "Files attached to a message (not photos or videos, which go as images): the message carries one
  <claudia-file> tag per file after the user's text. The runner writes the tags; the page writes the
  same text for its own copy (so the runner's echo matches it) and splits a sent message back into
  the text and its file chips."
  (:require [clojure.string :as str]))

(def max-bytes "The largest file a runner takes." (* 100 1024 1024))

(defn- attr [s]
  (-> (str s) (str/replace "&" "&amp;") (str/replace "\"" "&quot;")
      (str/replace "<" "&lt;") (str/replace ">" "&gt;") (str/replace "\n" " ")))

(defn- unattr [s]
  (-> (str s) (str/replace "&quot;" "\"") (str/replace "&lt;" "<") (str/replace "&gt;" ">") (str/replace "&amp;" "&")))

(defn tag [{:keys [path name size type]}]
  (str "<claudia-file path=\"" (attr path) "\" name=\"" (attr name) "\" size=\"" size "\" type=\"" (attr type)
       "\">The user attached this file; it is saved at the path.</claudia-file>"))

(defn with-files
  "The user's text with the files after it."
  [text files]
  (if (empty? files)
    text
    (str/join "\n\n" (filter seq (cons text (map tag files))))))

(defn- attr-of [attrs k]
  (unattr (second (re-find (re-pattern (str "\\b" k "=\"([^\"]*)\"")) attrs))))

(defn split-files
  "A sent message split back into {:text :files [{path name size type}]}."
  [text]
  (let [text (str text)]
    (if-not (str/includes? text "<claudia-file ")
      {:text text :files []}
      {:text (str/trim (str/replace text #"\n*<claudia-file ([^>]*)>[^<]*</claudia-file>" ""))
       :files (vec (for [[_ attrs] (re-seq #"<claudia-file ([^>]*)>[^<]*</claudia-file>" text)]
                     {:path (attr-of attrs "path") :name (attr-of attrs "name")
                      :size (or (parse-long (attr-of attrs "size")) 0) :type (attr-of attrs "type")}))})))

(defn size-label [n]
  (cond (< n 1024) (str n " B")
        (< n (* 1024 1024)) (str (Math/round (/ n 1024)) " KB")
        :else (let [mb (/ n 1024 1024)]
                (str (if (< n (* 10 1024 1024)) (.toFixed mb 1) (Math/round mb)) " MB"))))
