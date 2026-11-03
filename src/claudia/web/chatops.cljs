(ns claudia.web.chatops
  "The page's chat operations beyond talking: whose chats show (another profile's, read only) and
  which servers' (ticks), search, archived chats, all ~/Work sessions, Settings (tokens, GitHub),
  attachments, fork, explain-a-step, chat colours, project logos, older pages of a chat."
  (:require [re-frame.core :as rf]
            [clojure.string :as str]
            [claudia.shared.protocol :as protocol]
            [claudia.web.db :as db]
            [claudia.web.events :as events]
            [claudia.web.media :as media]
            [claudia.web.ws :as ws]))

;; ---- HTTP to one server's runner ----

(defn api-url
  "/s/<server>/api/<path>, with ?view= when another profile's chats show (reads only)."
  [server p & [view]]
  (let [u (str "/s/" (js/encodeURIComponent server) "/api/" p)]
    (if view (str u (if (str/includes? u "?") "&" "?") "view=" (js/encodeURIComponent view)) u)))

(defn- call [method url body]
  (-> (js/fetch url (clj->js (cond-> {:method method :credentials "same-origin"
                                      :headers {"accept" "application/json" "content-type" "application/json"}}
                               body (assoc :body (protocol/write-json body)))))
      (.then (fn [^js r]
               (.then (.text r)
                      (fn [t]
                        (let [b (when-not (str/blank? t) (try (protocol/read-json t) (catch :default _ nil)))]
                          (if (.-ok r) b (throw (js/Error. (or (:error b) (str "HTTP " (.-status r)))))))))))))

(rf/reg-fx
 :api/call
 ;; {:method :url :body :on-ok [event ...] :on-fail [event ...]}: the answer (or the error text) is conj'd.
 (fn [{:keys [method url body on-ok on-fail]}]
   (-> (call (or method "GET") url body)
       (.then #(when on-ok (rf/dispatch (conj on-ok %))))
       (.catch #(when on-fail (rf/dispatch (conj on-fail (.-message %))))))))

(defn- server-names [db] (mapv :name (get-in db [:me :servers])))

(defn read-only? [db] (some? (:view db)))

;; ---- whose chats, and which servers' ----

(rf/reg-event-fx
 :ops/me-loaded
 ;; After /hub/me: each server's own /api/me (its profiles, version, commit) and GitHub login.
 (fn [{:keys [db]} _]
   {:fx (vec (for [s (server-names db)]
               [:api/call {:url (api-url s "me") :on-ok [:ops/server-me s] :on-fail [:ops/server-me s nil]}]))}))

(defn- view-servers
  "The servers whose sockets are open for a view: all for your own chats; for another profile's,
  the servers that have that profile."
  [db view]
  (if view
    (filterv #(some #{view} (get-in db [:server-me % :profiles])) (server-names db))
    (server-names db)))

(rf/reg-event-fx
 :ops/server-me
 (fn [{:keys [db]} [_ server me]]
   (let [db (assoc-in db [:server-me server] (or me {:failed true}))
         all? (every? #(get-in db [:server-me %]) (server-names db))
         view (:view db)]
     (cond-> {:db db}
       ;; A stored view connects once every server said whether it has that profile.
       (and view all? (not (:view-connected db)))
       (merge {:db (assoc db :view-connected true
                          :servers (into {} (map (fn [n] [n {:status :connecting :rows {}}])) (view-servers db view)))
               :ws/reconnect {:view view :servers (view-servers db view)}})
       (and me (not view) (:profile me))
       (assoc :api/call {:url (api-url server "github") :on-ok [:ops/github server (:profile me)]})))))

(rf/reg-event-fx
 :ops/view
 ;; Whose chats show: nil = your own, else a profile name (read only). Every socket starts over.
 (fn [{:keys [db]} [_ view]]
   (let [servers (view-servers db view)]
     {:db (-> db
              (assoc :view view :view-connected true :open nil :screen :list :chats {} :search nil
                     :archived? false :settings? false)
              (assoc :servers (into {} (map (fn [n] [n {:status :connecting :rows {}}])) servers)))
      :store ["claudia.view" view]
      :history/push nil
      :ws/reconnect {:view view :servers servers}})))

(rf/reg-event-fx
 :ops/tick-server
 ;; A server ticked off leaves the list; the last ticked one stays.
 (fn [{:keys [db]} [_ server]]
   (let [hidden (set (:hidden-servers db))
         shown (remove hidden (keys (:servers db)))
         next (cond (hidden server) (disj hidden server)
                    (<= (count shown) 1) hidden
                    :else (conj hidden server))]
     {:db (assoc db :hidden-servers next) :store ["claudia.hiddenServers" (clj->js (vec next))]})))

(rf/reg-sub :view (fn [db] (:view db)))
(rf/reg-sub :read-only? (fn [db] (read-only? db)))
(rf/reg-sub :hidden-servers (fn [db] (set (:hidden-servers db))))
(rf/reg-sub :server-me (fn [db [_ s]] (get-in db [:server-me s])))
(rf/reg-sub :server-mes (fn [db] (:server-me db)))

(rf/reg-sub
 :people
 ;; Every other profile on the servers you use (the title menu's "<person>'s chats").
 (fn [db]
   (let [own (set (keep :profile (vals (:server-me db))))]
     (->> (vals (:server-me db)) (mapcat :profiles) (remove own) distinct sort vec))))

;; ---- the list's modes: archived chats, all sessions, search ----

(rf/reg-event-db :ops/settings (fn [db [_ on]] (assoc db :settings? on :screen (if (db/phone?) :list (:screen db)))))

(rf/reg-event-db :ops/archived (fn [db [_ on]] (assoc db :archived? on :settings? false :search nil)))

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

(rf/reg-event-fx
 :ops/search-now
 (fn [{:keys [db]} [_ text]]
   (if (< (count (str/trim (str text))) 2)
     {:db (assoc-in db [:search :found] nil)}
     {:db (assoc-in db [:search :found] {})
      :fx (vec (for [s (keys (:servers db))]
                 [:api/call {:url (api-url s (str "search?q=" (js/encodeURIComponent text) (when (:archived? db) "&archived=1")) (:view db))
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

;; ---- Settings: tokens, GitHub ----

(rf/reg-event-fx
 :ops/load-settings
 (fn [{:keys [db]} [_ server]]
   (when (and server (not (read-only? db)))
     {:db (assoc db :settings-server server)
      :fx [[:api/call {:url (api-url server "claude-tokens") :on-ok [:ops/tokens server]}]
           [:api/call {:url (api-url server "github") :on-ok [:ops/github-user server]}]]})))

(rf/reg-event-db :ops/tokens (fn [db [_ s t]] (assoc-in db [:tokens s] t)))
(rf/reg-event-db :ops/github-user (fn [db [_ s {:keys [user]}]] (assoc-in db [:github s] (or user false))))
(rf/reg-sub :tokens (fn [db [_ s]] (get-in db [:tokens s])))
(rf/reg-sub :github (fn [db [_ s]] (get-in db [:github s])))
(rf/reg-sub :settings-server (fn [db] (or (:settings-server db) (first (server-names db)))))

(defn github-later-key [server profile] (str "claudia.github-later." server "." profile))

(rf/reg-event-db
 :ops/github
 ;; A profile without a GitHub login is asked once per browser and server ("Later" hides it here).
 (fn [db [_ server profile {:keys [user]}]]
   (cond-> (assoc-in db [:github server] (or user false))
     (and (not user) (not (js/localStorage.getItem (github-later-key server profile))) (not (:github-prompt db)))
     (assoc :github-prompt {:server server :profile profile}))))

(rf/reg-event-db :ops/github-prompt (fn [db [_ v]] (assoc db :github-prompt v)))
(rf/reg-sub :github-prompt (fn [db] (:github-prompt db)))

(rf/reg-fx
 :ops/put
 ;; {:url :method :body :on-done (fn [ok error])}
 (fn [{:keys [url method body on-done]}]
   (-> (call (or method "PUT") url body)
       (.then #(on-done % nil))
       (.catch #(on-done nil (.-message %))))))

;; ---- attachments ----

(defn- new-uuid [] (if (exists? js/crypto.randomUUID) (js/crypto.randomUUID) (str (random-uuid))))

(defn- upload-target
  "[server chat-folder-id] for files added to a draft: the open chat's, or a new chat's own id."
  [db draft-key]
  (if (vector? draft-key)
    draft-key
    [(events/default-server db) (or (:new-upload-id db) "")]))

(rf/reg-event-fx
 :ops/attach
 ;; Files added to the composer (picker, paste, drop); files: a JS array of File.
 (fn [{:keys [db]} [_ draft-key files]]
   (let [db (if (and (not (vector? draft-key)) (not (:new-upload-id db))) (assoc db :new-upload-id (new-uuid)) db)
         [server chat] (upload-target db draft-key)
         items (vec (for [f files] {:id (new-uuid) :name (.-name ^js f) :size (.-size ^js f) :busy true}))]
     {:db (update-in db [:attach draft-key] (fnil into []) items)
      :ops/read-files {:draft-key draft-key :server server :chat chat
                       :pairs (mapv vector items files)}})))

(rf/reg-fx
 :ops/read-files
 (fn [{:keys [draft-key server chat pairs]}]
   (doseq [[{:keys [id]} ^js f] pairs]
     (-> (media/read-media f)
         (.then (fn [m]
                  (if m
                    (rf/dispatch [:ops/attached draft-key id (assoc m :kind "media")])
                    (if (> (.-size f) (* 100 1024 1024))
                      (rf/dispatch [:ops/attached draft-key id {:error "Over 100 MB: too big to send."}])
                      (let [xhr (js/XMLHttpRequest.)]
                        (.open xhr "POST" (api-url server (str "chats/" chat "/uploads?name=" (js/encodeURIComponent (.-name f)))))
                        (.setRequestHeader xhr "content-type" (or (not-empty (.-type f)) "application/octet-stream"))
                        (set! (.. xhr -upload -onprogress)
                              (fn [e] (when (.-lengthComputable e)
                                        (rf/dispatch [:ops/attached draft-key id {:progress (/ (.-loaded e) (.-total e))}]))))
                        (set! (.-onload xhr)
                              (fn []
                                (let [b (try (protocol/read-json (.-responseText xhr)) (catch :default _ nil))]
                                  (rf/dispatch [:ops/attached draft-key id
                                                (if (and (<= 200 (.-status xhr) 299) (:path b))
                                                  {:kind "file" :file b :busy false}
                                                  {:error (or (:error b) (str "Upload failed (HTTP " (.-status xhr) ")"))})]))))
                        (set! (.-onerror xhr) #(rf/dispatch [:ops/attached draft-key id {:error "Upload failed: no connection."}]))
                        (.send xhr f))))))))))

(rf/reg-event-db
 :ops/attached
 (fn [db [_ draft-key id m]]
   (update-in db [:attach draft-key]
              (fn [items] (mapv #(if (= id (:id %))
                                   (cond-> (merge % m) (or (:kind m) (:error m)) (assoc :busy false))
                                   %)
                                items)))))

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

(defmethod events/on-frame "older" [db server {:keys [chat messages more before]}]
  (let [k [server chat]]
    {:db (update-in db [:chats k]
                    (fn [st]
                      (if (= before (:id (first (:messages st))))
                        (assoc st :messages (into (vec messages) (:messages st)) :more more :loading-older false)
                        (assoc st :loading-older false))))}))
