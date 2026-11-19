(ns claudia.slave.bookmarks
  "An agent's bookmark tools (README decision 67): list_bookmarks and bookmark_folder in the workspace
  server read and change the runner owner's bookmarked folders, the list the Folders modal pins
  (README decision 61). They go to the hub as Claudia's own chat's tools do (POST
  /hub/server/claudia, this server's hub key, as CLAUDIA_OWNER_EMAIL: ops bookmarks and bookmark),
  so only a runner with Claudia's own chat (claudia.slave.claudia/on?) offers them."
  (:require ["node:path" :as path]
            [clojure.string :as str]
            [claudia.shared.bookmarks :as bm]
            [claudia.shared.protocol :as protocol]
            [claudia.slave.claudia :as claudia]
            [claudia.slave.hub-call :as hub-call]))

(def mcp
  "The tools, added to the workspace server when on."
  {:list_bookmarks
   {:description "List the user's bookmarked folders in Claudia (the pinned folders of the Folders window, the same on every device): server, folder and label of each."
    :inputSchema {:type "object" :properties {} :additionalProperties false}}
   :bookmark_folder
   {:description "Bookmark a folder in Claudia for the user (pin it in the Folders window, the same on every device), give a bookmark a new label, or remove one. Only when the user asks."
    :inputSchema {:type "object"
                  :additionalProperties false
                  :properties
                  (array-map
                   :folder {:type "string" :description "The folder: absolute; on this server also ~/… or relative to the working folder; default the working folder"}
                   :server {:type "string" :description "The server the folder is on (default this server)"}
                   :label {:type "string" :description "The name the bookmark shows (default the folder's last part)"}
                   :remove {:type "boolean" :description "true: remove the bookmark instead"})}}})

(def allowed ["mcp__workspace__list_bookmarks" "mcp__workspace__bookmark_folder"])

(def prompt
  "Its line in the system prompt."
  "## Bookmarks
The user's bookmarked folders (pinned in the Folders window, the same on every device) are listed by mcp__workspace__list_bookmarks and changed by mcp__workspace__bookmark_folder (add, relabel, remove). Bookmark only when the user asks.")

(defn on? [] (claudia/on?))

(defn target
  "The bookmark a bookmark_folder call names: {:server :folder}, or {:error}. here: this server's
  name; work: the chat's working folder; home: the home folder. On another server the folder must be
  absolute; on this one it may be ~/… or relative to work, and must be a folder (dir?)."
  [{:keys [folder server]} here work home dir?]
  (let [server (if (str/blank? server) here (str/trim server))
        local? (= server here)
        given (str/trim (str folder))
        p (cond (and local? (str/blank? given)) work
                local? (path/resolve work (str/replace given #"^~(?=/|$)" home))
                :else given)
        clean (bm/clean-folder p)]
    (cond (str/blank? p) {:error "Name the folder: this chat has no working folder."}
          (nil? clean) {:error (str "Not an absolute folder path: " p " (on another server give the whole path).")}
          (and local? (not (dir? clean))) {:error (str "No folder at " clean " on " server ".")}
          :else {:server server :folder clean})))

(defn listing
  "The answer to list_bookmarks: one line per bookmark."
  [items]
  (if (empty? items)
    "No bookmarks yet."
    (str/join "\n" (for [b items] (str (:server b) " " (:folder b) " (" (bm/label-of b) ")")))))

(defn answer
  "The hub's answer ({:ok :text}) as {:items} or {:error}."
  [{:keys [ok text]}]
  (let [body (try (protocol/read-json text) (catch :default _ nil))]
    (if ok {:items (bm/clean-items (:items body))} {:error (or (:error body) (str "The hub said: " text))})))

(defn- hub! [op args]
  (let [{:keys [email]} (claudia/config (.-env js/process))]
    (-> (hub-call/post! (hub-call/settings (.-env js/process)) "/hub/server/claudia" {:as email :op op :args args})
        (.then answer)
        (.catch #(do {:error (str "Could not reach the hub: " (.-message %))})))))

(defn list!
  "Promise of {:text} or {:error} for list_bookmarks."
  []
  (.then (hub! "bookmarks" {}) #(if (:error %) % {:text (listing (:items %))})))

(defn bookmark!
  "Promise of {:text} or {:error} for bookmark_folder with args, the bookmark t ({:server :folder})."
  [{label :label drop? :remove} t]
  (.then (hub! "bookmark" (cond-> t (seq label) (assoc :label label) drop? (assoc :remove true)))
         (fn [{:keys [error items]}]
           (if error
             {:error error}
             {:text (str (if drop? "Removed the bookmark of " "Bookmarked ") (:folder t) " on " (:server t)
                         ". Bookmarks now:\n" (listing items))}))))
