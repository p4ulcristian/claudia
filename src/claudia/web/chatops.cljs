(ns claudia.web.chatops
  "The page's chat operations beyond talking: whose chats show (another profile's, read only) and
  which servers' (ticks), search, archived chats, all ~/Work sessions, Settings (tokens, GitHub),
  attachments, fork, explain-a-step, chat colours, project logos, older pages of a chat."
  (:require [re-frame.core :as rf]
            [clojure.string :as str]
            [claudia.shared.protocol :as protocol]
            [claudia.web.api :as api]
            [claudia.web.db :as db]
            [claudia.web.events :as events]
            [claudia.web.media :as media]))

;; ---- HTTP to one server's runner ----

(defn api-url
  "/s/<server>/api/<path>, with ?view= when another profile's chats show (reads only)."
  [server p & [view]]
  (let [u (str "/s/" (js/encodeURIComponent server) "/api/" p)]
    (if view (str u (if (str/includes? u "?") "&" "?") "view=" (js/encodeURIComponent view)) u)))

(rf/reg-fx
 :api/call
 ;; {:method :url :body :on-ok [event ...] :on-fail [event ...]}: the answer (or the error text) is conj'd.
 (fn [{:keys [method url body on-ok on-fail]}]
   (-> (api/call-json (or method "GET") url body)
       (.then #(when on-ok (rf/dispatch (conj on-ok %))))
       (.catch #(when on-fail (rf/dispatch (conj on-fail (.-message %))))))))

(defn read-only? [db] (some? (:view db)))

;; ---- whose chats, and which servers' ----

(rf/reg-event-fx
 :ops/me-loaded
 ;; After /hub/me: each server's own /api/me (its profiles, version, commit) and GitHub login.
 (fn [{:keys [db]} _]
   {:fx (vec (for [s (events/server-names db)]
               [:api/call {:url (api-url s "me") :on-ok [:ops/server-me s] :on-fail [:ops/server-me s nil]}]))}))

(defn view-servers
  "The servers whose sockets are open for a view: all for your own chats; for another profile's,
  the servers that have that profile."
  [db view]
  (if view
    (filterv #(some #{view} (get-in db [:server-me % :profiles])) (events/server-names db))
    (events/server-names db)))

(defn view-due?
  "A stored view connects once every server said whether it has that profile."
  [db]
  (boolean (and (:view db)
                (not (:view-connected db))
                (every? #(get-in db [:server-me %]) (events/server-names db)))))

(defn connect-view
  "The view's servers connecting (db), and the sockets to open again."
  [db]
  (let [servers (view-servers db (:view db))]
    {:db (assoc db :view-connected true :servers (events/connecting-servers servers))
     :ws/reconnect {:view (:view db) :servers servers}}))

(rf/reg-event-fx
 :ops/server-me
 (fn [{:keys [db]} [_ server me]]
   (let [db (assoc-in db [:server-me server] (or me {:failed true}))]
     (cond-> {:db db}
       (view-due? db) (merge (connect-view db))))))

(rf/reg-event-fx
 :ops/view
 ;; Whose chats show: nil = your own, else a profile name (read only). Every socket starts over.
 (fn [{:keys [db]} [_ view]]
   (let [servers (view-servers db view)]
     {:db (-> db
              (assoc :view view :view-connected true :open nil :screen :list :chats {} :search nil
                     :archived? false :settings? false)
              (assoc :servers (events/connecting-servers servers)))
      :store ["claudia.view" view]
      :history/push nil
      :ws/reconnect {:view view :servers servers}})))

(defn tick-server
  "The hidden servers once server is ticked: a hidden one shows again, a shown one leaves the list,
  except the last one shown."
  [hidden servers server]
  (let [hidden (set hidden)]
    (cond (hidden server) (disj hidden server)
          (<= (count (remove hidden servers)) 1) hidden
          :else (conj hidden server))))

(rf/reg-event-fx
 :ops/tick-server
 (fn [{:keys [db]} [_ server]]
   (let [hidden (tick-server (:hidden-servers db) (keys (:servers db)) server)]
     {:db (assoc db :hidden-servers hidden) :store ["claudia.hiddenServers" (clj->js (vec hidden))]})))

(rf/reg-sub :view (fn [db] (:view db)))
(rf/reg-sub :read-only? (fn [db] (read-only? db)))
(rf/reg-sub :hidden-servers (fn [db] (set (:hidden-servers db))))
(rf/reg-sub :server-me (fn [db [_ s]] (get-in db [:server-me s])))
(rf/reg-sub :server-mes (fn [db] (:server-me db)))

(defn people
  "Every other profile on the servers you use (the title menu's \"<person>'s chats\"), from each
  server's /api/me."
  [server-me]
  (let [own (set (keep :profile (vals server-me)))]
    (->> (vals server-me) (mapcat :profiles) (remove own) distinct sort vec)))

(rf/reg-sub :people (fn [db] (people (:server-me db))))

;; ---- the list's modes: archived chats, all sessions, search ----

(rf/reg-event-db :ops/settings (fn [db [_ on]] (assoc db :settings? on :screen (if (db/phone?) :list (:screen db)))))

(rf/reg-event-db :ops/archived (fn [db [_ on]] (assoc db :archived? on :settings? false :search nil :project-filter nil)))

;; The archived list's project chip: a project's root, "" for the chats with no project, nil for all.
(rf/reg-event-db :ops/project-filter (fn [db [_ k]] (assoc db :project-filter k)))
(rf/reg-sub :project-filter (fn [db] (:project-filter db)))

(rf/reg-event-fx
 :ops/all-sessions
 (fn [{:keys [db]} [_ on]]
   (cond-> {:db (assoc db :all-sessions? on) :store ["claudia.allSessions" on]}
     on (assoc :fx (vec (for [s (keys (:servers db))]
                          [:api/call {:url (api-url s "sessions" (:view db)) :on-ok [:ops/sessions s]}]))))))

(rf/reg-event-db :ops/sessions (fn [db [_ server {:keys [sessions]}]] (assoc-in db [:sessions server] sessions)))

(rf/reg-event-fx
 :ops/adopt
 ;; A Claude Code session that isn't a chat here yet becomes one, and opens.
 (fn [{:keys [db]} [_ server id]]
   (if (read-only? db)
     {}
     {:api/call {:method "POST" :url (api-url server (str "sessions/" id "/adopt"))
                 :on-ok [:ops/adopted server] :on-fail [:ops/error]}})))

(rf/reg-event-fx
 :ops/adopted
 (fn [{:keys [db]} [_ server {:keys [id]}]]
   {:db (update-in db [:sessions server] (fn [l] (vec (remove #(= id (:id %)) l))))
    :fx [[:dispatch [:nav/open [server id]]]]}))

(rf/reg-sub :list-mode (fn [db] {:archived? (:archived? db) :all-sessions? (:all-sessions? db) :settings? (:settings? db)}))
(rf/reg-sub :sessions (fn [db] (:sessions db)))

(defonce ^:private search-timer (atom nil))

(rf/reg-fx
 :ops/search-later
 (fn [text]
   (some-> @search-timer js/clearTimeout)
   (reset! search-timer (js/setTimeout #(rf/dispatch [:ops/search-now text]) 220))))

(rf/reg-event-fx
 :ops/search
 (fn [{:keys [db]} [_ text]]
   {:db (assoc-in db [:search :text] text)
    :ops/search-later text}))

(defn search-path
  "The runner's search for text: among the archived chats when archived? (the archived list),
  else among every chat, archived ones too."
  [text archived?]
  (str "search?q=" (js/encodeURIComponent text) "&archived=" (if archived? "1" "all")))

(rf/reg-event-fx
 :ops/search-now
 (fn [{:keys [db]} [_ text]]
   (cond
     ;; Typed over, or the search closed (a chat picked) while it waited.
     (not= text (get-in db [:search :text])) nil
     (< (count (str/trim (str text))) 2) {:db (assoc-in db [:search :found] nil)}
     :else
     {:db (assoc-in db [:search :found] {})
      :fx (vec (for [s (keys (:servers db))]
                 [:api/call {:url (api-url s (search-path text (:archived? db)) (:view db))
                             :on-ok [:ops/found s text]}]))})))

(rf/reg-event-db
 :ops/found
 (fn [db [_ server text {:keys [found]}]]
   (if (= text (get-in db [:search :text]))
     (assoc-in db [:search :found server] found)
     db)))

(rf/reg-sub :search (fn [db] (:search db)))

;; ---- the chat menu: colour, rename, fork, archive ----

(rf/reg-event-db :ops/menu (fn [db [_ m]] (assoc db :menu m)))
(rf/reg-sub :menu (fn [db] (:menu db)))

(rf/reg-event-fx
 :ops/color
 (fn [{:keys [db]} [_ [s c] color]]
   (when-not (read-only? db)
     {:api/call {:method "PATCH" :url (api-url s (str "chats/" c)) :body {:color color} :on-fail [:ops/error]}})))

(rf/reg-event-fx
 :ops/fork
 (fn [{:keys [db]} [_ [s c] at]]
   (when-not (read-only? db)
     {:db (assoc-in db [:menu :forking] true)
      :api/call {:method "POST" :url (api-url s (str "chats/" c "/fork")) :body (if at {:at at} {})
                 :on-ok [:ops/forked s] :on-fail [:ops/fork-failed]}})))

(rf/reg-event-fx
 :ops/forked
 (fn [{:keys [db]} [_ s {:keys [id]}]]
   {:db (assoc db :menu nil)
    :fx [[:dispatch [:nav/open [s id]]]]}))

(rf/reg-event-db :ops/fork-failed (fn [db [_ e]] (update db :menu #(when % (assoc % :forking false :error e)))))

(rf/reg-event-db :ops/error (fn [db [_ e]] (assoc db :error e)))

;; ---- explain a step ----

(rf/reg-event-fx
 :ops/explain
 (fn [{:keys [db]} [_ [s c] step]]
   (let [k [s c step]]
     (if (get-in db [:explain k :text])
       {:db (update-in db [:explain k :open] not)}
       {:db (assoc-in db [:explain k] {:busy true :open true})
        :api/call {:method "POST" :url (api-url s (str "chats/" c "/explain") (:view db)) :body {:step step}
                   :on-ok [:ops/explained k] :on-fail [:ops/explain-failed k]}}))))

(rf/reg-event-db :ops/explained (fn [db [_ k {:keys [text]}]] (assoc-in db [:explain k] {:text text :open true})))
(rf/reg-event-db :ops/explain-failed (fn [db [_ k e]] (assoc-in db [:explain k] {:error e :open true})))
(rf/reg-sub :explain (fn [db [_ k]] (get-in db [:explain k])))

;; ---- project of a working folder ----

(rf/reg-event-fx
 :ops/project
 (fn [{:keys [db]} [_ server folder]]
   (when (and server (seq folder) (not (contains? (:projects db) [server folder])))
     {:db (assoc-in db [:projects [server folder]] {:loading true})
      :api/call {:url (api-url server (str "project?folder=" (js/encodeURIComponent folder)) (:view db))
                 :on-ok [:ops/project-loaded server folder]}})))

(rf/reg-event-db :ops/project-loaded (fn [db [_ s f p]] (assoc-in db [:projects [s f]] p)))
(rf/reg-sub :project (fn [db [_ s f]] (get-in db [:projects [s f]])))

(defn logo-url [view server folder]
  (api-url server (str "project/logo?folder=" (js/encodeURIComponent folder)) view))

;; ---- Settings: Claude tokens and GitHub logins (kept by the hub) ----

(rf/reg-event-fx
 :ops/load-settings
 (fn [{:keys [db]} [_ server]]
   (when (and server (not (read-only? db)))
     {:db (assoc db :settings-server server)
      :fx [[:dispatch [:ops/load-tokens]]
           [:dispatch [:ops/load-github]]]})))

;; The hub's Claude tokens (claudia.hub.tokens): the one in use on every server, the saved names,
;; what each of the person's servers runs on now.
(rf/reg-event-fx
 :ops/load-tokens
 (fn [{:keys [db]} _]
   (when-not (read-only? db)
     {:fx [[:api/call {:url "/hub/claude-tokens" :on-ok [:ops/tokens]}]]})))

(rf/reg-event-db :ops/tokens (fn [db [_ t]] (assoc db :tokens t)))
(rf/reg-sub :tokens (fn [db] (:tokens db)))
(rf/reg-sub :settings-server (fn [db] (or (:settings-server db) (first (events/server-names db)))))

;; The person's GitHub login, kept by the hub (claudia.hub.github-login): the login and what each of
;; their servers' gh is logged in as.
(rf/reg-event-fx
 :ops/load-github
 (fn [{:keys [db]} _]
   (when-not (read-only? db)
     {:fx [[:api/call {:url "/hub/github-login" :on-ok [:ops/github-login]}]]})))

(rf/reg-event-db :ops/github-login (fn [db [_ g]] (assoc db :github-login g)))
(rf/reg-sub :github-login (fn [db] (:github-login db)))

(rf/reg-fx
 :ops/put
 ;; {:url :method :body :on-done (fn [ok error])}
 (fn [{:keys [url method body on-done]}]
   (-> (api/call-json (or method "PUT") url body)
       (.then #(on-done % nil))
       (.catch #(on-done nil (.-message %))))))

;; ---- attachments ----

(defn- upload-target
  "[server chat-folder-id] for files added to a draft: the open chat's, or a new chat's own id."
  [db draft-key]
  (if (vector? draft-key)
    draft-key
    [(events/default-server db) (or (:new-upload-id db) "")]))

(defn with-upload-id
  "A new chat's draft gets its own id for uploads (id) the first time files are added to it."
  [db draft-key id]
  (if (and (not (vector? draft-key)) (not (:new-upload-id db))) (assoc db :new-upload-id id) db))

(rf/reg-event-fx
 :ops/attach
 ;; Files added to the composer (picker, paste, drop); files: a JS array of File.
 (fn [{:keys [db]} [_ draft-key files]]
   (let [db (with-upload-id db draft-key (events/new-uuid))
         [server chat] (upload-target db draft-key)
         items (vec (for [f files] {:id (events/new-uuid) :name (.-name ^js f) :size (.-size ^js f) :busy true}))]
     {:db (update-in db [:attach draft-key] (fnil into []) items)
      :ops/read-files {:draft-key draft-key :server server :chat chat
                       :pairs (mapv vector items files)}})))

(def ^:private upload-max (* 100 1024 1024))

(defn upload-path
  "Where a file named file-name is uploaded, for a chat (its folder id)."
  [chat file-name]
  (str "chats/" chat "/uploads?name=" (js/encodeURIComponent file-name)))

(defn upload-answer
  "What an upload's answer (its HTTP status, its body as data or nil) makes of its attachment."
  [status body]
  (if (and (<= 200 status 299) (:path body))
    {:kind "file" :file body :busy false}
    {:error (or (:error body) (str "Upload failed (HTTP " status ")"))}))

(defn- upload!
  "Sends f to url; each step (progress, the answer, a failure) calls (done m)."
  [url ^js f done]
  (let [xhr (js/XMLHttpRequest.)]
    (.open xhr "POST" url)
    (.setRequestHeader xhr "content-type" (or (not-empty (.-type f)) "application/octet-stream"))
    (set! (.. xhr -upload -onprogress)
          (fn [e] (when (.-lengthComputable e) (done {:progress (/ (.-loaded e) (.-total e))}))))
    (set! (.-onload xhr)
          #(done (upload-answer (.-status xhr)
                                (try (protocol/read-json (.-responseText xhr)) (catch :default _ nil)))))
    (set! (.-onerror xhr) #(done {:error "Upload failed: no connection."}))
    (.send xhr f)))

(defn- read-file!
  "A photo or video becomes images; anything else up to 100 MB is uploaded as a file."
  [{:keys [server chat]} ^js f done]
  (-> (media/read-media f)
      (.then (fn [m]
               (cond
                 m (done (assoc m :kind "media"))
                 (> (.-size f) upload-max) (done {:error "Over 100 MB: too big to send."})
                 :else (upload! (api-url server (upload-path chat (.-name f))) f done))))))

(rf/reg-fx
 :ops/read-files
 (fn [{:keys [draft-key pairs] :as target}]
   (doseq [[{:keys [id]} ^js f] pairs]
     (read-file! target f #(rf/dispatch [:ops/attached draft-key id %])))))

(defn attached
  "The attachments with item id updated by m; once read (a kind) or failed (an error) it is no
  longer busy."
  [items id m]
  (mapv #(if (= id (:id %))
           (cond-> (merge % m) (or (:kind m) (:error m)) (assoc :busy false))
           %)
        items))

(rf/reg-event-db
 :ops/attached
 (fn [db [_ draft-key id m]] (update-in db [:attach draft-key] attached id m)))

(rf/reg-event-db
 :ops/unattach
 (fn [db [_ draft-key id]] (update-in db [:attach draft-key] (fn [items] (vec (remove #(= id (:id %)) items))))))

(rf/reg-sub :attach (fn [db [_ k]] (get-in db [:attach k])))

;; ---- older pages of a chat ----

(rf/reg-event-fx
 :ops/older
 (fn [{:keys [db]} [_ [s c :as k]]]
   (let [st (get-in db [:chats k])
         first-id (:id (first (:messages st)))]
     (when (and (:more st) (not (:loading-older st)) first-id)
       {:db (assoc-in db [:chats k :loading-older] true)
        :ws/send [s {:type "older" :chat c :before first-id}]}))))

(defn with-older
  "A chat's state with an older page (the messages before the id before) in front, when the chat
  still starts there."
  [st messages more before]
  (if (= before (:id (first (:messages st))))
    (assoc st :messages (into (vec messages) (:messages st)) :more more :loading-older false)
    (assoc st :loading-older false)))

(defmethod events/on-frame "older" [db server {:keys [chat messages more before]}]
  {:db (update-in db [:chats [server chat]] with-older messages more before)})
