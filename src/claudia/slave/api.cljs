(ns claudia.slave.api
  "The runner's HTTP calls beyond /api/me and the vault read (PROTOCOL.md, \"Runner HTTP: chat
  operations\"): the delegate API another program on this machine uses (Eye on Rainbow's Gus),
  uploads, search, fork, explain, the working folder's project, all ~/Work sessions, the chat
  menu's changes, and Settings' tokens."
  (:require ["@anthropic-ai/claude-agent-sdk" :as sdk]
            ["node:fs" :as fs]
            [clojure.string :as str]
            [claudia.shared.protocol :as p]
            [claudia.slave.chats :as chats]
            [claudia.slave.explain :as explain]
            [claudia.slave.fork :as fork]
            [claudia.slave.history :as history]
            [claudia.slave.http :as h]
            [claudia.slave.projects :as projects]
            [claudia.slave.search :as search]
            [claudia.slave.store :as store]
            [claudia.slave.tokens :as tokens]
            [claudia.slave.uploads :as uploads]))

(defn- changed! [] ((:changed @chats/out)))

(defn- known? [id] (and (string? id) (some? (store/chat id))))

(defn- body-json
  "Promise of the request's JSON body as data ({} when empty or broken)."
  [req]
  (-> (h/read-body req (* 1024 1024))
      (.then (fn [t] (or (try (p/read-json t) (catch :default _ nil)) {})))))

(defn- text-of [body]
  (let [t (:text body)] (when (string? t) (let [t (str/trim t)] (when (<= 1 (count t) 20000) t)))))

(defn- reply-promise [^js res pr]
  (-> pr
      (.then (fn [v] (when-not (.-headersSent res)
                       (if (and (map? v) (:error v))
                         (h/json-reply res (or (:status v) 400) (dissoc v :status))
                         (h/json-reply res 200 v)))))
      (.catch (fn [e] (when-not (.-headersSent res)
                        (h/json-reply res 500 {:error (str (or (.-message e) e))}))))))

(defn least-used-colour []
  (let [used (frequencies (keep :color (remove :archived (store/chats))))
        least (apply min (map #(get used % 0) chats/chat-colors))]
    (rand-nth (filter #(= least (get used % 0)) chats/chat-colors))))

;; ---- the delegate API (decision 16 of the TypeScript Claudia) ----

(defn- delegate [req]
  (-> (body-json req)
      (.then (fn [b]
               (if-let [text (text-of b)]
                 {:id (chats/new-chat! text nil)}
                 {:error "text: 1 to 20000 characters"})))))

(defn- send-to [req id]
  (-> (body-json req)
      (.then (fn [b]
               (let [text (text-of b)]
                 (cond (not text) {:error "text: 1 to 20000 characters"}
                       (not (known? id)) {:error "no such chat" :status 404}
                       :else (do (store/update! id {:archived false})
                                 (chats/send-text! id text)
                                 (changed!)
                                 {:ok true})))))))

(defn- answer [req id]
  (-> (body-json req)
      (.then (fn [b]
               (let [text (text-of b)
                     open (->> (vals (:asks (get @chats/state id))) (map :ask) (filter #(= "question" (:kind %))))]
                 (cond (not text) {:error "text: 1 to 20000 characters"}
                       (empty? open) {:error "no open question in this chat" :status 409}
                       :else (do (doseq [a open]
                                   (chats/answer! id (:id a) (into {} (for [q (:questions a)] [(keyword (:question q)) text]))))
                                 {:ok true :answered (count open)})))))))

(defn- state-of
  "How a chat stands, for whoever started it from outside: working, waiting on a question, its last
  answer (the text after the last message sent), and the newest step, tool count and start of this
  turn for a progress line."
  [id]
  (if-not (known? id)
    (js/Promise.resolve {:known false :running false :waiting false :asks [] :last "" :step "" :steps 0 :since nil :agents 0})
    (-> (chats/snapshot id)
        (.then (fn [{:keys [messages running asks]}]
                 (let [msgs (vec messages)
                       last-user (or (last (keep-indexed #(when (= "user" (:role %2)) %1) msgs)) -1)
                       turn (subvec msgs (inc last-user))
                       newest (peek turn)
                       started (:at (get msgs last-user))]
                   {:known true
                    :running (boolean running)
                    :waiting (boolean (seq asks))
                    :asks (vec asks)
                    :last (str/join "\n\n" (keep #(when (= "assistant" (:role %)) (:text %)) turn))
                    :step (let [s (case (:role newest)
                                    "tool" (str (:tool newest) ": " (:summary newest))
                                    "assistant" (first (str/split-lines (str (:text newest))))
                                    "")]
                            (subs s 0 (min 160 (count s))))
                    :steps (count (filter #(= "tool" (:role %)) turn))
                    :since (when (and running started (pos? started)) (.toISOString (js/Date. started)))
                    :agents 0}))))))

;; ---- all ~/Work sessions ----

(defn- sessions
  "Claude Code sessions of the working folder that are not chats here (newest first)."
  []
  (-> (sdk/listSessions #js {:dir chats/cwd :limit 300 :includeWorktrees false})
      (.then (fn [list]
               (let [have (set (map :id (store/chats)))]
                 {:sessions (vec (for [^js s list
                                       :when (not (have (.-sessionId s)))]
                                   {:id (.-sessionId s)
                                    :title (or (not-empty (.-customTitle s)) (not-empty (.-summary s)) (not-empty (.-firstPrompt s)) "Session")
                                    :at (.-lastModified s)}))})))))

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
    (h/json-reply res 404 {:error "No such image."})))

(defn- find-image
  "Promise of the n-th image block of the user entry `uuid`, or of the tool result for tool use `tool`."
  [id {:keys [uuid n tool]}]
  (-> (.readFile (.-promises fs) (history/session-file chats/cwd id) "utf8")
      (.then (fn [text]
               (some (fn [line]
                       (when (or (and uuid (str/includes? line uuid)) (and tool (str/includes? line tool)))
                         (let [^js e (try (js/JSON.parse line) (catch :default _ nil))
                               content (some-> e .-message .-content)]
                           (when (and e (= "user" (.-type e)) (array? content))
                             (if uuid
                               (when (= uuid (.-uuid e))
                                 (nth (filterv #(= "image" (.-type ^js %)) content) n nil))
                               (some (fn [^js b]
                                       (when (and (= "tool_result" (.-type b)) (= tool (.-tool_use_id b)) (array? (.-content b)))
                                         (some #(when (= "image" (.-type ^js %)) %) (.-content b))))
                                     content))))))
                     (str/split-lines text))))
      (.catch (fn [_] nil))))

;; ---- the router ----

(defn handle
  "Answers the call when it is one of these; false when not (the runner answers 404)."
  [^js req ^js res {:keys [read-only] who :profile}]
  (let [method (.-method req)
        [path qs] (str/split (.-url req) #"\?" 2)
        q (js/URLSearchParams. (or qs ""))
        parts (mapv js/decodeURIComponent (rest (str/split path #"/")))
        reply #(reply-promise res %)]
    (if (and read-only (or (not= "GET" method)
                           ;; Settings' tokens and logins are the profile's own business.
                           (#{"claude-tokens" "github"} (second parts))))
      (do (h/json-reply res 403 {:error "Read only."}) true)
      (condp = [method (vec (take 2 parts)) (count parts)]
        ["POST" ["api" "delegate"] 2] (do (reply (delegate req)) true)
        ["GET" ["api" "search"] 2]
        (let [arch (= "1" (.get q "archived"))
              rows (for [r (store/chats) :when (= arch (boolean (:archived r)))]
                     {:id (:id r) :title (:title r) :file (history/session-file chats/cwd (:id r))})]
          (h/json-reply res 200 {:found (search/search (or (.get q "q") "") rows)}) true)
        ["GET" ["api" "sessions"] 2] (do (reply (sessions)) true)
        ["GET" ["api" "project"] 2]
        (let [pr (projects/project-of (.get q "folder") chats/cwd)]
          (h/json-reply res 200 (if pr {:name (:name pr) :logo (boolean (:logo pr))} {:name nil :logo false})) true)
        ["GET" ["api" "project"] 3]
        (let [pr (projects/project-of (.get q "folder") chats/cwd)]
          (if-let [logo (and (= "logo" (nth parts 2)) (:logo pr))]
            (do (.writeHead res 200 #js {"content-type" (projects/logo-type logo)
                                         "cache-control" "private, max-age=600"
                                         "x-content-type-options" "nosniff"
                                         "content-security-policy" "default-src 'none'; style-src 'unsafe-inline'"})
                (.pipe (fs/createReadStream logo) res))
            (h/json-reply res 404 {:error "No logo."}))
          true)
        ["GET" ["api" "claude-tokens"] 2] (do (h/json-reply res 200 (tokens/state)) true)
        ["PUT" ["api" "claude-token"] 2]
        (do (reply (-> (body-json req) (.then #(tokens/save! (:name %) (:token %) who))))
            true)
        ["POST" ["api" "claude-token"] 3]
        (do (reply (-> (body-json req) (.then #(tokens/use! (:name %) who)))) true)
        ["GET" ["api" "github"] 2] (do (reply (.then (tokens/github-user) (fn [u] {:user u}))) true)
        ["PUT" ["api" "github"] 2] (do (reply (-> (body-json req) (.then #(tokens/set-github-token! (:token %))))) true)
        ["POST" ["api" "sessions"] 4] (do (reply (adopt (nth parts 2))) true)
        ;; /api/chats/:id/...
        (if (and (= ["api" "chats"] (vec (take 2 parts))) (>= (count parts) 3))
          (let [id (nth parts 2)
                [_ _ _ op a b] parts]
            (case [method op]
              ["POST" "send"] (do (reply (send-to req id)) true)
              ["POST" "answer"] (do (reply (answer req id)) true)
              ["GET" "state"] (do (reply (state-of id)) true)
              ["POST" "uploads"] (do (uploads/save! req res id (.get q "name")) true)
              ["GET" "uploads"] (do (uploads/serve! res id a) true)
              ["GET" "sent"] (do (.then (find-image id {:uuid a :n (js/Number b)}) #(image-reply res %)) true)
              ["GET" "shown"] (do (.then (find-image id {:tool a}) #(image-reply res %)) true)
              ["POST" "fork"]
              (do (reply (if-not (known? id)
                           (js/Promise.resolve {:error "no such chat" :status 404})
                           (-> (body-json req)
                               (.then #(fork/fork! chats/cwd id (when (string? (:at %)) (not-empty (:at %)))
                                                   (boolean (:running (get @chats/state id))) (least-used-colour)))
                               (.then (fn [fid] (changed!) {:id fid}))
                               (.catch (fn [e] {:error (.-message e) :status 409})))))
                  true)
              ["POST" "explain"]
              (do (reply (if-not (known? id)
                           (js/Promise.resolve {:error "no such chat" :status 404})
                           (-> (body-json req)
                               (.then (fn [{:keys [step]}]
                                        (let [f (.-CLAUDIA_TOKEN_FILE (.-env js/process))
                                              token (when f (try (str/trim (fs/readFileSync f "utf8")) (catch :default _ nil)))]
                                          (cond (not (string? step)) {:error "step: a tool call's id"}
                                                (not (seq token)) {:error "No Claude token on this runner." :status 503}
                                                :else (.then (explain/explain chats/cwd id step token) (fn [t] {:text t})))))))))
                  true)
              ["PATCH" nil]
              (do (reply (-> (body-json req)
                             (.then (fn [{:keys [title archived color]}]
                                      (if-not (known? id)
                                        {:error "no such chat" :status 404}
                                        (do (store/update! id (cond-> {}
                                                                (and (string? title) (seq (str/trim title))) (assoc :title (subs (str/trim title) 0 (min 200 (count (str/trim title)))))
                                                                (boolean? archived) (assoc :archived archived)
                                                                (and (string? color) (some #{color} chats/chat-colors)) (assoc :color color)
                                                                ;; passive: grey, remembering the colour it had
                                                                (= color "ash") (assoc :color "ash" :prev_color (let [c (:color (store/chat id))] (if (= c "ash") (:prev-color (store/chat id)) c)))
                                                                ;; awake again: the colour it had, else a fresh one
                                                                (= color "wake") (assoc :color (or (:prev-color (store/chat id)) (least-used-colour)))))
                                            (changed!)
                                            {:ok true}))))))
                  true)
              false))
          false)))))
