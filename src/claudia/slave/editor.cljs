(ns claudia.slave.editor
  "The editor form: code Claude and the user both edit. Each file keeps Claude's code (:code, its
  version :v) and the user's unsent text (:edit, its revision :rev). The page saves the user's typing
  here; Send turns it into a chat message; Claude's draw may not replace an unsent edit."
  (:require ["diff" :as diff]
            [clojure.string :as str]))

(defn- files [data] (if (sequential? (:files data)) (vec (:files data)) []))
(defn- pending? [f] (string? (:edit f)))

(defn guard-draw
  "Claude's draw into an editor block, given the block's old data: files whose code would replace
  an unsent edit lose their code (other fields still apply) and come back as refusals with the
  user's code; accepted code gets a new version and clears the edit. Claude can't set v, edit or rev.
  Returns {:data :refused [text]}."
  [old data]
  (let [had (into {} (map (juxt :id identity)) (files old))
        refused (atom [])
        out (mapv (fn [given]
                    (let [seen (:seen given)
                          f (dissoc given :v :edit :rev :seen)
                          before (get had (:id f))]
                      (cond
                        (not (string? (:code f))) f
                        (and (pending? before) (not= seen (:rev before)))
                        (if (= (:code f) (:edit before))
                          (assoc f :v (inc (or (:v before) 0)) :edit nil)
                          (do (swap! refused conj
                                     (str "File \"" (:id f) "\"" (when (:name before) (str " (" (:name before) ")"))
                                          ": the user has unsent edits (revision " (:rev before) "); your code was not drawn. Their code now:\n```\n"
                                          (:edit before) "\n```\nMerge your change into it and draw this file again with seen: " (:rev before) "."))
                              (dissoc f :code)))
                        (and before (= (:code f) (:code before)) (not (pending? before))) f
                        :else (assoc f :v (inc (or (:v before) 0)) :edit nil))))
                  (files data))]
    {:data (if (sequential? (:files data)) (assoc data :files out) data)
     :refused @refused}))

(defn user-edit
  "The user's typing, saved: base is the version of Claude's code the page had. A save made on an
  older version (Claude's new code arrived meanwhile) is dropped: nil."
  [data file text base]
  (let [list (files data)]
    (when-let [f (some #(when (= (:id %) file) %) list)]
      (when (= (or (:v f) 0) base)
        (let [next (assoc f :edit (when (not= text (or (:code f) "")) text) :rev (inc (or (:rev f) 0)))]
          (assoc data :files (mapv #(if (= (:id %) file) next %) list)))))))

(defn- line-count [patch sign]
  (count (filter #(and (str/starts-with? % sign) (not (str/starts-with? % (str sign sign sign))))
                 (str/split-lines patch))))

(defn- attr [s] (-> (str s) (str/replace "&" "&amp;") (str/replace "\"" "&quot;")))

(defn send-code
  "Send: every file with an unsent edit as one message (the user's note first, then a <claudia-code>
  per file with the diff against Claude's version and the full code). Each sent file's code becomes
  what was sent; its version stays, so the page's next save still fits. {:text :data} or nil."
  [block data note]
  (let [list (files data)
        sent (filter pending? list)]
    (when (seq sent)
      (let [parts (for [f sent
                        :let [nm (or (:name f) (:id f))
                              patch (->> (str/split-lines (diff/createTwoFilesPatch (str nm " (yours)") (str nm " (the user's)")
                                                                                    (or (:code f) "") (:edit f) js/undefined js/undefined
                                                                                    #js {:context 3}))
                                         (remove #(re-matches #"=+" %))
                                         (str/join "\n")
                                         str/trimr)]]
                    (str "<claudia-code block=\"" (attr block) "\" file=\"" (attr (:id f)) "\" name=\"" (attr nm)
                         "\" added=\"" (line-count patch "+") "\" removed=\"" (line-count patch "-") "\">\n<diff>\n"
                         patch "\n</diff>\n<code>\n" (:edit f) "\n</code>\n</claudia-code>"))]
        {:text (str/join "\n\n" (remove str/blank? (cons (str/trim (str note)) parts)))
         :data (assoc data :files (mapv #(if (pending? %) (assoc % :code (:edit %) :edit nil) %) list))}))))
