(ns claudia.hub.systems
  "The Systems popup's numbers (README decision 12) and System (decision 95): a server's from its
  front (GET /api/system…, calls only the hub makes), the gate's from the hub itself, for admins;
  the labels that name projects on System (system_labels), written by admins."
  (:require [clojure.string :as str]
            [claudia.hub.front :as front]
            [claudia.hub.store :as store]
            [claudia.node.details :as details]
            [claudia.node.history :as history]
            [claudia.node.http :as io]
            [claudia.node.system :as system]))

(def ^:private wait-ms
  "A front's first sample after a quiet spell takes its 1 s window plus two reads."
  4000)

(def ^:private details-wait-ms
  "A front's Details read the helper's reads and walk every process."
  25000)

(defn answer-of
  "The page's answer [status body] from the front's [status body]: its sample, else 502 with why."
  [[status body]]
  (cond
    (= 200 status) [200 body]
    (zero? status) [502 {:error (str "not reached: " (:error body))}]
    :else [502 {:error (str "answered " status (when (:error body) (str ": " (:error body))))}]))

(defn- pass-on [server path ms ^js res]
  (.then (front/call server "GET" path nil ms)
         (fn [answer] (let [[status body] (answer-of answer)] (io/send-json res status body)))))

(defn server-sample
  "Answers with the server's newest sample."
  [server ^js res]
  (pass-on server "/api/system" wait-ms res))

(defn server-system
  "Answers System's call what (details, history, unit-log) with the server front's answer, the
  page's query passed on."
  [server what query ^js res]
  (pass-on server (str "/api/system/" what query) details-wait-ms res))

(defn- answer-promise [^js res answer]
  (-> answer
      (.then #(io/send-json res 200 %))
      (.catch #(io/send-json res 500 {:error (.-message %)}))))

(defn gate-sample
  "Answers with the gate's own newest sample (the hub's machine)."
  [^js res]
  (answer-promise res (system/latest!)))

(def ^:private gate-opts
  {:why-no-helper "The gate has no root helper: only what the hub's own user may see is shown."})

(defn gate-system
  "The gate's own details or history (what), read by the hub: no root helper there."
  [what query ^js res]
  (case what
    "details" (answer-promise res (details/latest! gate-opts))
    "history" (let [since (- (js/Date.now) (* 3600000 (history/hours-of (.get (js/URLSearchParams. query) "hours"))))]
                (answer-promise res (.then (-> (details/log! gate-opts) (.catch (fn [_] [])))
                                           #(history/answer (history/rows-since (store/database) since) % since))))
    (io/send-json res 404 {:error "not found"})))

(defn start-history!
  "Keeps the gate's history in the hub's store, one row a minute."
  []
  (history/start! (store/database) #(details/context-now! gate-opts)))

;; ---- labels ----

(def label-kinds #{"unit" "container" "port" "process"})

(defn label-of
  "A PUT body as the label to keep (by: the admin's email), or {:error}."
  [{:keys [server kind match project]} by]
  (let [[server match project] (map #(str/trim (str (or % ""))) [server match project])]
    (cond (not (re-matches #"^(\*|[a-z][a-z0-9-]{0,30})$" server)) {:error "server must be a server's name or *."}
          (not (label-kinds kind)) {:error "kind must be unit, container, port or process."}
          (not (<= 1 (count match) 120)) {:error "match must be 1 to 120 characters."}
          (not (<= 1 (count project) 120)) {:error "project must be 1 to 120 characters."}
          :else {:server server :kind kind :match match :project project :by by})))

(defn labels-call
  "/hub/system/labels and /hub/system/labels/<id>: GET for anyone who may see System, PUT and
  DELETE for admins."
  [^js req ^js res pathname email admin? read-json]
  (let [method (.-method req)
        [_ id] (re-matches #"^/hub/system/labels/(\d+)$" pathname)]
    (cond (and (nil? id) (= "GET" method)) (io/send-json res 200 {:labels (store/system-labels)})
          (not admin?) (io/send-json res 403 {:error "admins only"})
          (and (nil? id) (= "PUT" method))
          (.then (read-json req)
                 #(let [l (label-of % email)]
                    (if (:error l) (io/send-json res 400 l) (io/send-json res 200 {:label (store/save-system-label! l)}))))
          (and id (= "DELETE" method)) (do (store/drop-system-label! (js/parseInt id 10)) (io/send-json res 200 {:ok true}))
          :else (io/send-json res 405 {:error "GET, PUT or DELETE"}))))
