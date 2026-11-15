(ns claudia.web.health
  "The page's word on whether this release works for it (README decision 35): once per load, POST
  /hub/health says it worked (a chat list came) or why not: a socket opened and no chat list came
  (no-chats), the page's own code threw before it worked (script-error), or no socket opened at all
  (no-socket; the hub counts that one only when a server of the person's answers). Nothing is said
  while the page is hidden or offline. The hub rolls a release back on its pages' word.

  This is the page build's first module (js/health.js, loaded before main.js), so it has run, and
  catches the error, when the rest of the page's code breaks on load. It needs nothing of the rest:
  the sockets tell it with socket-opened! and working! (claudia.web.ws)."
  (:require [clojure.string :as str]))

(def no-chats-ms "A socket open this long with no chat list: the page does not work (a slow phone line
  brings a big list in well under it)." 30000)
(def no-socket-ms "This long after the load with no socket open: the page does not work." 30000)
(def ^:private tick-ms 1000)

(defonce ^:private state
  (atom {:t0 (js/Date.now) :load (str (random-uuid)) :commit nil :opened-at nil :working false
         :error nil :hidden false :reported false}))

(defn verdict
  "What the page says at now, or nil for nothing yet: {:ok true}, or {:ok false :reason :detail}.
  Only once a commit is known (the hub's /hub/me answered), never twice, never while hidden or
  offline."
  [{:keys [t0 commit opened-at working error hidden reported]} now online?]
  (cond
    (or reported (not commit)) nil
    working {:ok true}
    (or hidden (not online?)) nil
    error {:ok false :reason "script-error" :detail error}
    (and opened-at (>= (- now opened-at) no-chats-ms)) {:ok false :reason "no-chats"}
    (and (not opened-at) (>= (- now t0) no-socket-ms)) {:ok false :reason "no-socket"}))

(defn own-error
  "The text of an uncaught error thrown by the page's own scripts (origin's /js/), else nil: other
  scripts (extensions) and resources that failed to load are not the release's."
  [message filename origin]
  (when (and (string? filename) (str/starts-with? filename (str origin "/js/")))
    (str message " (" (last (str/split filename #"/")) ")")))

(defn- post! [body]
  (-> (js/fetch "/hub/health" #js {:method "POST" :credentials "same-origin" :keepalive true
                                   :headers #js {"content-type" "application/json"}
                                   :body (js/JSON.stringify (clj->js body))})
      (.catch (fn [_] nil))))

(defn- look! []
  (let [s @state]
    (when-let [v (verdict s (js/Date.now) (.-onLine js/navigator))]
      (swap! state assoc :reported true)
      (post! (merge {:load (:load s) :commit (:commit s)} v)))))

(defn socket-opened!
  "A socket to a server opened (the first one starts the no-chats wait)."
  []
  (when-not (:opened-at @state) (swap! state assoc :opened-at (js/Date.now))))

(defn working!
  "A chat list came: the page works."
  []
  (when-not (:working @state) (swap! state assoc :working true) (look!)))

(defn- on-error [^js e]
  (when-let [text (own-error (.-message e) (.-filename e) js/location.origin)]
    (when-not (or (:working @state) (:error @state)) (swap! state assoc :error text))))

(defn- learn-commit! []
  (-> (js/fetch "/hub/me" #js {:credentials "same-origin"})
      (.then (fn [^js r] (when (.-ok r) (.json r))))
      (.then (fn [^js me] (when-let [c (some-> me .-commit str not-empty)] (swap! state assoc :commit c))))
      (.catch (fn [_] nil))))

(defn start
  "Watches from the first moment: errors, the page going hidden, and the clock."
  []
  (js/window.addEventListener "error" on-error)
  (js/document.addEventListener "visibilitychange"
                                #(when (= "hidden" js/document.visibilityState) (swap! state assoc :hidden true)))
  (when (= "hidden" js/document.visibilityState) (swap! state assoc :hidden true))
  (learn-commit!)
  (let [id (atom nil)]
    (reset! id (js/setInterval #(do (look!) (when (:reported @state) (js/clearInterval @id))) tick-ms))))
