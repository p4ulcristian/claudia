(ns claudia.web.native
  "The iPhone app is this page in a native shell (README, Hub operations: push). The shell sets
  window.claudiaNative before the page loads, and when iOS gives it an APNs token it sets
  claudiaNative.pushToken and fires \"claudia-push-token\" (before or after this runs, so both are
  read), again whenever a field changes: the key alert texts are sealed with, and ActivityKit's
  push-to-start token, which may come later. Each new value goes to the hub (POST /hub/push/devices)
  under the signed-in person. In a browser nothing happens.")

(def ^:private retry-ms 30000)
(def ^:private tries-max 10)
(defonce ^:private latest (atom nil))
(defonce ^:private sent (atom ""))

(defn- fields [^js t]
  (js/JSON.stringify
   (clj->js (cond-> {:token (.-token t) :env (.-env t) :topic (.-topic t) :key (.-key t)}
              (.-liveStartToken t) (assoc :liveStartToken (.-liveStartToken t))))))

(defn retry?
  "Whether a registration is tried again: the hub unreachable (status nil) or failing (5xx), within
  tries-max tries. Refused: not again until a field changes or the app starts again."
  [status tries]
  (and (or (nil? status) (>= status 500)) (< (inc tries) tries-max)))

(defn- register! [^js t tries]
  (let [body (fields t)]
    (when-not (= body @sent)
      (reset! sent body)
      (-> (js/fetch "/hub/push/devices" #js {:method "POST" :credentials "same-origin"
                                             :headers #js {"content-type" "application/json"} :body body})
          (.catch (fn [_] nil))
          (.then (fn [^js r]
                   ;; Again in a while, unless a newer token came.
                   (when (retry? (some-> r .-status) tries)
                     (reset! sent "")
                     (js/setTimeout #(when (identical? @latest t) (register! t (inc tries))) retry-ms))))))))

(defn- on-token [^js t]
  (when (and t (string? (.-token t)) (seq (.-token t)))
    (reset! latest t)
    (register! t 0)))

(defn start! []
  (when-let [^js native (.-claudiaNative js/window)]
    (js/window.addEventListener "claudia-push-token" #(on-token (.-detail %)))
    (on-token (.-pushToken native))))
