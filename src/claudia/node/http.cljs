(ns claudia.node.http
  "Reading and answering HTTP requests, the same for the hub and every slave program."
  (:require [claudia.shared.protocol :as protocol]))

(defn read-bytes
  "Promise of the request's body as a Buffer; more than max bytes rejects and drops the request."
  [^js req max]
  (js/Promise.
   (fn [resolve reject]
     (let [chunks #js [] size (atom 0)]
       (.on req "data" (fn [c]
                         (if (> (swap! size + (.-length c)) max)
                           (do (reject (js/Error. "body too large")) (.destroy req))
                           (.push chunks c))))
       (.on req "end" #(resolve (js/Buffer.concat chunks)))
       (.on req "error" reject)))))

(defn read-text
  "Promise of the request's body as text (more than max bytes rejects)."
  [req max]
  (.then (read-bytes req max) #(.toString ^js % "utf8")))

(defn parse-object
  "JSON text as a map with keyword keys; {} when it is empty, broken or not an object."
  [text]
  (let [v (try (protocol/read-json text) (catch :default _ nil))]
    (if (map? v) v {})))

(defn read-json
  "Promise of the request's JSON body as a map ({} when empty, broken or not an object)."
  [req max]
  (.then (read-text req max) parse-object))

(defn send-json
  "Answers with data as JSON, never cached."
  [^js res status data]
  (.writeHead res status #js {"content-type" "application/json; charset=utf-8" "cache-control" "no-store"})
  (.end res (protocol/write-json data)))

(defn no-content
  "Answers 204 with no body, never cached."
  [^js res]
  (.writeHead res 204 #js {"cache-control" "no-store"})
  (.end res))
