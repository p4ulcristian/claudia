(ns claudia.web.api
  "Reads the code forms make from a chat's server (/s/<server>/api/...), outside re-frame: each form
  polls what it shows while it is on screen."
  (:require [clojure.string :as str]
            [claudia.shared.protocol :as protocol]))

(defn url
  "/s/<server>/api/<path>?<query> (query: a map; nil values left out)."
  [server path query]
  (str "/s/" (js/encodeURIComponent server) "/api/" path
       (let [q (for [[k v] query :when (some? v)] (str (name k) "=" (js/encodeURIComponent (str v))))]
         (when (seq q) (str "?" (str/join "&" q))))))

(defn fetch-json
  "Promise of the JSON answer (keyword keys); rejects with the server's error."
  ([u] (fetch-json u {}))
  ([u opts]
   (-> (js/fetch u (clj->js (merge {:credentials "same-origin" :headers {"accept" "application/json"}} opts)))
       (.then (fn [^js r]
                (.then (.text r)
                       (fn [t]
                         (let [body (when-not (str/blank? t) (try (protocol/read-json t) (catch :default _ nil)))]
                           (if (.-ok r) body (throw (js/Error. (or (:error body) (str "HTTP " (.-status r))))))))))))))

(defn post-json [u body]
  (fetch-json u {:method "POST" :headers {"content-type" "application/json" "accept" "application/json"}
                 :body (protocol/write-json body)}))
