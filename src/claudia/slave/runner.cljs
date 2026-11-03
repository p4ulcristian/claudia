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
            [claudia.slave.api :as api]
            [claudia.slave.chats :as chats]
            [claudia.slave.history :as history]
            [claudia.slave.search :as search]
            [claudia.slave.uploads :as uploads]
            [claudia.slave.hostlink :as hostlink]
            [claudia.slave.http :as h]
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

(def ^:private page-size "Messages per page of a chat's history." 120)

(defn- sent-images
  "A frame's photos and video frames as the host sends them: [{media_type data}] (at most 40)."
  [f]
  (vec (for [i (take 40 (when (sequential? (:images f)) (:images f)))
             :when (and (re-matches #"image/(jpeg|png|gif|webp)" (str (:media_type i))) (string? (:data i)))]
         {:media_type (:media_type i) :data (:data i)})))

(defn- on-frame [c {:keys [type chat] :as f}]
  (let [who (:profile c)]
    (cond
      (not (contains? p/page->runner type)) (send! c {:type "error" :text (str "Unknown frame " type ".")})
      (and (:read-only c) (not (contains? p/read-only-ok type))) (send! c {:type "error" :chat chat :text "Read only."})
      (= type "hello") (do (reset! (:hello c) true) (send! c {:type "chats" :chats (chats/rows)}))
      (= type "new") (let [id (chats/new-chat! (uploads/with-files (str (:text f)) (uploads/own-files (:files f)))
                                               (:folder f) (sent-images f))]
                       (swap! (:open c) conj id)
                       (send! c {:type "created" :chat id :id (:id f)}))
      (not (known? chat)) (send! c {:type "error" :chat chat :text "No such chat."})
      :else
      (case type
        ;; A chat opens with its newest page; older pages come with `older` as the page scrolls up.
        "open" (-> (chats/snapshot chat)
                   (.then (fn [frame]
                            (let [ms (:messages frame)
                                  n (count ms)]
                              (send! c (assoc frame :messages (subvec ms (max 0 (- n page-size))) :more (> n page-size))))
                            (swap! (:open c) conj chat))))
        "older" (-> (chats/snapshot chat)
                    (.then (fn [frame]
                             (let [ms (:messages frame)
                                   end (or (first (keep-indexed #(when (= (:before f) (:id %2)) %1) ms)) 0)]
                               (send! c {:type "older" :chat chat :before (:before f)
                                         :messages (subvec ms (max 0 (- end page-size)) end)
                                         :more (> end page-size)})))))
        "close" (swap! (:open c) disj chat)
        "send" (let [files (uploads/own-files (:files f))
                     images (sent-images f)]
                 (when (or (seq (:text f)) (seq files) (seq images))
                   (chats/send-text! chat (uploads/with-files (str (:text f)) files) images)))
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

(defn- on-request [^js req ^js res]
  (let [who (caller req)
        url (first (str/split (.-url req) #"\?"))]
    (cond
      (and (= url "/api/me") (= "GET" (.-method req)))
      (h/json-reply res 200 {:server nil :profile (:profile who) :profiles [profile] :viewing nil :readOnly (:read-only who)
                             :version h/version :commit h/commit})
      (and (= url "/api/secret/read") (= "POST" (.-method req)))
      (secret-read req res who)
      (api/handle req res who) nil
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
  (uploads/set-root! data-dir)
  (search/open! data-dir)
  ;; The search index catches up with every chat in the background, newest first.
  (search/warm! (for [r (store/chats)] [(history/session-file chats/cwd (:id r)) (:id r)]))
  (reset! chats/out {:emit emit :changed chats-changed})
  (-> secret/ready
      (.then
       (fn []
         (hostlink/connect! host-socket chats/on-host-event chats/on-host-lost)
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
                      (println "claudia runner" profile "on" socket-path "(host" host-socket ", cwd" chats/cwd ")"))))))))
