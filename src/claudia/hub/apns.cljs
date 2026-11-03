(ns claudia.hub.apns
  "Apple push (APNs) from the hub: an alert, a badge or a Live Activity push to one phone, over HTTP/2,
  signed with the developer account's APNs key (token auth: an ES256 JWT made by jose, reused for 50
  minutes; Apple refuses one older than an hour and too-frequent new ones). Development builds' tokens
  go to the sandbox, production builds' to Apple's main address. HUB_APNS_DRY_RUN=1 logs instead."
  (:require ["node:http2" :as http2]
            ["node:fs" :as fs]
            ["jose" :as jose]
            [clojure.string :as str]))

(def apple {"development" "https://api.sandbox.push.apple.com"
            "production" "https://api.push.apple.com"})

(def ^:private jwt-ms (* 50 60000))
(def ^:private request-ms 15000)

(defn short-token
  "A device token in a log: its start only."
  [token]
  (str (subs (str token) 0 (min 6 (count (str token)))) "…"))

(defn config
  "{:key-file :key-id :team-id} from the environment, or {:off \"why\"}."
  []
  (let [e (.-env js/process)
        c {:key-file (some-> (.-HUB_APNS_KEY_FILE e) str/trim)
           :key-id (some-> (.-HUB_APNS_KEY_ID e) str/trim)
           :team-id (some-> (.-HUB_APNS_TEAM_ID e) str/trim)}
        missing (for [[k v] [["HUB_APNS_KEY_FILE" (:key-file c)] ["HUB_APNS_KEY_ID" (:key-id c)]
                             ["HUB_APNS_TEAM_ID" (:team-id c)]]
                      :when (str/blank? v)] k)]
    (if (seq missing) {:off (str (str/join ", " missing) " not set")} c)))

(defn- post
  "One request on the session -> Promise of {:status :body} (status 0: no answer)."
  [^js session token topic body auth {:keys [type priority]}]
  (js/Promise.
   (fn [resolve reject]
     (let [^js req (.request session #js {":method" "POST"
                                          ":path" (str "/3/device/" token)
                                          "authorization" (str "bearer " auth)
                                          "apns-topic" topic
                                          "apns-push-type" type
                                          "apns-priority" (str priority)
                                          "content-type" "application/json"})
           status (atom 0)
           text (atom "")]
       (.setEncoding req "utf8")
       (.on req "response" (fn [h] (reset! status (js/Number (aget h ":status")))))
       (.on req "data" (fn [c] (swap! text str c)))
       (.on req "end" #(resolve {:status @status :body @text}))
       (.on req "close" #(resolve {:status @status :body @text}))
       (.on req "error" reject)
       (.setTimeout req request-ms #(.close req))
       (.end req body)))))

(def alert {:type "alert" :priority 10})

(defn create
  "Reads the key now (a bad key file stops push at the start) -> Promise of a send function:
  (send device payload kind) -> Promise of {:status :ok|:gone|:retry|:failed :detail}. device:
  {:token :env :topic}."
  [{:keys [key-file key-id team-id]}]
  (-> (jose/importPKCS8 (fs/readFileSync key-file "utf8") "ES256")
      (.then
       (fn [key]
         (let [jwt (atom {:token nil :at 0})
               sessions (atom {})
               bearer (fn []
                        (let [{:keys [token at]} @jwt]
                          (if (and token (< (- (js/Date.now) at) jwt-ms))
                            (js/Promise.resolve token)
                            (-> (jose/SignJWT. #js {})
                                (.setProtectedHeader #js {:alg "ES256" :kid key-id})
                                (.setIssuer team-id)
                                (.setIssuedAt)
                                (.sign key)
                                (.then (fn [t] (reset! jwt {:token t :at (js/Date.now)}) t))))))
               ;; One HTTP/2 connection per APNs address, kept open (Apple asks for that).
               session (fn [origin]
                         (let [^js have (get @sessions origin)]
                           (if (and have (not (.-closed have)) (not (.-destroyed have)))
                             have
                             (let [^js s (http2/connect origin)]
                               (.on s "error" #(js/console.error (str "push: APNs connection: " (.-message %))))
                               (.on s "goaway" #(.close s))
                               (.on s "close" #(when (identical? (get @sessions origin) s) (swap! sessions dissoc origin)))
                               (.setTimeout s (* 30 60000) #(.close s))
                               (swap! sessions assoc origin s)
                               s))))]
           (fn send [{:keys [token env topic]} payload & [kind]]
             (-> (bearer)
                 (.then #(post (session (get apple env)) token topic (js/JSON.stringify (clj->js payload)) % (or kind alert)))
                 (.then
                  (fn [{:keys [status body]}]
                    (let [reason (try (str (or (.-reason (js/JSON.parse body)) "")) (catch :default _ ""))
                          detail (str/trim (str status " " reason))]
                      (cond
                        (= status 200) {:status :ok}
                        (or (= status 410) (#{"BadDeviceToken" "Unregistered"} reason)) {:status :gone :detail detail}
                        (and (= status 403) (re-find #"ProviderToken" reason))
                        (do (reset! jwt {:token nil :at 0})
                            {:status (if (= reason "ExpiredProviderToken") :retry :failed) :detail detail})
                        (or (= status 429) (>= status 500) (= status 0)) {:status :retry :detail detail}
                        :else {:status :failed :detail detail}))))
                 (.catch (fn [e] {:status :retry :detail (.-message e)})))))))))

(defn dry-run
  "Pushes written to the log instead of sent. Titles show as their length only: no chat text in logs."
  [{:keys [token env topic]} payload & [kind]]
  (let [{:keys [type priority]} (or kind alert)
        shown (js/JSON.stringify (clj->js payload)
                                 (fn [k v] (if (and (= k "title") (string? v) (not= v "Claudia"))
                                             (str "‹" (count v) " chars›") v)))]
    (js/console.log (str "push (dry run, " type " p" priority ") to " (short-token token) " (" env ", " topic "): " shown))
    (js/Promise.resolve {:status :dry})))
