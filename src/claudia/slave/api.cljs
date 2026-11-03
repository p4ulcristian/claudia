(ns claudia.slave.api
  "The runner's HTTP calls beyond /api/me and the vault read (PROTOCOL.md, \"Runner HTTP: chat
  operations\"): the delegate API another program on this machine uses (Eye on Rainbow's Gus),
  uploads, search, fork, explain, the limits kept per token (the front collects them for the hub), the working folder's project, all ~/Work sessions, the chat
  menu's changes, and Settings' tokens."
  (:require ["@anthropic-ai/claude-agent-sdk" :as sdk]
            [claudia.node.http :as io]
            ["node:fs" :as fs]
            [clojure.string :as str]
            [claudia.slave.chats :as chats]
            [claudia.slave.codeforms :as codeforms]
            [claudia.slave.explain :as explain]
            [claudia.slave.fork :as fork]
            [claudia.slave.history :as history]
            [claudia.slave.meta :as meta]
            [claudia.slave.projects :as projects]
            [claudia.slave.search :as search]
            [claudia.slave.store :as store]
            [claudia.slave.tasks :as tasks]
            [claudia.slave.tokens :as tokens]
            [claudia.slave.uploads :as uploads]))

(defn- changed! [] ((:changed @chats/out)))

(defn- known? [id] (and (string? id) (some? (store/chat id))))

(def ^:private max-body (* 1024 1024))

(defn text-of
  "A request body's text, trimmed, when it is 1 to 20000 characters; else nil."
  [body]
  (let [t (:text body)] (when (string? t) (let [t (str/trim t)] (when (<= 1 (count t) 20000) t)))))

(defn- reply-promise [^js res answer]
  (-> answer
      (.then (fn [v] (when-not (.-headersSent res)
                       (if (and (map? v) (:error v))
                         (io/send-json res (or (:status v) 400) (dissoc v :status))
                         (io/send-json res 200 v)))))
      (.catch (fn [e] (when-not (.-headersSent res)
                        (io/send-json res 500 {:error (str (or (.-message e) e))}))))))

(defn least-used-colour [] (chats/least-used-colour))

(def ^:private no-chat (js/Promise.resolve {:error "no such chat" :status 404}))

;; ---- the delegate API (decision 16 of the TypeScript Claudia) ----

(defn- delegate [req]
  (-> (io/read-json req max-body)
      (.then (fn [b]
               (if-let [t (text-of b)]
                 {:id (chats/new-chat! t nil)}
                 {:error "text: 1 to 20000 characters"})))))

(defn- send-to [req id]
  (-> (io/read-json req max-body)
      (.then (fn [b]
               (let [t (text-of b)]
                 (cond (not t) {:error "text: 1 to 20000 characters"}
                       (not (known? id)) {:error "no such chat" :status 404}
                       :else (do (store/update! id {:archived false})
                                 (chats/wake! id)
                                 (chats/send-text! id t)
                                 (changed!)
                                 {:ok true})))))))

(defn answers-for
  "The answers to an ask: the same text for each of its questions, keyed by the question."
  [ask t]
  (into {} (for [q (:questions ask)] [(keyword (:question q)) t])))

(defn- answer [req id]
  (-> (io/read-json req max-body)
      (.then (fn [b]
               (let [t (text-of b)
                     open (->> (vals (:asks (get @chats/state id))) (map :ask) (filter #(= "question" (:kind %))))]
                 (cond (not t) {:error "text: 1 to 20000 characters"}
                       (empty? open) {:error "no open question in this chat" :status 409}
                       :else (do (doseq [a open] (chats/answer! id (:id a) (answers-for a t)))
                                 {:ok true :answered (count open)})))))))

(def unknown-state
  {:known false :running false :waiting false :asks [] :last "" :step "" :steps 0 :since nil :agents 0})

(defn- newest-step
  "The newest message of a turn as a progress line: \"<tool>: <title>\" or the start of what it said."
  [newest]
  (let [cut #(subs % 0 (min 160 (count %)))]
    (case (:role newest)
      "tool" (cut (str (or (:label newest) (:tool newest)) ": " (or (:title newest) (:summary newest))))
      "assistant" (cut (first (str/split-lines (str (:text newest)))))
      "")))

(defn turn-state
  "How a known chat stands, from its snapshot and the number of agents it left working."
  [{:keys [messages running asks since]} agents]
  (let [msgs (vec messages)
        last-user (or (last (keep-indexed #(when (= "user" (:role %2)) %1) msgs)) -1)
        turn (subvec msgs (inc last-user))]
    {:known true
     :running (boolean running)
     :waiting (boolean (seq asks))
     :asks (vec (for [a asks :when (= "question" (:kind a))] (select-keys a [:id :questions])))
     :last (str/join "\n\n" (keep #(when (= "assistant" (:role %)) (:text %)) turn))
     :step (newest-step (peek turn))
     :steps (count (filter #(= "tool" (:role %)) turn))
     :since (when (and running since) (.toISOString (js/Date. since)))
     :agents agents}))

(defn- state-of
  "How a chat stands, for whoever started it from outside (runner.ts /api/chats/:id/state): working,
  waiting on a question, its last answer (the text after the last message sent), and the newest step
  (\"<tool>: <title>\" or the start of what it said), tool count and start of this turn for a
  progress line, and how many agents it left working."
  [id]
  (if-not (known? id)
    (js/Promise.resolve unknown-state)
    (.then (chats/snapshot id) #(turn-state % (tasks/working id)))))

;; ---- all ~/Work sessions ----

(defn- session-row [^js s]
  {:id (.-sessionId s)
   :title (or (not-empty (.-customTitle s)) (not-empty (.-summary s)) (not-empty (.-firstPrompt s)) "Session")
   :at (.-lastModified s)})

(defn- sessions
  "Claude Code sessions of the working folder that are not chats here (newest first)."
  []
  (-> (sdk/listSessions #js {:dir chats/cwd :limit 300 :includeWorktrees false})
      (.then (fn [found]
               (let [have (set (map :id (store/chats)))]
                 {:sessions (vec (for [^js s found :when (not (have (.-sessionId s)))] (session-row s)))})))))

(defn- adopt [id]
  (if (known? id)
    (js/Promise.resolve {:id id})
    (-> (sdk/getSessionInfo id #js {:dir chats/cwd})
        (.then (fn [^js s]
                 (if-not s
                   {:error "no such session" :status 404}
                   (let [title (or (not-empty (.-customTitle s)) (not-empty (.-summary s)) "Session")]
                     (store/add! {:id id :title (subs title 0 (min 80 (count title))) :at (.-lastModified s)
                                  :folder nil :color (least-used-colour)})
                     (store/update! id {:ended (.-lastModified s) :seen (.-lastModified s)})
                     (changed!)
                     {:id id})))))))

;; ---- images shown and sent, from the session file ----

(defn- image-reply [^js res ^js im]
  (if (and im (re-matches #"image/(jpeg|png|gif|webp)" (str (some-> im .-source .-media_type))))
    (do (.writeHead res 200 #js {"content-type" (.. im -source -media_type)
                                 "cache-control" "private, max-age=86400"
                                 "x-content-type-options" "nosniff"})
        (.end res (js/Buffer.from (.. im -source -data) "base64")))
    (io/send-json res 404 {:error "No such image."})))

(defn- image-in
  "In a parsed user entry e (content its blocks): the n-th image when entry is the user entry
  entry, else the image of the tool result for tool use tool."
  [^js e content {entry :uuid n :n tool :tool}]
  (if entry
    (when (= entry (.-uuid e))
      (nth (filterv #(= "image" (.-type ^js %)) content) n nil))
    (some (fn [^js b]
            (when (and (= "tool_result" (.-type b)) (= tool (.-tool_use_id b)) (array? (.-content b)))
              (some #(when (= "image" (.-type ^js %)) %) (.-content b))))
          content)))

(defn image-of-line
  "The image a session file line holds for want ({:uuid :n} or {:tool}), or nil."
  [line {entry :uuid tool :tool :as want}]
  (when (or (and entry (str/includes? line entry)) (and tool (str/includes? line tool)))
    (let [^js e (try (js/JSON.parse line) (catch :default _ nil))
          content (some-> e .-message .-content)]
      (when (and e (= "user" (.-type e)) (array? content))
        (image-in e content want)))))

(defn- find-image
  "Promise of the n-th image block of the user entry `uuid`, or of the tool result for tool use `tool`."
  [id want]
  (-> (.readFile (.-promises fs) (history/session-file chats/cwd id) "utf8")
      (.then (fn [t] (some #(image-of-line % want) (str/split-lines t))))
      (.catch (fn [_] nil))))

;; ---- chat operations ----

(defn- fork-chat [req id]
  (if-not (known? id)
    no-chat
    (-> (io/read-json req max-body)
        (.then #(fork/fork! chats/cwd id (when (string? (:at %)) (not-empty (:at %)))
                            (boolean (:running (get @chats/state id))) (least-used-colour)))
        (.then (fn [fid] (chats/refresh-title! fid) (changed!) {:id fid}))
        (.catch (fn [e] {:error (.-message e) :status 409})))))

(defn- claude-token []
  (let [f (.-CLAUDIA_TOKEN_FILE (.-env js/process))]
    (when f (try (str/trim (fs/readFileSync f "utf8")) (catch :default _ nil)))))

(defn- explain-step [req id]
  (if-not (known? id)
    no-chat
    (.then (io/read-json req max-body)
           (fn [{:keys [step]}]
             (let [token (claude-token)]
               (cond (not (string? step)) {:error "step: a tool call's id"}
                     (not (seq token)) {:error "No Claude token on this runner." :status 503}
                     :else (.then (explain/explain chats/cwd id step token
                                                   (some #(when (= step (:id %)) %) (:turn (get @chats/state id))))
                                  (fn [t] {:text t}))))))))

(defn chat-update
  "What a chat menu PATCH changes in the chat's row (row: the chat as stored now): archived, a colour
  of the palette, ash (passive: grey, remembering the colour it had) or wake (the colour it had, else
  (fresh), a new one)."
  [{:keys [archived color]} row palette fresh]
  (cond-> {}
    (boolean? archived) (assoc :archived archived)
    (and (string? color) (some #{color} palette)) (assoc :color color)
    (= color "ash") (assoc :color "ash" :prev_color (let [c (:color row)] (if (= c "ash") (:prev-color row) c)))
    (= color "wake") (assoc :color (or (:prev-color row) (fresh)) :prev_color nil)))

(defn- patch-chat [req id]
  (.then (io/read-json req max-body)
         (fn [{:keys [title] :as b}]
           (if-not (known? id)
             {:error "no such chat" :status 404}
             (do (when (string? title) (chats/rename! id title))
                 (store/update! id (chat-update b (store/chat id) chats/chat-colors #(chats/least-used-colour id)))
                 (changed!)
                 {:ok true})))))

;; ---- the other calls ----

(defn- search-rows [archived]
  (for [r (store/chats) :when (= archived (boolean (:archived r)))]
    {:id (:id r) :title (:title r) :file (history/session-file chats/cwd (:id r))}))

(defn- project-info [folder]
  (let [found (projects/project-of folder chats/cwd)]
    (if found {:name (:name found) :logo (boolean (:logo found))} {:name nil :logo false})))

(defn- project-logo [^js res folder part]
  (if-let [logo (and (= "logo" part) (:logo (projects/project-of folder chats/cwd)))]
    (do (.writeHead res 200 #js {"content-type" (projects/logo-type logo)
                                 "cache-control" "private, max-age=600"
                                 "x-content-type-options" "nosniff"
                                 "content-security-policy" "default-src 'none'; style-src 'unsafe-inline'"})
        (.pipe (fs/createReadStream logo) res))
    (io/send-json res 404 {:error "No logo."})))

;; The profile's preferences (runner.ts /api/prefs): chatColors, the forms deleted in the Forms
;; window (they leave Claude's draw tool), and the opt-in forms this runner doesn't have.
(defn- set-prefs [req]
  (.then (io/read-json req max-body)
         (fn [b]
           (let [out (codeforms/set-prefs! b)]
             (when (contains? b :hiddenForms) (chats/prefs-changed!))
             (assoc out :notHere codeforms/not-here)))))

;; ---- the router ----

(def ^:private api-routes
  "[method [\"api\" name] parts] -> operation, for /api/<name>[/...]."
  {["POST" ["api" "delegate"] 2] :delegate
   ["GET" ["api" "search"] 2] :search
   ["GET" ["api" "limits"] 2] :limits
   ["GET" ["api" "sessions"] 2] :sessions
   ["GET" ["api" "project"] 2] :project
   ["GET" ["api" "project"] 3] :project-logo
   ["GET" ["api" "prefs"] 2] :prefs
   ["PATCH" ["api" "prefs"] 2] :set-prefs
   ["GET" ["api" "github"] 2] :github
   ["PUT" ["api" "github"] 2] :set-github
   ["POST" ["api" "sessions"] 4] :adopt})

(def ^:private chat-routes
  "[method op] -> operation, for /api/chats/:id[/op/a/b]."
  {["POST" "send"] :send
   ["POST" "answer"] :answer
   ["GET" "state"] :state
   ["POST" "uploads"] :upload
   ["GET" "uploads"] :download
   ["GET" "sent"] :sent
   ["GET" "shown"] :shown
   ["POST" "fork"] :fork
   ["POST" "explain"] :explain
   ["PATCH" nil] :patch-chat})

(defn route
  "Which operation a call is: method, the path's decoded parts (without the leading empty one) and
  whether the caller only reads. :read-only (refused: anything but GET, and Settings' logins, the
  profile's own business), an operation keyword, or nil when it is none of these."
  [method parts read-only]
  (cond
    (and read-only (or (not= "GET" method) (#{"github"} (second parts)))) :read-only
    :else (or (get api-routes [method (vec (take 2 parts)) (count parts)])
              (when (and (= ["api" "chats"] (vec (take 2 parts))) (>= (count parts) 3))
                (get chat-routes [method (nth parts 3 nil)])))))

(defn- operate
  "Runs operation op for the call ({:req :res :q (query) :parts}); reply answers a promise."
  [op {:keys [^js req ^js res ^js q parts]}]
  (let [reply #(reply-promise res %)
        [_ _ id _ a b] parts]
    (case op
      :read-only (io/send-json res 403 {:error "Read only."})
      :delegate (reply (delegate req))
      :search (io/send-json res 200 {:found (search/search (or (.get q "q") "") (search-rows (= "1" (.get q "archived"))))})
      :sessions (reply (sessions))
      :limits (io/send-json res 200 {:tokens (meta/heard)})
      :project (io/send-json res 200 (project-info (.get q "folder")))
      :project-logo (project-logo res (.get q "folder") (nth parts 2))
      :prefs (io/send-json res 200 (assoc (codeforms/prefs) :notHere codeforms/not-here))
      :set-prefs (reply (set-prefs req))
      :github (reply (.then (tokens/github-user) (fn [u] {:user u})))
      :set-github (reply (.then (io/read-json req max-body) #(tokens/set-github-token! (:token %))))
      :adopt (reply (adopt (nth parts 2)))
      :send (reply (send-to req id))
      :answer (reply (answer req id))
      :state (reply (state-of id))
      :upload (uploads/save! req res id (.get q "name"))
      :download (uploads/serve! res id a)
      :sent (.then (find-image id {:uuid a :n (js/Number b)}) #(image-reply res %))
      :shown (.then (find-image id {:tool a}) #(image-reply res %))
      :fork (reply (fork-chat req id))
      :explain (reply (explain-step req id))
      :patch-chat (reply (patch-chat req id)))))

(defn handle
  "Answers the call when it is one of these; false when not (the runner answers 404)."
  [^js req ^js res {:keys [read-only]}]
  (let [[path qs] (str/split (.-url req) #"\?" 2)
        parts (mapv js/decodeURIComponent (rest (str/split path #"/")))]
    (if-let [op (route (.-method req) parts read-only)]
      (do (operate op {:req req :res res :q (js/URLSearchParams. (or qs "")) :parts parts}) true)
      false)))
