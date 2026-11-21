(ns claudia.web.events
  (:require [re-frame.core :as rf]
            [clojure.string :as str]
            [claudia.shared.colours :as colours]
            [claudia.shared.files :as files]
            [claudia.web.api :as api]
            [claudia.web.db :as db]
            [claudia.web.fx :as fx]
            [claudia.web.here :as here]
            [claudia.web.updates :as updates]
            [claudia.web.views.pure.agents :as agents-pure]
            [claudia.web.views.pure.chat :as chat-pure]
            [claudia.web.views.pure.dictated :as dictated]
            [claudia.web.views.pure.mic :as mic]
            [claudia.web.views.pure.sidebar :as sidebar-pure]
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

(defn echo-text
  "A sent text as compared with its echo: trimmed, the blank around each line break gone (a reminder
  the runner took out of a merged message leaves blank lines)."
  [t]
  (str/replace (str/trim (or t "")) #"\s*\n\s*" "\n"))

(defn echo-run
  "[start end) of the oldest run of outbox texts whose echo is text: one text, or several in a row
  joined by line breaks (Claude Code takes messages queued together in as one); nil when none."
  [outbox text]
  (let [want (echo-text text)
        n (count outbox)]
    (first (for [i (range n)
                 j (range (inc i) (inc n))
                 :when (= want (echo-text (str/join "\n" (subvec outbox i j))))]
             [i j]))))

(defn drop-echo
  "The runner echoed a user message: take the oldest matching text (or run of texts) off the outbox."
  [outbox text]
  (let [outbox (vec outbox)]
    (if-let [[i j] (echo-run outbox text)]
      (into (subvec outbox 0 i) (subvec outbox j))
      outbox)))

(defn drop-echoes
  "The outbox without the texts a chat's user messages echoed."
  [outbox msgs]
  (reduce drop-echo (vec outbox) (keep #(when (= "user" (:role %)) (:text %)) msgs)))

(defn server-names
  "The names of the servers the hub lets the person use, in its order."
  [db]
  (mapv :name (get-in db [:me :servers])))

(defn all-server-names
  "server-names, then the servers the person has no profile on, where they may look at others'
  chats, read only (the hub's viewServers)."
  [db]
  (into (server-names db) (map :name) (get-in db [:me :viewServers])))

(defn default-server
  "Where a new chat starts: the last used server while it is still offered, else the first."
  [db]
  (let [names (server-names db)]
    (if (some #{(:new-server db)} names) (:new-server db) (first names))))

(defn viewing-other?
  "Another person's chats are shown (the title menu's \"<person>'s chats\")."
  [db]
  (some? (:view db)))

(defn writable?
  "The viewed person gave the caller a write tick that holds on this server (its /api/me says)."
  [db server]
  (boolean (some #{(:view db)} (get-in db [:server-me server :writable]))))

(defn read-only?
  "Viewing another person's chats without write access to them on server (default: the open chat's
  server, else where a new chat would start)."
  ([db] (read-only? db (or (first (:open db)) (default-server db))))
  ([db server] (and (viewing-other? db) (not (writable? db server)))))

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

(def back-after-ms
  "A connection away at least this long shows \"Connected\" when it is back (a shorter blip never
  showed its \"Reconnecting\" either)."
  1500)

(def back-shown-ms "How long \"Connected\" shows." 2000)

(defn with-status
  "A server's entry with its socket's new status at now: when it goes down, since when (:down-since);
  when it opens after being down back-after-ms or longer, :back (\"Connected\" shows)."
  [{:keys [down-since] :as entry} status now]
  (cond
    (= :open status) (-> entry (assoc :status :open :back (boolean (and down-since (>= (- now down-since) back-after-ms))))
                         (dissoc :down-since))
    (= :open (:status entry)) (assoc entry :status status :down-since now :back false)
    :else (assoc entry :status status)))

(rf/reg-event-db
 :ws/status
 (fn [db [_ server status]] (update-in db [:servers server] with-status status (js/Date.now))))

(rf/reg-event-db
 :ws/back-shown
 (fn [db [_ server]] (assoc-in db [:servers server :back] false)))

(rf/reg-event-db
 :net/online
 (fn [db [_ online?]] (assoc db :offline (not online?))))

(defn hello-frames
  "The frames a socket to server that just opened sends, in order (plain frames: they all go on that
  one socket): hello, when this page was touched (here, nil: never; README decision 21), and after
  a reconnect the open chat's open again (full state, live events)."
  [[s c] server here]
  (cond-> [{:type "hello"}]
    here (conj here)
    (and (= s server) c) (conj {:type "open" :chat c})))

(defn opened-fx
  "What a socket to server opening at now does: its status, hello (and the open chat) on it, a
  version check (a hub on a new version dropped every socket: is it new? claudia.web.updates), and
  \"Connected\" hidden again after a while when it shows."
  [db server now]
  (let [entry (with-status (get-in db [:servers server]) :open now)]
    {:db (assoc-in db [:servers server] entry)
     :ws/hello [server (hello-frames (:open db) server (here/frame))]
     :fx (cond-> [[:dispatch [:version/check]]]
           (:back entry) (conj [:dispatch-later {:ms back-shown-ms :dispatch [:ws/back-shown server]}]))}))

(rf/reg-event-fx
 :ws/opened
 (fn [{:keys [db]} [_ server]] (opened-fx db server (js/Date.now))))

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

(defn all-rows
  "Every chat row of every server the person's lists came from, each with its :server."
  [db]
  (for [[s {:keys [rows]}] (:servers db), r (vals rows)] (assoc r :server s)))

(defn fresh-colour
  "A new chat's colour: one no open chat on any server wears (README decision 26), the most
  different from those worn, never the colour of the chat started last while another is free."
  [rows]
  (colours/most-different rows nil))

(defn with-fresh-colour
  "db with a colour for the new chat, unless it has one no open chat wears (picked by hand, or picked
  before another server's list came)."
  [db]
  (let [rows (all-rows db)
        c (get-in db [:new-settings :color])]
    (cond-> db
      (or (not c) ((colours/taken rows nil) c))
      (assoc-in [:new-settings :color] (fresh-colour rows)))))

(defn recolour-plan
  "The open chats that wear a colour an older open chat wears, each with a free colour: [[k colour]…],
  picked one after the other so no two get the same."
  [rows]
  (first (reduce (fn [[plan rs] {:keys [id server]}]
                   (let [c (colours/most-different rs id)]
                     [(conj plan [[server id] c]) (map #(if (and (= id (:id %)) (= server (:server %))) (assoc % :color c) %) rs)]))
                 [[] rows]
                 (colours/duplicates rows))))

(defn lists-complete?
  "Every server of the person's own sent its list and is connected (not another person's view)."
  [db]
  (and (not (viewing-other? db))
       (every? (fn [s] (let [e (get-in db [:servers s])] (and (:rows e) (= :open (:status e))))) (server-names db))))

(defmethod on-frame "chats" [db server {:keys [chats]}]
  ;; The new chat on screen takes a free colour once a list is here; two open chats in one colour get
  ;; a free one for the newer once every list is here (README decision 26).
  (let [db' (cond-> (assoc-in db [:servers server :rows]
                              (into {} (map (juxt :id identity)) (sidebar-pure/with-clock-times chats (js/Date.now))))
              (nil? (:open db)) with-fresh-colour)
        plan (when (lists-complete? db') (recolour-plan (all-rows db')))]
    {:db db'
     :fx (into [[:dispatch [:chat/check-seen]]] (for [[k c] plan] [:dispatch [:ops/color k c]]))}))

(defn chat-of-frame
  "A chat's state from its `chat` frame, loaded."
  [frame]
  (-> (select-keys frame [:title :folder :scene :running :asks :more
                          :agents :settings :model :effort :commands :context])
      (assoc :messages (vec (:messages frame)) :loaded true)))

(defmethod on-frame "chat" [db server {:keys [chat] :as f}]
  (let [k [server chat]
        now (js/Date.now)]
    {:db (-> db
             (assoc-in [:chats k] (chat-of-frame f))
             (update-in [:outbox k] drop-echoes (:messages f))
             (update-in [:turn-since k] running-since (:running f) now))
     :fx [[:dispatch [:chat/check-seen]]]}))

(def carried-keys
  "What a new chat takes over from the last chat: the model, effort, prompt profile, folder (README
  decision 74: it starts where the last chat works, on its computer), voice, bell and sound (not the
  colour: each chat gets its own; not the subscription: a new chat runs on its person's last pick,
  README decision 57)."
  [:model :effort :prompt :folder :voice :bell :sound])

(defn carried-picks
  "The picks a new chat starts with after the chat k: that chat's settings, switches and folder (its
  own, else its computer's work folder: README decision 74), else (no chat open) the last new chat's
  picks, without its colour."
  [db k]
  (if-let [{:keys [settings folder]} (when k (get-in db [:chats k]))]
    (let [row (get-in db [:servers (first k) :rows (second k)])]
      (into {} (filter (comp some? val))
            (merge (select-keys settings [:model :effort :prompt]) (select-keys row [:voice :bell :sound])
                   {:folder (or (not-empty (:folder row)) (not-empty folder) (get-in db [:work-folders (first k)]))})))
    (select-keys (:new-settings db) carried-keys)))

(defn created-db
  "A new chat the server made (as chat) from the pending one sent as id: the text waiting for it
  moves to the real chat, which opens if the pending one was open; its picks stay for the next new
  chat, all but the colour."
  [db server chat id text]
  (let [k [server chat]]
    (cond-> (-> db
                (update :pending-new dissoc id)
                (assoc-in [:outbox k] [text])
                (update :outbox dissoc [server id])
                (update :new-settings select-keys carried-keys))
      (= (:open db) [server id]) (assoc :open k))))

(defn created-frames
  "Open the new chat (what was picked before it was made went with `new`)."
  [server chat]
  [[server {:type "open" :chat chat}]])

(defn new-picks
  "What a new chat's header picked, as `new` carries it: model, effort, plan, prompt profile, folder,
  colour, voice, bell and sound, those set."
  [new-settings]
  (into {} (filter (comp some? val))
        (update (select-keys new-settings [:model :effort :plan :prompt :folder :color :voice :bell :sound]) :folder not-empty)))

(defmethod on-frame "created" [db server {:keys [chat id]}]
  (if-let [{:keys [text group picks]} (get-in db [:pending-new id])]
    (let [was-open? (= (:open db) [server id])]
      (cond-> {:db (created-db db server chat id text)
               :store ["claudia.newPicks" (clj->js (select-keys (:new-settings db) carried-keys))]
               :history/replace (if was-open? [server chat] (fx/parse-path js/location.pathname))
               :ws/send-all (created-frames server chat)}
        ;; Started from a group's +: it goes into that group (claudia.web.order).
        group (assoc :fx [[:dispatch [:order/created (str server "/" chat) group (dissoc picks :color)]]])))
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

(defmethod on-frame "turn" [db server {:keys [chat running stopped]}]
  (let [k [server chat]]
    {:db (-> db
             (update-in [:chats k] chat-after-turn running stopped)
             (update-in [:turn-since k] running-since running (js/Date.now)))
     :fx [[:dispatch [:chat/check-seen]]]}))

(defn speak-item
  "A voice message from server (README decisions 21 and 93) as a reply to read: {:id :k :voice :text
  :colour-name :msg}, the name of the chat's colour (colour, its id) to say first unless the chat is
  open when it is read (talk/spoken), msg the message's own id; nil when nothing in it can be said."
  [server {:keys [chat id voice text ask]} colour]
  (when-let [said (talk/speakable (if ask (talk/ask-text ask) text))]
    (cond-> {:id (str server "/" chat "/" id) :k [server chat] :voice voice :text said :msg id}
      (colours/name-of colour) (assoc :colour-name (colours/name-of colour)))))

(defn played-item
  "The message a `voices` frame says to play now ({:chat :id}, README decision 94) as a reply to
  read, or nil."
  [db server messages {:keys [chat id]}]
  (when-let [m (some #(when (and (= chat (:chat %)) (= id (:id %))) %) messages)]
    (speak-item server m (get-in db [:servers server :rows chat :color]))))

(defmethod on-frame "voices" [db server {:keys [messages play]}]
  ;; Every waiting voice message of this server's chats (README decision 93), sent whenever they
  ;; change; play: this page was touched last, so it says the new one as soon as it can.
  (let [msgs (if (sequential? messages) (vec messages) [])
        item (when play (played-item db server msgs play))]
    (cond-> {:db (assoc-in db [:voice-msgs server] msgs)}
      item (assoc :fx [[:speech/enqueue item]]))))

(defn without-heard
  "Server's messages without chat's ones with these ids (heard or swiped away)."
  [msgs chat ids]
  (let [gone (set ids)]
    (vec (remove #(and (= chat (:chat %)) (gone (:id %))) msgs))))

(defn voice-heard-fx
  "Chat k's messages ids heard to their end or swiped away: gone here at once, and on every device
  once its runner says so."
  [db [server chat] ids]
  (when (seq ids)
    {:db (update-in db [:voice-msgs server] without-heard chat ids)
     :ws/send [server {:type "heard" :chat chat :ids (vec ids)}]}))

(rf/reg-event-fx :voice/heard (fn [{:keys [db]} [_ k ids]] (voice-heard-fx db k ids)))

(defn chat-msg-ids
  "The ids of chat k's ([server chat]) waiting voice messages in db."
  [db [server chat]]
  (->> (get-in db [:voice-msgs server]) (filter #(= chat (:chat %))) (mapv :id)))

(defn voice-done-fx
  "Chat k's waiting voice messages are done with (README decision 107): all marked heard, here at
  once and on every device through its runner, and its line stops if it is being read."
  [db k]
  (assoc (or (voice-heard-fx db k (chat-msg-ids db k)) {}) :fx [[:speech/drop k]]))

;; A chat opened (its words read on the screen), stopped by a hold, or skipped from its circle.
(rf/reg-event-fx :voice/done (fn [{:keys [db]} [_ k]] (voice-done-fx db k)))

(defn chat-items
  "Chat k's waiting voice messages in db as replies to read, oldest first."
  [db [server chat]]
  (let [colour (get-in db [:servers server :rows chat :color])]
    (->> (get-in db [:voice-msgs server])
         (filter #(= chat (:chat %)))
         (sort-by :at)
         (keep #(speak-item server % colour))
         vec)))

(rf/reg-event-fx
 :voice/play
 ;; A tap on chat k's circle (or on the mic, for the chat waiting longest): its messages play.
 (fn [{:keys [db]} [_ k]]
   {:speech/play-chat (chat-items db k)}))

(rf/reg-sub :voice-msgs (fn [db _] (:voice-msgs db)))

(defn remove-ask
  "The asks without the one with this id."
  [asks id]
  (vec (remove #(= (:id %) id) asks)))

(defmethod on-frame "ask" [db server {:keys [chat ask]}]
  {:db (update-in db [:chats [server chat] :asks] #(conj (remove-ask % (:id ask)) ask))})

(defmethod on-frame "keys_ask" [db server {:keys [chat ask]}]
  ;; list_secrets (README decision 77): answered at once with this person's keys, no card.
  {:db db :keys/answer {:server server :chat chat :ask ask}})

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
  "The chat screen, with the archived chats closed and the list back to the chats: a chat picked
  from the archived ones leaves them behind (Search and Settings are views of the right half and
  stay, README decision 75)."
  [db]
  (assoc db :screen :chat :archived? false :project-filter nil))

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
      ;; Opened, its replies are read on the screen: its voice messages are done (README decision 107).
      :fx (cond-> [[:dispatch [:chat/check-seen]] [:dispatch [:voice/done k]]]
            history? (conj [:history/push k]))})))

(defn new-chat
  "The effects of opening a new chat on server with picks (the chat open now closes)."
  [db picks server]
  (let [[ps pc] (:open db)]
    {:db (-> (assoc (on-chat-screen db) :open nil :new-settings picks :new-server server :new-group nil)
             with-fresh-colour)
     :store ["claudia.newPicks" (clj->js (select-keys picks carried-keys))]
     :ws/send-all (if pc [[ps {:type "close" :chat pc}]] [])
     :history/push nil}))

(rf/reg-event-fx
 :nav/new
 ;; The new chat starts where the chat open now works, with its picks (carried-picks).
 (fn [{:keys [db]} _]
   (let [[ps pc :as k] (:open db)
         server (if (and pc (not (contains? (:pending-new db) pc))) ps (:new-server db))]
     (new-chat db (carried-picks db k) server))))

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
 ;; An emptied draft forgets what the mic put into it.
 (fn [db [_ k text]]
   (cond-> (assoc-in db [:drafts k] text)
     (str/blank? text) (update :dictated dissoc k))))

(defn draft-key
  "The draft the composer writes now: the open chat's, or :new (no chat open, or one on its way)."
  [{:keys [open pending-new]}]
  (if (and open (not (contains? pending-new (second open)))) open :new))

(defn draft-with
  "A draft with text put under what it holds (a blank line between)."
  [draft text]
  (if (str/blank? draft) text (str (str/trimr draft) "\n\n" text)))

(rf/reg-event-db
 :draft/add
 ;; Text put in the composer from elsewhere, not sent (System's Ask a chat, README decision 96); on
 ;; a phone the chat comes up to show it.
 (fn [db [_ text]]
   (let [k (draft-key db)]
     (cond-> (assoc-in db [:drafts k] (draft-with (get-in db [:drafts k]) text))
       (db/phone?) (assoc :screen :chat)))))

(rf/reg-event-fx
 :chat/switches
 ;; The chat's voice, bell and/or sound (m: {:voice :bell :sound}, those set), kept with the chat; k nil: the new
 ;; chat's picks, which go with `new`. A voice turned Off is that chat's silence at once.
 (fn [{:keys [db]} [_ [s c :as k] m]]
   (cond-> {:db (switches/with-switches db k m)}
     k (assoc :ws/send [s (merge {:type "settings" :chat c} m)])
     (and k (= "" (:voice m))) (assoc :fx [[:speech/drop k]]))))

(rf/reg-event-db
 :speech/now
 ;; The reply being read aloud ({:id :k :voice}, nil: none): the mic shows its chat.
 (fn [db [_ item]] (if item (assoc db :speaking item) (dissoc db :speaking))))

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

(rf/reg-event-fx
 :chat/root
 ;; The chat's root switch (README decision 52), on until its owner turns it off: kept by the
 ;; server's front (never the runner), which has the runner show it to every page with the list.
 ;; On carries the key broker's grant for the owner's passkey (README decision 79); off needs none.
 (fn [{:keys [db]} [_ [s c :as k] on? grant]]
   {:db (switches/with-switches db k {:root on?})
    :api/call {:method "POST" :url (api/url s "root/switch" nil)
               :body (merge {:chat c :on on?} (when on? (select-keys grant [:grant :signature])))
               :on-fail [:root/switch-failed k (not on?)]}}))

(rf/reg-event-db
 :root/switch-failed
 (fn [db [_ k was message]]
   (assoc (switches/with-switches db k {:root was}) :error (str "Root switch: " message) :error-from nil)))

(rf/reg-event-fx
 :root/answer
 ;; Allow or Deny on a "Run as root" card goes to the server's front, with the command and folder
 ;; the card showed: the front checks them against what it asked (README decision 52). Allow
 ;; carries the key broker's grant for the owner's passkey (decision 79); Deny needs none.
 (fn [{:keys [db]} [_ [s c] ask allow? grant]]
   {:db (assoc-in db [:ask-state (:id ask)] {:busy true})
    :api/call {:method "POST" :url (api/url s "root/answer" nil)
               :body (merge {:chat c :ask (:id ask) :allow allow? :argv (:argv ask) :cwd (:cwd ask)}
                            (when allow? (select-keys grant [:grant :signature])))
               :on-fail [:root/answer-failed (:id ask)]}}))

(rf/reg-event-db
 :root/answer-failed
 (fn [db [_ id message]] (assoc-in db [:ask-state id] {:error message})))

(rf/reg-event-db :root/ask (fn [db [_ k]] (assoc db :root-ask k)))
(rf/reg-event-db :root/keep (fn [db _] (dissoc db :root-ask)))

(rf/reg-event-fx
 :root/confirm
 ;; The confirm card's passkey gave grant (claudia.web.views.confirm/root-ask).
 (fn [{:keys [db]} [_ grant]]
   (cond-> {:db (dissoc db :root-ask)}
     (:root-ask db) (assoc :fx [[:dispatch [:chat/root (:root-ask db) true grant]]]))))

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
  "Nothing is sent: nothing to send, an attachment still on its way, another profile's chats without
  write access on server, or a new chat still on its way (the text waits in the composer until
  `created`)."
  [db server chat attach {:keys [text extra]}]
  (boolean (or (and (str/blank? text) (empty? (:images extra)))
               (some :busy attach)
               (read-only? db server)
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
             (assoc-in [:pending-new id] (cond-> {:server server :text text}
                                           (:new-group db) (assoc :group (:new-group db)
                                                                  :picks (assoc (new-picks (:new-settings db)) :server server))))
             (dissoc :new-group)
             (assoc :open [server id])
             (assoc-in [:outbox [server id]] [text])
             (assoc-in [:drafts :new] "")
             (update :attach dissoc :new)
             (dissoc :new-upload-id)
             (assoc :new-server server))
     :store ["claudia.newServer" server]
     :ws/send [server (merge {:type "new" :id id :text sent} (new-picks (:new-settings db)) extra)]}))

(defn folder-first
  "A new chat's first message with no folder picked (README decision 65): nothing is sent, the draft
  stays, and the right half shows Files for the new chat; its Select picks the folder and then sends
  (claudia.web.folders, :send? true)."
  [_db _server]
  {:fx [[:dispatch [:folders/open {:chat nil :send? true}]]]})

(defn send-for
  "A send of typed from the composer k ([server chat], or :new): into that chat, or a new chat.
  now: it and every message queued before it go in at once, the running turn stopped for them
  (README decision 83). viewing: the turn of the earlier picture the person looks at in that chat
  (none on the live one), so Claude hears which step (README decision 102)."
  [db k typed & [now]]
  (let [chat (when (vector? k) (second k))
        server (if chat (first k) (default-server db))
        attach (get-in db [:attach (if chat k :new)])
        viewing (when chat (get-in db [:viewing k]))
        out (cond-> (assoc-in (outgoing attach typed) [:extra :msg] (new-uuid))
              (and now chat) (assoc-in [:extra :now] true)
              viewing (assoc-in [:extra :viewing] viewing))]
    (cond
      (hold-send? db server chat attach out) {}
      chat (send-in-chat db k out)
      (str/blank? (get-in db [:new-settings :folder])) (folder-first db server)
      :else (send-new-chat db (default-server db) (new-uuid) out))))

(defn with-dictation-checked
  "A send's effects fx (nothing sent: empty) from composer k, whose text went out: the mic's pieces
  in that draft are forgotten."
  [fx db k]
  (if (or (empty? fx) (empty? (get-in db [:dictated k])))
    fx
    (update fx :db #(update (or % db) :dictated dissoc k))))

(rf/reg-event-fx
 :composer/send
 (fn [{:keys [db]} [_ typed]]
   (let [[_ chat :as k] (:open db)
         k (if chat k :new)]
     (with-dictation-checked (send-for db k typed) db k))))

(defn heard-fx
  "What the mic's words (heard: {:text :raw}, text cleaned up, raw as heard) do in the composer k
  it was held in ([server chat] or :new), wherever the person is now (hearing takes a moment; they
  may have opened another chat): the clean text joins that composer's draft, kept as a piece
  (pure/dictated); let go to send (or :now: the running turn stops first), the draft with it
  answers that chat's question or is sent."
  [db k heard dir]
  (let [text (mic/with-heard (get-in db [:drafts k] "") (:text heard))
        with-text (-> db (assoc-in [:drafts k] text) (update-in [:dictated k] dictated/added heard))
        ask (when (vector? k) (first (get-in db [:chats k :asks])))
        answers (when ask (chat-pure/typed-answer ask (str/trim text) (get-in db [:attach k])))]
    (cond
      (not (#{:send :now} dir)) {:db with-text}
      answers {:db (-> db (assoc-in [:drafts k] "") (update :dictated dissoc k))
               :fx [[:dispatch [:ask/answer k (:id ask) answers]]]}
      :else (let [sent (send-for with-text k text (= dir :now))]
              (if (seq sent) (with-dictation-checked sent with-text k) {:db with-text})))))

(rf/reg-event-db
 :dictated/swap
 ;; The composer's chip: the last dictated words in draft k swapped, clean for raw or back.
 (fn [db [_ k]]
   (let [draft (get-in db [:drafts k]) pieces (get-in db [:dictated k])]
     (if-let [piece (dictated/swappable pieces draft)]
       (let [[d ps] (dictated/swapped draft pieces piece)]
         (-> db (assoc-in [:drafts k] d) (assoc-in [:dictated k] ps)))
       db))))

(rf/reg-event-fx
 :mic/heard
 ;; A "now" press in a chat whose helpers still run is sent without stopping the turn (it would
 ;; stop them too): it arrives in the running turn like a plain send.
 (fn [{:keys [db]} [_ k heard dir]]
   (heard-fx db k heard (agents-pure/mic-dir dir (when (vector? k) (get-in db [:chats k :agents]))))))

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
 :chat/unqueue
 ;; A queued message's ×: the runner asks Claude Code to drop it (README decision 83).
 (fn [_ [_ [s c] msg-id]]
   {:ws/send [s {:type "unqueue" :chat c :msg msg-id}]}))

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
 ;; A chat's working folder (the Folders modal's Select; blank: none).
 (fn [_ [_ [s c] folder]]
   {:ws/send [s {:type "folder" :chat c :folder (str/trim (str folder))}]}))

(defn taken-by-others
  "The colours open chats other than k ([server id]; nil: the new chat) wear on every server: its
  palette shows them struck through (README decision 26)."
  [db [s c]]
  (colours/taken (remove #(and (= s (:server %)) (= c (:id %))) (all-rows db)) nil))

(defn back-colour
  "The colour a chat coming back from the archive needs (k: [server id]): a free one when an open
  chat wears its own (README decision 26), else nil."
  [db [s c]]
  (let [rows (all-rows db)
        mine (get-in db [:servers s :rows c :color])
        others (remove #(and (= s (:server %)) (= c (:id %))) rows)]
    (when ((colours/taken others nil) mine)
      (colours/most-different others nil))))

(rf/reg-event-fx
 :chat/archive
 ;; Archiving the open chat moves on to the next one in the list (or a new chat); one coming back
 ;; whose colour an open chat wears takes a free one.
 (fn [{:keys [db]} [_ [s c :as k] archived]]
   (let [following (next-open (:servers db) k)
         recolour (when-not archived (back-colour db k))]
     (cond-> {:ws/send [s {:type "archive" :chat c :archived archived}]}
       (and archived (= k (:open db)))
       (assoc :fx [(if following [:dispatch [:nav/open following]] [:dispatch [:nav/new]])])
       recolour (assoc :fx [[:dispatch [:ops/color k recolour]]])))))

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
 :ask/sealed
 ;; Allow with passkey: the card's tap got the value sealed by the key broker (README decision 66).
 (fn [_ [_ [s c] ask sealed]]
   {:secret/send {:server s :chat c :ask ask :sealed sealed}}))

(rf/reg-event-fx
 :ask/paste
 (fn [{:keys [db]} [_ [s c] ask text]]
   {:db (assoc-in db [:ask-state (:id ask)] {:busy true})
    :secret/seal {:server s :chat c :ask ask :text text}}))
