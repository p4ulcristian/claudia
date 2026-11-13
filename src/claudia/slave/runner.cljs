(ns claudia.slave.runner
  "One profile's runner: HTTP + WebSocket on a unix socket (the front passes the page's calls on),
  the chats, and the SQLite store. Restarting it keeps running chats: the host holds them."
  (:require ["node:http" :as http]
            [claudia.node.http :as io]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            ["ws" :refer [WebSocketServer]]
            [clojure.string :as str]
            [claudia.shared.protocol :as p]
            [claudia.slave.api :as api]
            [claudia.slave.changes :as changes]
            [claudia.slave.chats :as chats]
            [claudia.slave.codeforms :as codeforms]
            [claudia.slave.history :as history]
            [claudia.slave.search :as search]
            [claudia.slave.uploads :as uploads]
            [claudia.slave.shown :as shown]
            [claudia.slave.video :as video]
            [claudia.slave.hostlink :as hostlink]
            [claudia.slave.http :as h]
            [claudia.slave.meta :as meta]
            [claudia.slave.pictures :as pictures]
            [claudia.slave.render :as render]
            [claudia.slave.secret :as secret]
            [claudia.slave.store :as store]))

(def ^:private env (.-env js/process))
(def socket-path (or (.-RUNNER_SOCKET env) (str (or (.-XDG_RUNTIME_DIR env) "/tmp") "/claudia-runner.sock")))
(def host-socket (or (.-HOST_SOCKET env) (str (or (.-XDG_RUNTIME_DIR env) "/tmp") "/claudia-host.sock")))
(def data-dir (or (.-RUNNER_DATA env) (path/join (os/homedir) ".local/share/claudia")))
(def profile (or (.-RUNNER_PROFILE env) ""))

;; One per page socket: {:ws :profile :read-only :hello (atom) :open (atom #{chat ids})}
(defonce conns (atom #{}))

(defn- send! [{:keys [^js ws]} frame]
  (when (= 1 (.-readyState ws))
    (.send ws (p/write-json frame))))

(defn- emit [chat frame]
  (doseq [c @conns :when (contains? @(:open c) chat)] (send! c frame)))

(defn- chats-changed []
  (let [frame {:type "chats" :chats (chats/rows)}]
    (doseq [c @conns :when @(:hello c)] (send! c frame))))

(defn- known? [id] (and (string? id) (some? (store/chat id))))

(def ^:private page-size "Messages per page of a chat's history." 120)

(defn- sent-images
  "A frame's photos and video frames as the host sends them: [{media_type data}] (at most 40)."
  [f]
  (vec (for [i (take 40 (when (sequential? (:images f)) (:images f)))
             :when (and (re-matches #"image/(jpeg|png|gif|webp)" (str (:media_type i))) (string? (:data i)))]
         {:media_type (:media_type i) :data (:data i)})))

(defn frame-gate
  "What to do with a page frame of type frame-type before its chat: :unknown (no such frame),
  :read-only (a read-only caller may not send it), :hello, :new, :no-chat (its chat is not known)
  or :chat."
  [frame-type read-only chat-known]
  (cond
    (not (contains? p/page->runner frame-type)) :unknown
    (and read-only (not (contains? p/read-only-ok frame-type))) :read-only
    (= frame-type "hello") :hello
    (= frame-type "new") :new
    (not chat-known) :no-chat
    :else :chat))

(defn newest-page
  "A chat's messages as its first page: the newest page-size and whether there are more."
  [ms]
  (let [n (count ms)]
    {:messages (subvec ms (max 0 (- n page-size))) :more (> n page-size)}))

(defn older-page
  "The page of messages before the message with id before (the start when it is not there)."
  [ms before]
  (let [end (or (first (keep-indexed #(when (= before (:id %2)) %1) ms)) 0)]
    {:messages (subvec ms (max 0 (- end page-size)) end) :more (> end page-size)}))

(defn- wake-with!
  "The user wrote text (and images) to chat: an archived chat comes back, a passive one wakes."
  [chat text images]
  (store/update! chat {:archived false})
  (chats/wake! chat)
  (if images (chats/send-text! chat text images) (chats/send-text! chat text))
  (chats-changed))

(defmulti ^:private on-chat-frame
  "A page frame for a known chat: c the page socket, f the frame, chat its id."
  (fn [_c f _chat] (:type f)))

;; A chat opens with its newest page; older pages come with `older` as the page scrolls up.
(defmethod on-chat-frame "open" [c _ chat]
  (.then (chats/snapshot chat)
         (fn [frame]
           (send! c (merge frame (newest-page (:messages frame))))
           (send! c {:type "pictures" :chat chat :pictures (pictures/listing chat)})
           (swap! (:open c) conj chat))))

(defmethod on-chat-frame "picture" [c f chat]
  (send! c {:type "picture" :chat chat :turn (:turn f) :scene (pictures/one chat (str (:turn f)))}))

(defmethod on-chat-frame "older" [c f chat]
  (.then (chats/snapshot chat)
         (fn [frame]
           (send! c (merge {:type "older" :chat chat :before (:before f)}
                           (older-page (:messages frame) (:before f)))))))

(defmethod on-chat-frame "close" [c _ chat] (swap! (:open c) disj chat))

(defmethod on-chat-frame "send" [_ f chat]
  (let [files (uploads/own-files (:files f))
        images (sent-images f)]
    (when (or (seq (:text f)) (seq files) (seq images))
      (wake-with! chat (uploads/with-files (str (:text f)) files) images))))

(defmethod on-chat-frame "stop" [_ _ chat] (chats/stop! chat))
(defmethod on-chat-frame "answer" [_ f chat] (chats/answer! chat (:ask f) (:answers f)))
(defmethod on-chat-frame "secret" [c f chat] (chats/secret! chat (:ask f) (:sealed f) (:profile c)))
(defmethod on-chat-frame "deny" [c f chat] (chats/deny! chat (:ask f) (:profile c)))
(defmethod on-chat-frame "rename" [_ f chat] (chats/rename! chat (:title f)))
(defmethod on-chat-frame "archive" [_ f chat]
  (store/update! chat {:archived (boolean (:archived f))})
  (chats/session-follows-row! chat)
  (chats-changed))
(defmethod on-chat-frame "folder" [_ f chat] (chats/move-folder! chat (:folder f)))
(defmethod on-chat-frame "seen" [_ _ chat] (store/update! chat {:seen (js/Date.now)}) (chats-changed))
(defmethod on-chat-frame "stop_task" [_ f chat] (chats/stop-task! chat (str (:task f))))
(defmethod on-chat-frame "settings" [_ f chat] (chats/set-settings! chat (select-keys f [:model :effort :plan])))

(defmethod on-chat-frame "agent" [c f chat]
  (.then (chats/agent-detail chat (str (:agent f)))
         (fn [d] (send! c (merge {:type "agent" :chat chat :agent (:agent f)} d)))))

(defmethod on-chat-frame "agent_git" [c f chat]
  (.then (chats/agent-git chat (str (:agent f)) (str (:rev f)))
         (fn [d] (send! c (merge {:type "agent_git" :chat chat :agent (:agent f) :rev (:rev f)} d)))))

(defmethod on-chat-frame "diff" [c f chat]
  (send! c (merge {:type "diff" :chat chat :change (:change f)}
                  (or (chats/change-texts chat (:change f)) {:error "No such change."}))))

;; The editor form: the user's typing saved into the picture, and its Send.
(defmethod on-chat-frame "code_edit" [_ f chat]
  (some->> (codeforms/code-edit (chats/scene-of chat) f) (chats/set-scene! chat)))

(defmethod on-chat-frame "code_send" [_ f chat]
  (when-let [{:keys [scene text]} (codeforms/code-send (chats/scene-of chat) f)]
    (chats/set-scene! chat scene)
    (when (seq text) (wake-with! chat text nil))))

(defn- on-frame [c {frame-type :type :keys [chat] :as f}]
  (case (frame-gate frame-type (:read-only c) (known? chat))
    :unknown (send! c {:type "error" :text (str "Unknown frame " frame-type ".")})
    :read-only (send! c {:type "error" :chat chat :text "Read only."})
    :hello (do (reset! (:hello c) true)
               (send! c {:type "chats" :chats (chats/rows)})
               (send! c (meta/frame)))
    :new (let [id (chats/new-chat! (uploads/with-files (str (:text f)) (uploads/own-files (:files f)))
                                   (:folder f) (sent-images f) (select-keys f [:model :effort :plan :color]))]
           (swap! (:open c) conj id)
           (send! c {:type "created" :chat id :id (:id f)}))
    :no-chat (send! c {:type "error" :chat chat :text "No such chat."})
    :chat (on-chat-frame c f chat)))

(defn- caller [^js req]
  {:profile (or (aget (.-headers req) "x-claudia-profile") profile)
   :read-only (= "1" (aget (.-headers req) "x-claudia-read-only"))})

(defn- secret-read
  "The vault read: op read on this profile's account, sealed to the asking runner's one-time key."
  [req res {:keys [read-only] who :profile}]
  (let [account (.-CLAUDIA_OP_ACCOUNT env)]
    (cond
      read-only (io/send-json res 403 {:error "Read only."})
      (not (seq account)) (io/send-json res 503 {:error "No 1Password account set for this profile (CLAUDIA_OP_ACCOUNT)."})
      :else
      (-> (io/read-text req 65536)
          (.then (fn [body]
                   (let [{:keys [ref pubkey request]} (p/read-json body)]
                     (if-not (and (string? ref) (str/starts-with? ref "op://") (string? pubkey))
                       (io/send-json res 400 {:error "ref must start with op:// and pubkey is needed."})
                       (do (js/console.log (str "vault read " ref " for request " request " by " who))
                           (-> (secret/op-read account ref)
                               (.then (fn [value] (io/send-json res 200 {:sealed (secret/seal value pubkey)})))))))))
          (.catch (fn [e] (io/send-json res 502 {:error (str (or (.-message e) e))})))))))

(defn- query-param [^js req k]
  (.get (.-searchParams (js/URL. (.-url req) "http://runner")) k))

(defn- picture-png
  "GET /api/chats/:id/picture.png?turn=: the live picture (or the turn's) as a PNG."
  [^js req ^js res id]
  (let [turn (query-param req "turn")
        scene (if (seq turn) (pictures/one id turn) (chats/current-scene id))]
    (if-not (seq (:blocks scene))
      (io/send-json res 404 {:error "No such picture."})
      (-> (render/screenshot scene (:color (store/chat id)))
          (.then (fn [{:keys [^js png error]}]
                   (if png
                     (do (.writeHead res 200 #js {"content-type" "image/png" "cache-control" "no-store"
                                                  "content-length" (.-length png)})
                         (.end res png))
                     (io/send-json res 503 {:error error}))))))))

(defn request-op
  "Which of the runner's own calls a request is (url without its query; chat-id and sub from
  /api/chats/:id/<sub>; chat-known: that chat is in the store): :no-chat, :picture-png,
  :me, :secret-read, or nil (the forms' and the API's calls, then not found)."
  [method url chat-id sub chat-known]
  (cond
    (and chat-id (= "GET" method) (not chat-known)) :no-chat
    (and chat-id (= sub "picture.png") (= "GET" method)) :picture-png
    (and (= url "/api/me") (= "GET" method)) :me
    (and (= url "/api/secret/read") (= "POST" method)) :secret-read))

(defn chat-call
  "A request path (no query) of /api/chats/:id/<sub> as [id sub] (id decoded), else [nil nil]."
  [url]
  (let [[_ chat-id sub] (re-matches #"/api/chats/([^/]+)/(.+)" url)]
    [(some-> chat-id js/decodeURIComponent) sub]))

(defn- on-request [^js req ^js res]
  (let [who (caller req)
        url (first (str/split (.-url req) #"\?"))
        [chat-id sub] (chat-call url)]
    (case (request-op (.-method req) url chat-id sub (and chat-id (known? chat-id)))
      :no-chat (io/send-json res 404 {:error "No such chat."})
      :picture-png (picture-png req res chat-id)
      :me (io/send-json res 200 {:server nil :profile (:profile who) :profiles [profile] :viewing nil :readOnly (:read-only who)
                                 :version h/version :commit h/commit})
      :secret-read (secret-read req res who)
      nil (cond
            (video/handle req res url who) nil
            (codeforms/handle req res url who {:scene-of chats/scene-of :work chats/cwd
                                               :tools-changed! chats/prefs-changed!}) nil
            (api/handle req res who) nil
            :else (io/send-json res 404 {:error "Not found."})))))

(defn- on-socket [^js ws ^js req]
  (let [c (merge (caller req) {:ws ws :hello (atom false) :open (atom #{})})]
    (swap! conns conj c)
    (.on ws "message" (fn [data]
                        (try (on-frame c (p/read-json (str data)))
                             (catch :default e (send! c {:type "error" :text (str "Bad frame: " (.-message e))})))))
    (.on ws "close" #(swap! conns disj c))
    (.on ws "error" (fn [_]))))

(defn- listen!
  "The runner's HTTP and WebSocket server on its unix socket."
  []
  (let [wss (WebSocketServer. #js {:noServer true :maxPayload (* 64 1024 1024)})
        server (http/createServer on-request)]
    (.on wss "connection" on-socket)
    ;; Uploads take up to 100 MB: no 5-minute cap on a request.
    (set! (.-requestTimeout server) 0)
    (.on server "upgrade"
         (fn [req sock head]
           (if (str/starts-with? (.-url req) "/ws")
             (.handleUpgrade wss req sock head #(.emit wss "connection" % req))
             (.destroy sock))))
    (try (fs/unlinkSync socket-path) (catch :default _))
    (.listen server socket-path
             (fn []
               ;; Group may connect: a front running as another user of the group.
               (fs/chmodSync socket-path (js/parseInt "660" 8))
               (println "claudia runner" profile "on" socket-path "(host" host-socket ", cwd" chats/cwd ")")))))

(defn- start!
  "Once libsodium is ready: the host link, the chats, the 5-minute probe and the server."
  []
  (hostlink/connect! host-socket chats/on-host-event chats/on-host-lost)
  (chats/init!)
  ;; Models, commands and plan usage: every 5 minutes while a page is connected (runner.ts).
  (js/setInterval #(when (seq @conns) (chats/probe-soon! 0)) (* 5 60000))
  (listen!))

(defn main [& _]
  (store/open! data-dir)
  (uploads/set-root! data-dir)
  (video/set-root! data-dir)
  (shown/set-root! data-dir)
  (search/open! data-dir)
  ;; The search index catches up with every chat in the background, newest first.
  (search/warm! (for [r (store/chats)] [(history/session-file chats/cwd (:id r)) (:id r)]))
  (reset! chats/out {:emit emit :changed chats-changed})
  (reset! changes/data-dir data-dir)
  (reset! meta/on-change (fn [_] (let [frame (meta/frame)]
                                   (doseq [c @conns :when @(:hello c)] (send! c frame)))))
  (.then secret/ready start!))
