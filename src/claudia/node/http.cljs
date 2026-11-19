(ns claudia.node.http
  "Reading and answering HTTP requests, the same for the hub and every slave program, and one JSON
  call over a unix socket (the front, the runners and the root helper call each other so)."
  (:require ["node:fs" :as fs]
            ["node:http" :as http]
            [clojure.string :as str]
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

(defn send-text
  "Answers with text, never cached."
  [^js res status text]
  (.writeHead res status #js {"content-type" "text/plain; charset=utf-8" "cache-control" "no-store"})
  (.end res text))

(defn no-content
  "Answers 204 with no body, never cached."
  [^js res]
  (.writeHead res 204 #js {"cache-control" "no-store"})
  (.end res))

(defn byte-range
  "The bytes a Range header asks for of a file of size bytes: {:start :end} (end included), :bad
  when it can't be served (the answer is 416), nil for the whole file (no header, or one this
  doesn't read: several ranges, other units)."
  [header size]
  (when-let [[_ a b] (re-matches #"bytes=(\d*)-(\d*)" (str/trim (str header)))]
    (let [a (when (seq a) (js/parseInt a 10))
          b (when (seq b) (js/parseInt b 10))]
      (cond (and (nil? a) (nil? b)) nil
            (nil? a) (if (pos? b) {:start (max 0 (- size b)) :end (dec size)} :bad)
            (>= a size) :bad
            (and b (< b a)) :bad
            :else {:start a :end (min (dec size) (or b (dec size)))}))))

(defn send-range
  "Answers with file f of size bytes, the whole file or range r (byte-range's answer: 206, or 416
  for :bad). head: the headers besides the length and range ones (content-type, cache-control, CSP)."
  [^js res f size head r]
  (if (= r :bad)
    (do (.writeHead res 416 #js {"content-range" (str "bytes */" size)}) (.end res))
    (let [{:keys [start end]} (or r {:start 0 :end (dec size)})
          all (cond-> (merge {"x-content-type-options" "nosniff"} head
                             {"content-length" (str (inc (- end start))) "accept-ranges" "bytes"})
                r (assoc "content-range" (str "bytes " start "-" end "/" size)))]
      (.writeHead res (if r 206 200) (clj->js all))
      (.pipe (fs/createReadStream f #js {:start start :end end}) res))))

(defn url-host
  "A host as it goes in a URL: an IPv6 address (it holds a colon) in brackets."
  [host]
  (if (str/includes? host ":") (str "[" host "]") host))

(defn decode
  "decodeURIComponent, or nil when s is not well encoded."
  [s]
  (try (js/decodeURIComponent s) (catch :default _ nil)))

(defn socket-json
  "Promise of {:status :body} for POSTing body (data, as JSON) to path on the unix socket socket;
  :body is the answer as a map ({} when it is not a JSON object). Rejects when the socket does not
  answer within ms. on-request, when given, gets the request (destroying it gives up the call)."
  ([socket path body ms] (socket-json socket path body ms nil))
  ([socket path body ms on-request]
   (js/Promise.
    (fn [done fail]
      (let [req (http/request #js {:socketPath socket :method "POST" :path path :timeout ms
                                   :headers #js {"content-type" "application/json"}}
                              (fn [^js res]
                                (.then (read-text res (* 1024 1024))
                                       #(done {:status (.-statusCode res) :body (parse-object %)})
                                       fail)))]
        (.on req "timeout" #(.destroy req (js/Error. "no answer in time")))
        (.on req "error" fail)
        (when on-request (on-request req))
        (.end req (protocol/write-json body)))))))
