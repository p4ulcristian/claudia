(ns claudia.slave.rootfront
  "The front's side of root (README decisions 52 and 79). The root helper asks here, on a socket only
  root can open (FRONT_ROOT_SOCKET); the person's taps arrive here as hub calls (POST /api/root/answer,
  /api/root/switch, /api/root/challenge), so the proof of a tap lives with user claudia, which no
  agent user reaches. Turning root on and Allow need the owner's passkey: a grant the key broker
  signs, checked here with its pinned key, so neither a stolen login nor a hacked hub gives root. A
  runner (the agent user's) only names the chat for a key, says whether a chat is there (a tap on
  one it lacks keeps nothing) and shows the card; it decides nothing."
  (:require ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:http" :as http]
            [claudia.node.crypto :as c]
            [claudia.node.env :as env]
            [claudia.node.http :as io]
            [claudia.root.rules :as rules]
            [claudia.shared.activity :as activity]
            [claudia.shared.protocol :as p]
            [claudia.shared.root-grant :as rg]
            [claudia.slave.rootgate :as gate]))

(def ^:private ask-ms (* 10 60 1000))
(def ^:private runner-ms 5000)

;; {:switches {"<profile>/<chat>" true} :pending {nonce {:profile :chat :argv :cwd :done}}
;;  :grants {nonce {:profile :chat :action :ask :expires}}} (grants: open passkey nonces, decision 79)
(defonce ^:private state (atom {:switches {} :pending {} :grants {}}))

(defn- state-file [] (env/env "FRONT_ROOT_STATE"))

(defn- save-switches! [switches]
  (when-let [f (state-file)]
    (fs/writeFileSync (str f ".new") (p/write-json switches) #js {:mode (js/parseInt "600" 8)})
    (fs/renameSync (str f ".new") f)))

(defn- load-switches!
  "The kept switches, without those of profiles that have no runner here (the file then rewritten)."
  [profiles]
  (when-let [f (state-file)]
    (let [v (try (p/read-json (fs/readFileSync f "utf8")) (catch :default _ nil))
          kept (if (map? v) (update-keys v #(subs (str %) 1)) {})
          here (gate/switches-here kept profiles)]
      (swap! state assoc :switches here)
      (when (not= kept here)
        (js/console.log (str "root switches: dropped " (- (count kept) (count here)) " of profiles not here"))
        (save-switches! here)))))

(defn- runner-of [profiles profile] (some #(when (= profile (:profile %)) %) profiles))

(defn- runner-call
  "Promise of a runner's answer {:status :body} to POST path; status 502 when it did not answer."
  [profiles profile path body]
  (-> (io/socket-json (:socket (runner-of profiles profile)) path body runner-ms)
      (.catch (fn [e] {:status 502 :body {:error (str "The runner did not answer: " (.-message e))}}))))

(defn- to-runner
  "Promise of a runner's answer body to POST path (only for its display, or naming a chat)."
  [profiles profile path body]
  (.then (runner-call profiles profile path body)
         (fn [{:keys [status body]}] (if (= 200 status) body {:error (or (:error body) (str "HTTP " status))}))))

;; ---- the root helper's ask ----

(defn- settle!
  "Ends the open ask under nonce (allow or the why of a no; by: the email of the person who answered,
  nil when nobody did): its card goes, the helper hears."
  [profiles nonce allow why by]
  (when-let [{:keys [profile chat done timer]} (get-in @state [:pending nonce])]
    (js/clearTimeout timer)
    (swap! state update :pending dissoc nonce)
    (to-runner profiles profile "/api/root/done" {:chat chat :ask nonce})
    (done (gate/verdict-of chat allow why by))))

(defn- wait-for-person
  "Promise of the verdict for argv in cwd of profile's chat: the card shown by the runner, the
  person's Allow through this front, 10 minutes at most. req: the helper's call (it leaving ends it)."
  [profiles profile chat {:keys [argv cwd]} ^js req]
  (js/Promise.
   (fn [done]
     (let [nonce (.toString (crypto/randomBytes 16) "hex")
           timer (js/setTimeout #(settle! profiles nonce false "Nobody answered in 10 minutes." nil) ask-ms)]
       (swap! state assoc-in [:pending nonce] {:profile profile :chat chat :argv argv :cwd cwd :done done :timer timer})
       (.on (.-socket req) "close" #(settle! profiles nonce false "The command went away." nil))
       (.then (to-runner profiles profile "/api/root/card"
                         {:chat chat :ask nonce :argv argv :cwd cwd :expires (+ (js/Date.now) ask-ms)})
              #(when (:error %) (settle! profiles nonce false (:error %) nil)))))))

(defn- helper-ask
  "The root helper's {agentUser chatKey argv cwd}: the runner names the chat; the front's own switch
  allows at once, else the person's Allow through the front."
  [profiles {:keys [agentUser chatKey] :as ask} req]
  (let [profile (rules/profile-of agentUser)]
    (.then (to-runner profiles profile "/api/root/chat" {:chatKey chatKey})
           (fn [{:keys [chat error]}]
             (cond (not (string? chat)) {:allow false :why (or error "No chat for this key.")}
                   (gate/switch-on? (:switches @state) profile chat)
                   {:allow true :via "switch" :chat chat :by (gate/switch-by (:switches @state) profile chat)}
                   :else (wait-for-person profiles profile chat ask req))))))

(defn- on-helper [profiles ^js req ^js res]
  (-> (io/read-json req 262144)
      (.then (fn [ask]
               (if-let [why (or (when-not (= "/root/ask" (.-url req)) "Not found.")
                                (gate/ask-problem ask profiles))]
                 {:allow false :why why}
                 (helper-ask profiles ask req))))
      (.then #(io/send-json res 200 %))
      (.catch #(io/send-json res 400 {:allow false :why (.-message %)}))))

(defn listen!
  "The root helper's socket (FRONT_ROOT_SOCKET; unset: none): user claudia's, mode 0600, so only
  root (and this front) can open it."
  [profiles-atom]
  (load-switches! @profiles-atom)
  (when-let [sock-path (env/env "FRONT_ROOT_SOCKET")]
    (let [server (http/createServer #(on-helper @profiles-atom %1 %2))]
      (try (fs/unlinkSync sock-path) (catch :default _))
      (set! (.-requestTimeout server) 0)
      (.listen server sock-path #(do (fs/chmodSync sock-path (js/parseInt "600" 8))
                                     (println "claudia front: root asks on" sock-path))))))

;; ---- the person's taps (hub calls, the caller's route known) ----

(defn tap-line
  "The front's activity line for the person's tap (README decision 59): route the caller's, chat the
  chat, decision what the tap did, argv the command it answered (nil for a switch)."
  [route chat decision argv]
  (activity/line (activity/root-entry {:chat chat :profile (get-in route [:target :profile])
                                       :agent-user (get-in route [:target :user]) :argv argv
                                       :decision decision :by (get-in route [:own :email])})))

;; ---- passkey grants (README decision 79) ----

(defn- tap-of [route chat action ask]
  {:profile (get-in route [:target :profile]) :chat chat :action action :ask ask})

(defn- take-grant!
  "The open nonce entry grant g names, taken out (each is used once)."
  [g]
  (let [nonce (:nonce g)
        entry (when (string? nonce) (get-in @state [:grants nonce]))]
    (when entry (swap! state update :grants dissoc nonce))
    entry))

(defn- grant-problem
  "Why the body's {grant signature} does not let this tap (action, ask) through, or nil: the key
  broker's signature under the pinned key (FRONT_BROKER_GRANT_KEY), the front's own nonce, the same
  chat and command, and the passkey of the chat's owner (the profiles file's email)."
  [route {:keys [chat grant signature]} action ask]
  (let [pinned (env/env "FRONT_BROKER_GRANT_KEY")]
    (cond
      (not (seq pinned)) "This server pins no key broker (FRONT_BROKER_GRANT_KEY): root needs a passkey through it."
      (not (and (string? grant) (string? signature))) "Root needs your passkey: the approval is missing."
      (not (c/ed25519-valid? pinned grant signature)) "That approval is not signed by the key broker."
      :else (let [g (try (p/read-json grant) (catch :default _ nil))]
              (rg/problem g (take-grant! g) (tap-of route chat action ask)
                          (get-in route [:target :email]) (js/Date.now))))))

(defn- challenge!
  "A one-time nonce for the passkey approval of root on chat (action \"on\") or of one Allow
  (\"allow\", ask: the open card's nonce), 5 minutes."
  [_profiles route {:keys [chat action ask]}]
  (let [pending (get-in @state [:pending ask])]
    (cond
      (not (contains? rg/actions action)) [400 {:error "action must be on or allow."}]
      (and (= "allow" action) (gate/answer-problem pending {:chat chat :ask ask :allow false} route))
      [403 {:error (gate/answer-problem pending {:chat chat :ask ask :allow false} route)}]
      :else (let [nonce (.toString (crypto/randomBytes 16) "hex")
                  tap (tap-of route chat action (when (= "allow" action) ask))]
              (swap! state update :grants rg/open-nonce nonce tap (js/Date.now))
              [200 {:nonce nonce :profile (:profile tap)}]))))

(defn- answer! [profiles route {:keys [ask allow] :as a}]
  (let [pending (get-in @state [:pending ask])]
    (if-let [why (or (gate/answer-problem pending a route)
                     (when (true? allow) (grant-problem route a "allow" ask)))]
      [403 {:error why}]
      (do (js/console.log (tap-line route (:chat pending) (if (true? allow) "allowed" "denied") (:argv pending)))
          (settle! profiles ask (true? allow) "The person said no." (get-in route [:own :email]))
          [200 {:ok true}]))))

(defn- switch-to!
  "Keeps profile's chat switched on or off (by the owner's email), tells its runner, logs the tap."
  [profiles route chat on]
  (let [profile (get-in route [:target :profile])
        switches (:switches (swap! state update :switches gate/with-switch profile chat (true? on)
                                   (get-in route [:own :email])))]
    (save-switches! switches)
    (js/console.log (tap-line route chat (if (true? on) "switch on" "switch off") nil))
    (to-runner profiles profile "/api/root/shown" {:chat chat :root (true? on)})
    [200 {:ok true :root (true? on)}]))

(defn- switch!
  "The root switch: on needs the owner's passkey grant; off needs nothing."
  [profiles route {:keys [chat on] :as body}]
  (if-let [why (when (true? on) (grant-problem route body "on" nil))]
    [403 {:error why}]
    (switch-to! profiles route chat on)))

(defn- tap!
  "Promise of [status body] for the person's tap: the owner's only, on a chat the target profile's
  runner says it has (else nothing is kept or settled), then act."
  [profiles route {:keys [chat] :as body} act]
  (if-let [problem (gate/tap-problem route chat)]
    (js/Promise.resolve problem)
    (.then (runner-call profiles (get-in route [:target :profile]) "/api/root/known" {:chat chat})
           #(or (gate/chat-problem %) (act profiles route body)))))

(defn handle
  "A hub call to /api/root/answer, /api/root/switch or /api/root/challenge (route: the caller's,
  from the hub's headers)."
  [profiles route url ^js req ^js res]
  (-> (io/read-json req 262144)
      (.then (fn [body]
               (case url
                 "/api/root/answer" (tap! profiles route body answer!)
                 "/api/root/switch" (tap! profiles route body switch!)
                 "/api/root/challenge" (tap! profiles route body challenge!)
                 [404 {:error "Not found."}])))
      (.then (fn [[status body]] (io/send-json res status body)))
      (.catch #(io/send-json res 500 {:error (.-message %)}))))
