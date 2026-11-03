(ns claudia.node.http
  "Reading and answering HTTP requests, the same for the hub and every slave program."
  (:require [clojure.string :as str]
            [claudia.shared.protocol :as protocol]))

(defn read-bytes
  "Promise of the request's body as a Buffer; more than limit bytes rejects and drops the request."
  [^js req limit]
  (js/Promise.
   (fn [done fail]
     (let [chunks #js [] size (atom 0)]
       (.on req "data" (fn [c]
                         (if (> (swap! size + (.-length c)) limit)
                           (do (fail (js/Error. "body too large")) (.destroy req))
                           (.push chunks c))))
       (.on req "end" #(done (js/Buffer.concat chunks)))
       (.on req "error" fail)))))

(defn read-text
  "Promise of the request's body as text (more than limit bytes rejects)."
  [req limit]
  (.then (read-bytes req limit) #(.toString ^js % "utf8")))

(defn parse-object
  "JSON text as a map with keyword keys; {} when it is empty, broken or not an object."
  [text]
  (let [v (try (protocol/read-json text) (catch :default _ nil))]
    (if (map? v) v {})))

(defn read-json
  "Promise of the request's JSON body as a map ({} when empty, broken or not an object)."
  [req limit]
  (.then (read-text req limit) parse-object))

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

(defn url-host
  "A host as it goes in a URL: an IPv6 address (it holds a colon) in brackets."
  [host]
  (if (str/includes? host ":") (str "[" host "]") host))

(defn decode
  "decodeURIComponent, or nil when s is not well encoded."
  [s]
  (try (js/decodeURIComponent s) (catch :default _ nil)))
