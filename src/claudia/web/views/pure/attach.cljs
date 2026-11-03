(ns claudia.web.views.pure.attach
  "What the composer holds, as plain data.")

(defn- reading? [item]
  (and (:busy item) (not (:progress item)) (not (:error item))))

(defn groups
  "The composer's items: photos and videos ready to show (media), whether a file is still being read,
  and the files sent as uploads."
  [items]
  {:media (filter #(and (= "media" (:kind %)) (:url %)) items)
   :reading? (boolean (some reading? items))
   :uploads (remove #(or (= "media" (:kind %)) (reading? %)) items)})

(defn upload-title
  "An upload chip's hover text."
  [{file-name :name :keys [error file]}]
  (cond error (str file-name ": " error)
        file (str "Saved for Claude at " (:path file))
        :else (str "Sending " file-name "…")))

(defn upload-path
  "Where a sent file is fetched from on the chat's server: chats/<folder>/uploads/<name>, and the
  saved name (path's last part)."
  [path file-name]
  (let [parts (.split (str path) "/")]
    {:folder (last (butlast parts))
     :saved (or (last parts) file-name)}))
