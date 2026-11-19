(ns claudia.slave.folders
  "The folder browser's calls on the runner (README decisions 63 and 64; PROTOCOL.md, \"Runner
  HTTP: folders\"): one folder's sub-folders, its contents (folders and files), one file's text or
  bytes for its preview, a folder's facts (size, chats, git), and the Trash.
  The whole system is in reach as far as the runner's user may read and write; the runner never
  deletes the folders rules/delete-refusal names. Only the profile's own person may call them (the
  front's X-Claudia-Via \"own\"); a view or a write tick gets 403. Every path is resolved (`..`,
  symlinks) before any check."
  (:require [clojure.string :as str]
            [claudia.node.http :as io]
            [claudia.slave.folders.disk :as disk]
            [claudia.slave.folders.rules :as rules]
            [claudia.slave.folders.trash :as trash]
            [claudia.slave.files :as files]
            [claudia.slave.git :as git]
            [claudia.slave.store :as store]
            ["node:os" :as os]
            ["node:path" :as path]))

(def ^:private max-body 65536)

(def ^:private routes
  {["GET" "/api/folders"] :list
   ["GET" "/api/folders/contents"] :contents
   ["GET" "/api/folders/file"] :file
   ["GET" "/api/folders/facts"] :facts
   ["GET" "/api/folders/trashed"] :trashed
   ["POST" "/api/folders/trash"] :trash
   ["POST" "/api/folders/delete"] :delete
   ["POST" "/api/folders/restore"] :restore
   ["POST" "/api/folders/purge"] :purge
   ["POST" "/api/folders/missing"] :missing})

(defn route
  "Which folder call a request is (url without its query): an operation, :not-found for another
  /api/folders… call, or nil when it is none."
  [method url]
  (or (get routes [method url])
      (when (or (= url "/api/folders") (str/starts-with? url "/api/folders/")) :not-found)))

(defn own? "True when the caller is the profile's own person (the front's X-Claudia-Via)." [who]
  (= "own" (:via who)))

(defn kept-folders
  "The folders the runner never deletes, symlinks followed: {:home :cwd :data :install}."
  [{:keys [cwd data]}]
  {:home (disk/real-or-nil (os/homedir)) :cwd (disk/real-or-nil cwd)
   :data (disk/real-or-nil data) :install (disk/install-folder)})

;; ---- the answers (promises of a reply: the body, or {:status :error}) ----

(defn- with-real
  "Promise of (f real) for the path p resolved, or the OS error's reply."
  [p f]
  (-> (disk/real! p)
      (.then f (fn [^js e] (rules/error-reply (.-code e) (.-message e) p)))))

(defn- listing
  "One folder's sub-folders (p nil: \"/\"), and the work folder (the tree opens it at first)."
  [p cwd]
  (with-real (or p "/")
    (fn [real]
      (-> (disk/entries! real)
          (.then (fn [es] {:path real :entries (rules/listing real es) :cwd (disk/real-or-nil cwd)}))
          (.catch (fn [^js e] (rules/error-reply (.-code e) (.-message e) real)))))))

(defn- contents [p]
  (with-real p
    (fn [real] (.catch (disk/contents! real) (fn [^js e] (rules/error-reply (.-code e) (.-message e) real))))))

(defn- file-text
  "A file's text for its preview ({:path :text :mtime}, or {:path :error} when it is too big, not
  text or unreadable)."
  [p]
  (with-real p
    (fn [real] (assoc (files/read-text (path/dirname real) (path/basename real) nil) :path real))))

(def ^:private file-csp
  "The bytes of a previewed file run nothing: an SVG opened by itself draws, its scripts never run."
  "default-src 'none'; style-src 'unsafe-inline'; img-src data:")

(defn- send-file!
  "Sends the file at p (resolved) for its preview, in byte ranges when asked (video seeking, Safari):
  only the kinds rules/preview-types names (415 for others), 404 when p is no file."
  [^js req ^js res p]
  (-> (disk/real! p)
      (.then (fn [real] (.then (disk/file-stat! real) (fn [st] [real st]))))
      (.then (fn [[real st]]
               (let [t (rules/preview-type (path/basename real))]
                 (cond (nil? st) (io/send-json res 404 {:error (str "No such file: " real)})
                       (nil? t) (io/send-json res 415 {:error "No preview for this kind of file."})
                       (zero? (:size st)) (io/send-json res 404 {:error "The file is empty."})
                       :else (io/send-range res real (:size st)
                                            (cond-> {"content-type" t "cache-control" "private, no-cache"}
                                              (not= t "application/pdf") (assoc "content-security-policy" file-csp))
                                            (io/byte-range (.. req -headers -range) (:size st)))))))
      (.catch (fn [^js e] (let [r (rules/error-reply (.-code e) (.-message e) p)]
                            (io/send-json res (:status r) (dissoc r :status)))))))

(defn- repo-summary [folder]
  (.then (git/repo-facts folder) #(when % (rules/git-summary %))))

(defn- inner-summaries
  "Promise of the summaries of the repos below folder that hold work only here."
  [folder]
  (-> (disk/inner-repos! folder)
      (.then #(js/Promise.all (into-array (map repo-summary %))))
      (.then #(filterv (fn [g] (and g (rules/unpushed? g))) %))))

(defn- facts [p deep? config]
  (with-real p
    (fn [real]
      (-> (js/Promise.all #js [(disk/mtime! real) (repo-summary real)
                               (if deep? (inner-summaries real) (js/Promise.resolve nil))])
          (.then (fn [[mtime g inner]]
                   (cond-> (merge {:path real :mtime mtime :git g
                                   :chats (rules/chats-in (store/chats) real (:cwd config))
                                   :kept (rules/delete-refusal real (kept-folders config))}
                                  (disk/size real))
                     deep? (assoc :inner (if g [] inner)))))))))

(defn- body-path
  "Promise of the body's value under k (:path, :id, :paths) read from the request."
  [req k]
  (.then (io/read-json req max-body) #(get % k)))

(defn- answer
  "Promise of the reply to operation op ({:q (query) :req :who :config})."
  [op {:keys [^js q req who config]}]
  (let [kept #(kept-folders config)]
    (case op
      :list (listing (.get q "path") (:cwd config))
      :contents (contents (.get q "path"))
      :file (file-text (.get q "path"))
      :facts (facts (.get q "path") (= "1" (.get q "deep")) config)
      :trashed (js/Promise.resolve (trash/listed))
      :trash (.then (body-path req :path) (fn [p] (with-real p #(trash/trash! % (kept) (:by who)))))
      :delete (.then (body-path req :path) (fn [p] (with-real p #(trash/delete-now! % (kept)))))
      :restore (.then (body-path req :id) #(trash/restore! (:data config) (str %)))
      :purge (.then (body-path req :id) #(trash/purge! (:data config) (when (string? %) %)))
      :missing (.then (body-path req :paths) #(.then (disk/missing! (take 200 (filter string? %))) (fn [m] {:missing m})))
      :not-found (js/Promise.resolve {:status 404 :error "Not found."}))))

(defn- reply! [^js res answered]
  (-> answered
      (.then (fn [v] (if (:error v)
                       (io/send-json res (or (:status v) 400) (dissoc v :status))
                       (io/send-json res 200 v))))
      (.catch (fn [e] (io/send-json res 400 {:error (str (or (.-message e) e))})))))

(defn handle
  "Answers a folder call (true), else false. who: the caller ({:via :by}); config: {:cwd :data}."
  [^js req ^js res url who config]
  (if-let [op (route (.-method req) url)]
    (do (if (own? who)
          (let [q (js/URLSearchParams. (or (second (str/split (.-url req) #"\?" 2)) ""))]
            (if (and (= op :file) (not= "1" (.get q "read")))
              (send-file! req res (.get q "path"))
              (reply! res (answer op {:q q :req req :who who :config config}))))
          (io/send-json res 403 {:error "Folders are the profile's own person's only."}))
        true)
    false))
