(ns claudia.web.events
  (:require [re-frame.core :as rf]
            [clojure.string :as str]
            [claudia.shared.colours :as colours]
            [claudia.shared.files :as files]
            [claudia.web.db :as db]
            [claudia.web.fx :as fx]
            [claudia.web.updates :as updates]
            [claudia.web.views.pure.chat :as chat-pure]
            [claudia.web.views.pure.mic :as mic]
            [claudia.web.views.pure.switches :as switches]
            [claudia.web.views.pure.talk :as talk]))

;; ---- helpers ----

(defn without-index
  "The vector v without its item at i."
  [v i]
  (into (subvec v 0 i) (subvec v (inc i))))

(defn- msg-index [msgs id]
  (first (keep-indexed (fn [i m] (when (= id (:id m)) i)) msgs)))

(defn upsert-msg
  "A new or changed message by id. gone: a queued message Claude Code dropped. A queued message Claude
  took in joins the chat where it was read: at the end, as things stand now."
  [msgs msg]
  (let [msgs (vec msgs)
        i (msg-index msgs (:id msg))]
    (cond
      (:gone msg) (if i (without-index msgs i) msgs)
      (and i (:queued (nth msgs i)) (not (:queued msg))) (conj (without-index msgs i) msg)
      i (assoc msgs i msg)
      :else (conj msgs msg))))

(defn append-delta
  "A streamed piece of text onto its message (a new streaming assistant message when none yet)."
  [msgs id text]
  (if-let [i (msg-index msgs id)]
    (update-in msgs [i :text] str text)
    (conj (or msgs []) {:id id :role "assistant" :text text :streaming true})))

(defn drop-echo
  "The runner echoed a user message: take the oldest matching text off the outbox."
  [outbox text]
  (let [i (first (keep-indexed (fn [i t] (when (= (str/trim t) (str/trim (or text ""))) i)) outbox))]
    (if i (without-index outbox i) outbox)))

(defn drop-echoes
  "The outbox without the texts a chat's user messages echoed."
  [outbox msgs]
  (reduce drop-echo (vec outbox) (keep #(when (= "user" (:role %)) (:text %)) msgs)))

(defn server-names
  "The names of the servers the hub lets the person use, in its order."
  [db]
  (mapv :name (get-in db [:me :servers])))

(defn default-server
  "Where a new chat starts: the last used server while it is still offered, else the first."
  [db]
  (let [names (server-names db)]
    (if (some #{(:new-server db)} names) (:new-server db) (first names))))

(defn connecting-servers
  "server name -> the state of a socket just opening, for each of names."
  [names]
  (into {} (map (fn [n] [n {:status :connecting :rows {}}])) names))

(defn list-order
  "A chat row's place in the list: in the order the chats were started."
  [row]
  (or (:started row) (:at row) 0))

(defn running-since
  "When a chat's running turn was first seen running: since while it runs on, now when it starts,
  nil when it is not running."
  [since running now]
  (when running (or since now)))

(defn new-uuid []
  (if (exists? js/crypto.randomUUID) (js/crypto.randomUUID) (str (random-uuid))))

;; ---- start ----

(defn new-chat-text
  "/new?text=…: a new chat of your own with the text in the box, not sent (decision 15 of the
  TypeScript Claudia: a link that sent it would let any link drive someone's Claude). nil elsewhere."
  [pathname search]
  (when (= "/new" pathname)
    (or (.get (js/URLSearchParams. search) "text") "")))

(defn start-db
  "The state the page starts with: the chat in the address (open), or a new chat holding new-text."
  [open new-text]
  (cond-> (assoc db/initial :open open :screen (if open :chat :list))
    new-text (-> (assoc :view nil :screen :chat) (assoc-in [:drafts :new] new-text))))

(rf/reg-event-fx
 :init
 (fn [_ _]
   (let [open (fx/parse-path js/location.pathname)
         new-text (new-chat-text js/location.pathname js/location.search)]
     (when new-text (js/history.replaceState nil "" "/"))
     ;; Composer texts kept across a reload for a new version (claudia.web.updates) come back.
     {:db (update (start-db open new-text) :drafts merge (updates/kept-drafts!))
      :fx (if new-text [[:store ["claudia.view" nil]]] [])
      :http/get {:url "/hub/me" :on-ok [:me/loaded] :on-fail [:me/failed]}})))

(rf/reg-event-fx
 :me/loaded
 (fn [{:keys [db]} [_ me]]
   (let [names (mapv :name (:servers me))]
     {:db (-> db
              (assoc :me me :me-error nil)
              ;; The hub's commit the page loaded with: another one later means a new version.
              (update :loaded-commit #(or % (:commit me)))
              (assoc :servers (connecting-servers (if (:view db) [] names))))
      ;; Another profile's chats (a stored view) connect once each server said it has that profile.
      :ws/connect (if (:view db) [] names)
      :fx [[:dispatch [:ops/me-loaded]]]})))

(rf/reg-event-db
 :me/failed
 (fn [db [_ text]] (assoc db :me-error text)))

;; ---- sockets ----

(rf/reg-event-db
 :ws/status
 (fn [db [_ server status]] (assoc-in db [:servers server :status] status)))

(defn hello-frames
  "What a socket that just opened sends: hello, and after a reconnect the open chat's open again
  (full state, live events)."
  [[s c] server]
  (cond-> [[server {:type "hello"}]]
    (and (= s server) c) (conj [server {:type "open" :chat c}])))

(rf/reg-event-fx
 :ws/opened
 (fn [{:keys [db]} [_ server]]
   {:db (assoc-in db [:servers server :status] :open)
    :ws/send-all (hello-frames (:open db) server)
    ;; A hub on a new version dropped every socket: as they open again, is it new? (claudia.web.updates)
    :fx [[:dispatch [:version/check]]]}))

(defmulti on-frame (fn [_db _server frame] (:type frame)))

(defn drop-chat-error
  "db without chat k's error (a chat that moved on: a new turn, Claude writing, a send)."
  [db k]
  (cond-> db (get-in db [:chats k :error]) (update-in [:chats k] dissoc :error)))

(defn without-stale-error
  "db without the top error a server sent (:error-from) once that server sends anything but an
  error: the call it answered is over."
  [db server frame]
  (cond-> db
    (and (= server (:error-from db)) (not= "error" (:type frame))) (dissoc :error :error-from)))

(rf/reg-event-fx
 :ws/frame
 (fn [{:keys [db]} [_ server frame]]
   (let [fx (on-frame db server frame)]
     (cond-> fx (:db fx) (update :db without-stale-error server frame)))))

(defmethod on-frame :default [db _ _] {:db db})

(defmethod on-frame "chats" [db server {:keys [chats]}]
  {:db (assoc-in db [:servers server :rows] (into {} (map (juxt :id identity)) chats))
   :fx [[:dispatch [:chat/check-seen]]]})

(defn chat-of-frame
  "A chat's state from its `chat` frame, loaded."
  [frame]
  (-> (select-keys frame [:title :folder :scene :running :asks :more
                          :agents :changes :settings :model :effort :commands :context])
      (assoc :messages (vec (:messages frame)) :loaded true)))

(defmethod on-frame "chat" [db server {:keys [chat] :as f}]
  (let [k [server chat]
        now (js/Date.now)]
    {:db (-> db
             (assoc-in [:chats k] (chat-of-frame f))
             (update-in [:outbox k] drop-echoes (:messages f))
             (update-in [:turn-since k] running-since (:running f) now))
     :fx [[:dispatch [:chat/check-seen]]]}))

(defn created-db
  "A new chat the server made (as chat) from the pending one sent as id: the text waiting for it
  moves to the real chat, which opens if the pending one was open."
  [db server chat id text]
  (let [k [server chat]]
    (cond-> (-> db
                (update :pending-new dissoc id)
                (assoc-in [:outbox k] [text])
                (update :outbox dissoc [server id])
                (dissoc :new-settings))
      (= (:open db) [server id]) (assoc :open k))))

(defn created-frames
  "Open the new chat (what was picked before it was made went with `new`)."
  [server chat]
  [[server {:type "open" :chat chat}]])

(defn new-picks
  "What a new chat's header picked, as `new` carries it: model, effort, plan, folder, colour, speaker
  and bell, those set."
  [new-settings]
  (into {} (filter (comp some? val))
        (update (select-keys new-settings [:model :effort :plan :folder :color :talk :bell]) :folder not-empty)))

(defn fresh-colour
  "A new chat's colour as soon as it opens: the most different from what that server's chats (rows) wear."
  [rows]
  (colours/most-different rows nil))

(defn with-fresh-colour
  "db with a colour for the new chat on its new server, unless it has one."
  [db]
  (cond-> db
    (not (get-in db [:new-settings :color]))
    (assoc-in [:new-settings :color] (fresh-colour (vals (get-in db [:servers (default-server db) :rows]))))))

(defmethod on-frame "created" [db server {:keys [chat id]}]
  (if-let [{:keys [text]} (get-in db [:pending-new id])]
    (let [was-open? (= (:open db) [server id])]
      {:db (created-db db server chat id text)
       :history/replace (if was-open? [server chat] (fx/parse-path js/location.pathname))
       :ws/send-all (created-frames server chat)})
    {:db db}))

(defmethod on-frame "msg" [db server {:keys [chat msg]}]
  ;; Claude writing again means the chat's error no longer holds.
  (let [k [server chat]]
    {:db (cond-> (update-in db [:chats k :messages] upsert-msg msg)
           (= "user" (:role msg)) (update-in [:outbox k] #(drop-echo (vec %) (:text msg)))
           (not= "user" (:role msg)) (drop-chat-error k))}))

(defmethod on-frame "delta" [db server {:keys [chat id text]}]
  {:db (update-in db [:chats [server chat] :messages] append-delta id text)})

(defmethod on-frame "scene" [db server {:keys [chat scene]}]
  {:db (assoc-in db [:chats [server chat] :scene] scene)})

(defmethod on-frame "status" [db server {:keys [chat running note]}]
  ;; What the running turn is doing beyond "Working" (Thinking, Retrying…, Compacting); none clears it.
  {:db (-> (assoc-in db [:chats [server chat] :running] running)
           (assoc-in [:chats [server chat] :note] note)
           (update-in [:turn-since [server chat]] running-since running (js/Date.now)))})

(defn chat-after-turn
  "A chat's state after a turn frame. A turn the person stopped says \"Stopped.\" until the next one
  starts; a new turn clears the last one's error; a turn's end clears the note and settles every
  streamed message."
  [st running stopped]
  (cond-> (assoc st :running running :stopped (boolean (and (not running) stopped)))
    running (dissoc :error)
    (not running) (-> (dissoc :note)
                      (update :messages (fn [ms] (mapv #(dissoc % :streaming) ms))))))

(defn turn-speech
  "What a turn's end says in talking mode: the final reply, only for the chat on screen and a turn
  that ended by itself (nil otherwise)."
  [db k running stopped]
  (when (and (not running) (not stopped) (talk/speaks? db k))
    (some-> (talk/turn-reply (get-in db [:chats k :messages])) talk/speakable)))

(defmethod on-frame "turn" [db server {:keys [chat running stopped]}]
  (let [k [server chat]
        said (turn-speech db k running stopped)]
    {:db (-> db
             (update-in [:chats k] chat-after-turn running stopped)
             (update-in [:turn-since k] running-since running (js/Date.now)))
     :fx (cond-> [[:dispatch [:chat/check-seen]]]
           said (conj [:speech/say said]))}))

(defn remove-ask
  "The asks without the one with this id."
  [asks id]
  (vec (remove #(= (:id %) id) asks)))

(defmethod on-frame "ask" [db server {:keys [chat ask]}]
  (let [k [server chat]
        said (when (talk/speaks? db k) (some-> (talk/ask-text ask) talk/speakable))]
    (cond-> {:db (update-in db [:chats k :asks] #(conj (remove-ask % (:id ask)) ask))}
      said (assoc :fx [[:speech/say said]]))))

(defmethod on-frame "asked" [db server {:keys [chat ask]}]
  {:db (-> db
           (update-in [:chats [server chat] :asks] remove-ask ask)
           (update :ask-state dissoc ask))})

(defmethod on-frame "error" [db server {:keys [chat text]}]
  {:db (if chat
         (assoc-in db [:chats [server chat] :error] text)
         (assoc db :error (str server ": " text) :error-from server))})

;; ---- navigation ----

(defn on-chat-screen
  "The chat screen, with the drawer, the rail search and Settings closed, and the list back to the
  chats: a chat picked from the archived ones or a search leaves those behind."
  [db]
  (assoc db :screen :chat :sidebar? false :rail-search? false :settings? false
         :archived? false :search nil :project-filter nil))

(defn switch-frames
  "Moving from the open chat prev to k: close prev (when it is a chat), open k's chat (when it has one)."
  [[ps pc :as prev] [server chat :as k]]
  (cond-> []
    (and pc (not= prev k)) (conj [ps {:type "close" :chat pc}])
    chat (conj [server {:type "open" :chat chat}])))

(rf/reg-event-fx
 :nav/open
 (fn [{:keys [db]} [_ k {:keys [history?] :or {history? true}}]]
   (if (= (:open db) k)
     {:db (on-chat-screen db)}
     {:db (-> (on-chat-screen db) (assoc :open k) (assoc-in [:chats k :error] nil))
      :ws/send-all (switch-frames (:open db) k)
      :fx (cond-> [[:dispatch [:chat/check-seen]]]
            history? (conj [:history/push k]))})))

(rf/reg-event-fx
 :nav/new
 (fn [{:keys [db]} _]
   (let [[ps pc] (:open db)]
     {:db (with-fresh-colour (assoc (on-chat-screen db) :open nil))
      :ws/send-all (if pc [[ps {:type "close" :chat pc}]] [])
      :history/push nil})))

(rf/reg-event-fx
 :nav/list
 (fn [{:keys [db]} _]
   {:db (assoc db :screen :list)}))

(rf/reg-event-fx
 :nav/popstate
 (fn [{:keys [db]} _]
   (let [k (fx/parse-path js/location.pathname)]
     (if k
       {:fx [[:dispatch [:nav/open k {:history? false}]]]}
       {:db (assoc db :screen :list)}))))

(rf/reg-event-db
 :nav/screen
 (fn [db [_ screen]] (assoc db :screen screen)))

(defn toggle-sidebar
  "The drawer (desktop) opened or closed (open? nil: switched); from a rail search it only folds
  that back. Closing it leaves Settings, and a rail search's text."
  [db open?]
  (let [v (if (some? open?) open? (and (not (:rail-search? db)) (not (:sidebar? db))))]
    (cond-> (assoc db :sidebar? v :rail-search? false)
      (not v) (assoc :settings? false)
      (and (not v) (:rail-search? db)) (assoc :search nil))))

(rf/reg-event-db
 :sidebar/toggle
 (fn [db [_ open?]] (toggle-sidebar db open?)))

(rf/reg-event-db
 :sidebar/search
 ;; The rail's search: the drawer opens with the search box focused (shell focuses it).
 (fn [db _] (assoc db :sidebar? true :rail-search? true)))

(rf/reg-event-fx
 :new/server
 (fn [{:keys [db]} [_ server]]
   ;; A folder is one server's: picking another computer drops it.
   {:db (-> db (assoc :new-server server) (update :new-settings dissoc :folder)) :store ["claudia.newServer" server]}))

(rf/reg-event-db
 :error/dismiss
 (fn [db [_ k]] (if k (assoc-in db [:chats k :error] nil) (dissoc db :error :error-from))))

;; ---- talking ----

(rf/reg-event-db
 :draft/set
 (fn [db [_ k text]] (assoc-in db [:drafts k] text)))


(rf/reg-event-fx
 :chat/switches
 ;; The chat's speaker and/or bell on or off (m: {:talk :bell}, those set), kept with the chat; k nil:
 ;; the new chat's picks, which go with `new`. The speaker off is silence at once.
 (fn [{:keys [db]} [_ [s c :as k] m]]
   (cond-> {:db (switches/with-switches db k m)}
     k (assoc :ws/send [s (merge {:type "settings" :chat c} m)])
     (false? (:talk m)) (assoc :fx [[:speech/stop]]))))

(rf/reg-event-db
 :mic/failed
 ;; The mic's error goes by itself like every error (the next hold tries again).
 (fn [db [_ message]] (assoc db :error (str "Mic: " message) :error-from nil)))

(defn without-error
  "db without the error text, if it still shows: the chat k's, or the top one (k nil). Another error
  that came since stays."
  [db k text]
  (if k
    (cond-> db (= text (get-in db [:chats k :error])) (update-in [:chats k] dissoc :error))
    (cond-> db (= text (:error db)) (dissoc :error :error-from))))

(rf/reg-event-db
 :error/clear-if
 ;; An error's time is up (views.chat/error-line): it goes, unless another took its place.
 (fn [db [_ k text]] (without-error db k text)))

;; ---- archiving asks first ----

(rf/reg-event-db :archive/ask (fn [db [_ k]] (assoc db :archive-ask k)))
(rf/reg-event-db :archive/keep (fn [db _] (dissoc db :archive-ask)))

(rf/reg-event-fx
 :archive/confirm
 (fn [{:keys [db]} _]
   (cond-> {:db (dissoc db :archive-ask)}
     (:archive-ask db) (assoc :fx [[:dispatch [:chat/archive (:archive-ask db) true]]]))))

(defn outgoing
  "What a send carries, from the draft's attachments (files uploaded, named by <claudia-file> tags
  after the text; images; notes) and the typed text: sent (what goes to the runner, which adds the
  files' tags), text (with the tags: what the outbox holds, as the runner's echo will) and extra."
  [attach typed]
  (let [sent-files (keep :file attach)
        images (vec (mapcat :images attach))
        sent (str/join "\n\n" (filter seq (concat (keep :note attach) [(str/trim (or typed ""))])))]
    {:sent sent
     :text (files/with-files sent sent-files)
     :extra (cond-> {}
              (seq sent-files) (assoc :files (vec sent-files))
              (seq images) (assoc :images images))}))

(defn hold-send?
  "Nothing is sent: nothing to send, an attachment still on its way, another profile's chats, or a
  new chat still on its way (the text waits in the composer until `created`)."
  [db chat attach {:keys [text extra]}]
  (boolean (or (and (str/blank? text) (empty? (:images extra)))
               (some :busy attach)
               (:view db)
               (contains? (:pending-new db) chat))))

(defn send-in-chat
  "A send into the open chat k."
  [db [server chat :as k] {:keys [sent text extra]}]
  {:db (-> db (update-in [:outbox k] (fnil conj []) text) (assoc-in [:drafts k] "") (update :attach dissoc k)
           (drop-chat-error k))
   :ws/send [server (merge {:type "send" :chat chat :text sent} extra)]})

(defn send-new-chat
  "A send that starts a new chat on server, pending as id until `created`; nil without a server."
  [db server id {:keys [sent text extra]}]
  (when server
    {:db (-> db
             (assoc-in [:pending-new id] {:server server :text text})
             (assoc :open [server id])
             (assoc-in [:outbox [server id]] [text])
             (assoc-in [:drafts :new] "")
             (update :attach dissoc :new)
             (dissoc :new-upload-id)
             (assoc :new-server server))
     :store ["claudia.newServer" server]
     :ws/send [server (merge {:type "new" :id id :text sent} (new-picks (:new-settings db)) extra)]}))

(defn send-for
  "A send of typed from the composer k ([server chat], or :new): into that chat, or a new chat."
  [db k typed]
  (let [chat (when (vector? k) (second k))
        attach (get-in db [:attach (if chat k :new)])
        out (outgoing attach typed)]
    (cond
      (hold-send? db chat attach out) {}
      chat (send-in-chat db k out)
      :else (send-new-chat db (default-server db) (new-uuid) out))))

(rf/reg-event-fx
 :composer/send
 (fn [{:keys [db]} [_ typed]]
   (let [[_ chat :as k] (:open db)]
     (send-for db (if chat k :new) typed))))

(defn heard-fx
  "What the mic's text does in the composer k it was held in ([server chat] or :new), wherever the
  person is now (hearing takes a moment; they may have opened another chat): it joins that
  composer's draft; let go to send, the draft with it answers that chat's question or is sent."
  [db k heard dir]
  (let [text (mic/with-heard (get-in db [:drafts k] "") heard)
        with-text (assoc-in db [:drafts k] text)
        ask (when (vector? k) (first (get-in db [:chats k :asks])))
        answers (when ask (chat-pure/typed-answer ask (str/trim text) (get-in db [:attach k])))]
    (cond
      (not= dir :send) {:db with-text}
      answers {:db (assoc-in db [:drafts k] "") :fx [[:dispatch [:ask/answer k (:id ask) answers]]]}
      :else (let [sent (send-for with-text k text)] (if (seq sent) sent {:db with-text})))))

(rf/reg-event-fx
 :mic/heard
 (fn [{:keys [db]} [_ k heard dir]] (heard-fx db k heard dir)))

(defn- on-open-chat [db f]
  (let [[server chat] (:open db)]
    (if (and chat (not (contains? (:pending-new db) chat)))
      (f server chat)
      {})))

(rf/reg-event-fx
 :chat/stop
 (fn [{:keys [db]} _]
   (on-open-chat db (fn [s c] {:ws/send [s {:type "stop" :chat c}]}))))

(rf/reg-event-fx
 :chat/rename
 (fn [_ [_ [s c] title]]
   (when-not (str/blank? title)
     {:ws/send [s {:type "rename" :chat c :title (str/trim title)}]})))

(defn next-open
  "The chat to open once k is archived: the first other chat in the list not archived, or nil."
  [servers k]
  (->> (for [[srv {:keys [rows]}] servers, [id r] rows
             :when (and (not= [srv id] k) (not (:archived r)))]
         (assoc r :k [srv id]))
       (sort-by list-order)
       first
       :k))

(rf/reg-event-fx
 :chat/folder
 ;; Move to project: the chat's working folder (blank: no project).
 (fn [_ [_ [s c] folder]]
   {:ws/send [s {:type "folder" :chat c :folder (str/trim (str folder))}]}))

(rf/reg-event-fx
 :chat/archive
 ;; Archiving the open chat moves on to the next one in the list (or a new chat).
 (fn [{:keys [db]} [_ [s c :as k] archived]]
   (let [following (next-open (:servers db) k)]
     (cond-> {:ws/send [s {:type "archive" :chat c :archived archived}]}
       (and archived (= k (:open db)))
       (assoc :fx [(if following [:dispatch [:nav/open following]] [:dispatch [:nav/new]])])))))

(defn- visible? [] (= "visible" js/document.visibilityState))

(defn seen-due?
  "`seen` is due once the open chat's last turn is on screen: the chat is shown (phone? and on the
  list: not), the page is visible, its turn has ended, its row still says unseen, and `seen` was not
  sent for that row yet."
  [db phone? visible]
  (let [[s c :as k] (:open db)
        row (get-in db [:servers s :rows c])]
    (boolean (and c row (:unseen row) visible
                  (or (not phone?) (not= :list (:screen db)))
                  (not (:view db))
                  (get-in db [:chats k :loaded])
                  (not (get-in db [:chats k :running]))
                  (not= (:at row) (get-in db [:seen-sent k]))))))

(rf/reg-event-fx
 :chat/check-seen
 (fn [{:keys [db]} _]
   (let [[s c :as k] (:open db)]
     (if (seen-due? db (db/phone?) (visible?))
       {:db (assoc-in db [:seen-sent k] (get-in db [:servers s :rows c :at]))
        :ws/send [s {:type "seen" :chat c}]}
       {}))))

;; ---- asks ----

(rf/reg-event-db
 :ask/state
 (fn [db [_ id state]] (assoc-in db [:ask-state id] state)))

(rf/reg-event-fx
 :ask/answer
 (fn [{:keys [db]} [_ [s c] ask-id answers]]
   {:db (assoc-in db [:ask-state ask-id] {:busy true :sent true})
    :ws/send [s {:type "answer" :chat c :ask ask-id :answers answers}]}))

(rf/reg-event-fx
 :ask/deny
 (fn [{:keys [db]} [_ [s c] ask-id]]
   {:db (assoc-in db [:ask-state ask-id] {:busy true :sent true})
    :ws/send [s {:type "deny" :chat c :ask ask-id}]}))

(rf/reg-event-fx
 :ask/allow
 (fn [{:keys [db]} [_ [s c] ask]]
   {:db (assoc-in db [:ask-state (:id ask)] {:busy true})
    :secret/read {:server s :chat c :ask ask}}))

(rf/reg-event-fx
 :ask/paste
 (fn [{:keys [db]} [_ [s c] ask text]]
   {:db (assoc-in db [:ask-state (:id ask)] {:busy true})
    :secret/seal {:server s :chat c :ask ask :text text}}))
