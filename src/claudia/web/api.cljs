(ns claudia.web.api
  "HTTP from the page: the code forms' reads from a chat's server (/s/<server>/api/...), outside
  re-frame (each form polls what it shows while it is on screen), and the fetches re-frame's effects
  make."
  (:require [clojure.string :as str]
            [claudia.shared.protocol :as protocol]))

(defn url
  "/s/<server>/api/<path>?<query> (query: a map; nil values left out)."
  [server path query]
  (str "/s/" (js/encodeURIComponent server) "/api/" path
       (let [q (for [[k v] query :when (some? v)] (str (name k) "=" (js/encodeURIComponent (str v))))]
         (when (seq q) (str "?" (str/join "&" q))))))

(defn fetch-answer
  "Promise of {:status :ok :body} (the body as data with keyword keys, nil when empty or not JSON).
  Same origin, so the gate's login cookie goes along."
  ([u] (fetch-answer u {}))
  ([u opts]
   (-> (js/fetch u (clj->js (merge {:credentials "same-origin" :headers {"accept" "application/json"}} opts)))
       (.then (fn [^js r]
                (.then (.text r)
                       (fn [t]
                         {:status (.-status r)
                          :ok (.-ok r)
                          :body (when-not (str/blank? t) (try (protocol/read-json t) (catch :default _ nil)))})))))))

(defn fetch-json
  "Promise of the JSON answer (keyword keys); rejects with the server's error, its :status in ex-data."
  ([u] (fetch-json u {}))
  ([u opts]
   (.then (fetch-answer u opts)
          (fn [{:keys [status ok body]}]
            (if ok body (throw (ex-info (or (:error body) (str "HTTP " status)) {:status status})))))))

(defn post-json [u body]
  (fetch-json u {:method "POST" :headers {"content-type" "application/json" "accept" "application/json"}
                 :body (protocol/write-json body)}))
