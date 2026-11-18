(ns claudia.slave.rootfront
  "The front's side of root (README decision 52). The root helper asks here, on a socket only root
  can open (FRONT_ROOT_SOCKET); the person's taps arrive here as hub calls (POST /api/root/answer,
  /api/root/switch), so the proof of a tap lives with user claudia, which no agent user reaches. A
  runner (the agent user's) only names the chat for a key, says whether a chat is there (a tap on
  one it lacks keeps nothing) and shows the card; it decides nothing."
  (:require ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:http" :as http]
            [claudia.node.env :as env]
            [claudia.node.http :as io]
            [claudia.root.rules :as rules]
            [claudia.shared.protocol :as p]
            [claudia.slave.rootgate :as gate]))

(def ^:private ask-ms (* 10 60 1000))
(def ^:private runner-ms 5000)

;; {:switches {"<profile>/<chat>" true} :pending {nonce {:profile :chat :argv :cwd :done}}}
(defonce ^:private state (atom {:switches {} :pending {}}))

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
  "Ends the open ask under nonce (allow or the why of a no): its card goes, the helper hears."
  [profiles nonce allow why]
  (when-let [{:keys [profile chat done timer]} (get-in @state [:pending nonce])]
    (js/clearTimeout timer)
    (swap! state update :pending dissoc nonce)
    (to-runner profiles profile "/api/root/done" {:chat chat :ask nonce})
    (done (gate/verdict-of chat allow why))))

(defn- wait-for-person
  "Promise of the verdict for argv in cwd of profile's chat: the card shown by the runner, the
  person's Allow through this front, 10 minutes at most. req: the helper's call (it leaving ends it)."
  [profiles profile chat {:keys [argv cwd]} ^js req]
  (js/Promise.
   (fn [done]
     (let [nonce (.toString (crypto/randomBytes 16) "hex")
           timer (js/setTimeout #(settle! profiles nonce false "Nobody answered in 10 minutes.") ask-ms)]
       (swap! state assoc-in [:pending nonce] {:profile profile :chat chat :argv argv :cwd cwd :done done :timer timer})
       (.on (.-socket req) "close" #(settle! profiles nonce false "The command went away."))
       (.then (to-runner profiles profile "/api/root/card"
                         {:chat chat :ask nonce :argv argv :cwd cwd :expires (+ (js/Date.now) ask-ms)})
              #(when (:error %) (settle! profiles nonce false (:error %))))))))

(defn- helper-ask
  "The root helper's {agentUser chatKey argv cwd}: the runner names the chat; the front's own switch
  allows at once, else the person's Allow through the front."
  [profiles {:keys [agentUser chatKey] :as ask} req]
  (let [profile (rules/profile-of agentUser)]
    (.then (to-runner profiles profile "/api/root/chat" {:chatKey chatKey})
           (fn [{:keys [chat error]}]
             (cond (not (string? chat)) {:allow false :why (or error "No chat for this key.")}
                   (gate/switch-on? (:switches @state) profile chat) {:allow true :via "switch" :chat chat}
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

(defn- answer! [profiles route {:keys [ask allow] :as a}]
  (let [pending (get-in @state [:pending ask])]
    (if-let [why (gate/answer-problem pending a route)]
      [403 {:error why}]
      (do (js/console.log (str "root ask " ask " of " (:chat pending) ": " (if (true? allow) "allowed" "denied")
                               " by " (get-in route [:own :profile])))
          (settle! profiles ask (true? allow) "The person said no.")
          [200 {:ok true}]))))

(defn- switch! [profiles route {:keys [chat on]}]
  (let [profile (get-in route [:target :profile])
        switches (:switches (swap! state update :switches gate/with-switch profile chat (true? on)))]
    (save-switches! switches)
    (js/console.log (str "root switch of " profile "/" chat " " (if (true? on) "on" "off")))
    (to-runner profiles profile "/api/root/shown" {:chat chat :root (true? on)})
    [200 {:ok true :root (true? on)}]))

(defn- tap!
  "Promise of [status body] for the person's tap: the owner's only, on a chat the target profile's
  runner says it has (else nothing is kept or settled), then act."
  [profiles route {:keys [chat] :as body} act]
  (if-let [problem (gate/tap-problem route chat)]
    (js/Promise.resolve problem)
    (.then (runner-call profiles (get-in route [:target :profile]) "/api/root/known" {:chat chat})
           #(or (gate/chat-problem %) (act profiles route body)))))

(defn handle
  "A hub call to /api/root/answer or /api/root/switch (route: the caller's, from the hub's headers)."
  [profiles route url ^js req ^js res]
  (-> (io/read-json req 262144)
      (.then (fn [body]
               (case url
                 "/api/root/answer" (tap! profiles route body answer!)
                 "/api/root/switch" (tap! profiles route body switch!)
                 [404 {:error "Not found."}])))
      (.then (fn [[status body]] (io/send-json res status body)))
      (.catch #(io/send-json res 500 {:error (.-message %)}))))
