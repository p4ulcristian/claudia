(ns claudia.slave.runner
  "One profile's runner: HTTP + WebSocket on a unix socket (the front passes the page's calls on),
  the chats, and the SQLite store. Restarting it keeps running chats: the host holds them."
  (:require ["node:http" :as http]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            ["ws" :refer [WebSocketServer]]
            [clojure.string :as str]
            [claudia.shared.protocol :as p]
            [claudia.slave.chats :as chats]
            [claudia.slave.hostlink :as hostlink]
            [claudia.slave.http :as h]
            [claudia.slave.pictures :as pictures]
            [claudia.slave.render :as render]
            [claudia.slave.secret :as secret]
            [claudia.slave.store :as store]))

(def ^:private env (.-env js/process))
(def socket-path (or (.-RUNNER_SOCKET env) (str (or (.-XDG_RUNTIME_DIR env) "/tmp") "/claudia-beta-runner.sock")))
(def host-socket (or (.-HOST_SOCKET env) (str (or (.-XDG_RUNTIME_DIR env) "/tmp") "/claudia-beta-host.sock")))
(def data-dir (or (.-RUNNER_DATA env) (path/join (os/homedir) ".local/share/claudia-beta")))
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

(defn- on-frame [c {:keys [type chat] :as f}]
  (let [who (:profile c)]
    (cond
      (not (contains? p/page->runner type)) (send! c {:type "error" :text (str "Unknown frame " type ".")})
      (and (:read-only c) (not (contains? p/read-only-ok type))) (send! c {:type "error" :chat chat :text "Read only."})
      (= type "hello") (do (reset! (:hello c) true) (send! c {:type "chats" :chats (chats/rows)}))
      (= type "new") (let [id (chats/new-chat! (str (:text f)) (:folder f))]
                       (swap! (:open c) conj id)
                       (send! c {:type "created" :chat id :id (:id f)}))
      (not (known? chat)) (send! c {:type "error" :chat chat :text "No such chat."})
      :else
      (case type
        "open" (-> (chats/snapshot chat)
                   (.then (fn [frame]
                            (send! c frame)
                            (send! c {:type "pictures" :chat chat :pictures (pictures/listing chat)})
                            (swap! (:open c) conj chat))))
        "picture" (send! c {:type "picture" :chat chat :turn (:turn f) :scene (pictures/one chat (str (:turn f)))})
        "close" (swap! (:open c) disj chat)
        "send" (when (seq (:text f)) (chats/send-text! chat (:text f)))
        "stop" (chats/stop! chat)
        "answer" (chats/answer! chat (:ask f) (:answers f))
        "secret" (chats/secret! chat (:ask f) (:sealed f) who)
        "deny" (chats/deny! chat (:ask f) who)
        "rename" (do (store/update! chat {:title (str/trim (str (:title f)))}) (chats-changed))
        "archive" (do (store/update! chat {:archived (boolean (:archived f))}) (chats-changed))
        "seen" (do (store/update! chat {:seen (js/Date.now)}) (chats-changed))))))

(defn- caller [^js req]
  {:profile (or (aget (.-headers req) "x-claudia-profile") profile)
   :read-only (= "1" (aget (.-headers req) "x-claudia-read-only"))})

(defn- secret-read
  "The vault read: op read on this profile's account, sealed to the asking runner's one-time key."
  [req res {:keys [read-only] who :profile}]
  (let [account (.-CLAUDIA_OP_ACCOUNT env)]
    (cond
      read-only (h/json-reply res 403 {:error "Read only."})
      (not (seq account)) (h/json-reply res 503 {:error "No 1Password account set for this profile (CLAUDIA_OP_ACCOUNT)."})
      :else
      (-> (h/read-body req 65536)
          (.then (fn [body]
                   (let [{:keys [ref pubkey request]} (p/read-json body)]
                     (if-not (and (string? ref) (str/starts-with? ref "op://") (string? pubkey))
                       (h/json-reply res 400 {:error "ref must start with op:// and pubkey is needed."})
                       (do (js/console.log (str "vault read " ref " for request " request " by " who))
                           (-> (secret/op-read account ref)
                               (.then (fn [value] (h/json-reply res 200 {:sealed (secret/seal value pubkey)})))))))))
          (.catch (fn [e] (h/json-reply res 502 {:error (str (or (.-message e) e))})))))))

(defn- query-param [^js req k]
  (.get (.-searchParams (js/URL. (.-url req) "http://runner")) k))

(defn- picture-png
  "GET /api/chats/:id/picture.png?turn=: the live picture (or the turn's) as a PNG."
  [^js req ^js res id]
  (let [turn (query-param req "turn")
        scene (if (seq turn) (pictures/one id turn) (chats/current-scene id))]
    (if-not (seq (:blocks scene))
      (h/json-reply res 404 {:error "No such picture."})
      (-> (render/screenshot scene (:color (store/chat id)))
          (.then (fn [{:keys [^js png error]}]
                   (if png
                     (do (.writeHead res 200 #js {"content-type" "image/png" "cache-control" "no-store"
                                                  "content-length" (.-length png)})
                         (.end res png))
                     (h/json-reply res 503 {:error error}))))))))

(defn- on-request [^js req ^js res]
  (let [who (caller req)
        url (first (str/split (.-url req) #"\?"))
        [_ chat-id sub] (re-matches #"/api/chats/([^/]+)/(.+)" url)
        chat-id (some-> chat-id js/decodeURIComponent)]
    (cond
      (and chat-id (= "GET" (.-method req)) (not (known? chat-id)))
      (h/json-reply res 404 {:error "No such chat."})
      (and chat-id (= sub "picture.png") (= "GET" (.-method req)))
      (picture-png req res chat-id)
      (and (= url "/api/me") (= "GET" (.-method req)))
      (h/json-reply res 200 {:server nil :profile (:profile who) :viewing nil :readOnly (:read-only who)
                             :version h/version :commit h/commit})
      (and (= url "/api/secret/read") (= "POST" (.-method req)))
      (secret-read req res who)
      :else (h/json-reply res 404 {:error "Not found."}))))

(defn- on-socket [^js ws ^js req]
  (let [c (merge (caller req) {:ws ws :hello (atom false) :open (atom #{})})]
    (swap! conns conj c)
    (.on ws "message" (fn [data]
                        (try (on-frame c (p/read-json (str data)))
                             (catch :default e (send! c {:type "error" :text (str "Bad frame: " (.-message e))})))))
    (.on ws "close" #(swap! conns disj c))
    (.on ws "error" (fn [_]))))

(defn main [& _]
  (store/open! data-dir)
  (reset! chats/out {:emit emit :changed chats-changed})
  (-> secret/ready
      (.then
       (fn []
         (hostlink/connect! host-socket chats/on-host-event chats/on-host-lost)
         (let [wss (WebSocketServer. #js {:noServer true :maxPayload (* 16 1024 1024)})
               server (http/createServer on-request)]
           (.on wss "connection" on-socket)
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
                      (println "claudia runner" profile "on" socket-path "(host" host-socket ", cwd" chats/cwd ")"))))))))
