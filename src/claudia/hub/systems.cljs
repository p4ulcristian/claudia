(ns claudia.hub.systems
  "The Systems popup's numbers (README decision 12): a server's from its front (GET /api/system, a
  call only the hub makes), the gate's from the hub itself, for admins."
  (:require [claudia.hub.front :as front]
            [claudia.node.http :as io]
            [claudia.node.system :as system]))

(def ^:private wait-ms
  "A front's first sample after a quiet spell takes its 1 s window plus two reads."
  4000)

(defn answer-of
  "The page's answer [status body] from the front's [status body]: its sample, else 502 with why."
  [[status body]]
  (cond
    (= 200 status) [200 body]
    (zero? status) [502 {:error (str "not reached: " (:error body))}]
    :else [502 {:error (str "answered " status (when (:error body) (str ": " (:error body))))}]))

(defn server-sample
  "Answers with the server's newest sample."
  [server ^js res]
  (.then (front/call server "GET" "/api/system" nil wait-ms)
         (fn [answer] (let [[status body] (answer-of answer)] (io/send-json res status body)))))

(defn gate-sample
  "Answers with the gate's own newest sample (the hub's machine)."
  [^js res]
  (-> (system/latest!)
      (.then #(io/send-json res 200 %))
      (.catch #(io/send-json res 500 {:error (.-message %)}))))
