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

(defn call-opts
  "fetch options for method with body (data, sent as JSON when given), asking for JSON back."
  [method body]
  (cond-> {:method method :headers {"accept" "application/json"}}
    body (-> (assoc-in [:headers "content-type"] "application/json")
             (assoc :body (protocol/write-json body)))))

(defn call-json
  "Promise of the JSON answer to method on u, with body (data or nil); rejects like fetch-json."
  [method u body]
  (fetch-json u (call-opts method body)))

(defn post-blob
  "Promise of the JSON answer to POSTing blob as the body (its type as content-type); rejects like
  fetch-json."
  [u ^js blob]
  (fetch-json u {:method "POST" :headers {"content-type" (or (not-empty (.-type blob)) "application/octet-stream")
                                          "accept" "application/json"}
                 :body blob}))

(defn share-of
  "How far got bytes of total are, 0 to 99 (100 only once the whole body is in; a compressed body
  can run past its content-length), or nil when the total is unknown."
  [got total]
  (when (pos? total) (min 99 (js/Math.floor (/ (* 100 got) total)))))

(defn- read-body
  "Promise of the response's body as a Blob of its type, on-share called with share-of as it comes."
  [^js r on-share]
  (let [total (js/Number (or (.get (.-headers r) "content-length") 0))
        ^js reader (when (and (pos? total) (.-body r)) (.getReader (.-body r)))
        kind #js {:type (or (.get (.-headers r) "content-type") "")}]
    (if-not reader
      (.blob r)
      ((fn more [parts got]
         (.then (.read reader)
                (fn [^js piece]
                  (if (.-done piece)
                    (js/Blob. (into-array parts) kind)
                    (let [got2 (+ got (.. piece -value -length))]
                      (on-share (share-of got2 total))
                      (more (conj parts (.-value piece)) got2))))))
       [] 0))))

(defn fetch-blob
  "Promise of what u holds as a Blob (same origin: the login cookie goes along, as for an img's src);
  on-share gets how far it is (share-of) while a big one comes. Rejects with the HTTP status."
  [u on-share]
  (.then (js/fetch u #js {:credentials "same-origin"})
         (fn [^js r]
           (if (.-ok r) (read-body r on-share) (throw (js/Error. (str "HTTP " (.-status r))))))))
