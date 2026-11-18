(ns claudia.hub.pass
  "Passing /s/<server>/api/… and /s/<server>/ws on to that server's front, as the caller: the
  front gets who is calling (X-Claudia-Email), whose chats to show (X-Claudia-View), whose chats
  the caller may write to (X-Claudia-Writes: emails, comma-separated), and none of the browser's
  own headers (its cookies hold the login). `who` = {:email :view :writes}."
  (:require ["http" :as http]
            ["ws" :refer [WebSocket WebSocketServer]]
            [clojure.string :as str]
            [claudia.hub.hub-key :as hub-key]
            [claudia.node.http :as io]
            [claudia.shared.protocol :as protocol]))

;; Connections to the fronts are kept open between requests: each one is a TCP handshake over
;; the private network saved. Node drops a kept connection just before the front's Keep-Alive timeout.
(def ^:private agent (http/Agent. #js {:keepAlive true :maxFreeSockets 8}))

(def ^:private connect-ms
  "How long a front may take to accept the connection before the caller gets 502."
  5000)

(def ^:private max-frame
  "The largest WebSocket frame either way: a chat's full state can be big, not unbounded."
  (* 64 1024 1024))

(def ^:private passed-request-headers
  "The only browser headers a front gets (besides the hub's own X-Claudia-*). range: a video Claude
  showed plays in parts (Safari asks for nothing else)."
  ["content-type" "content-length" "accept" "range"])

(def ^:private dropped-response-headers
  "Hop-by-hop headers belong to one connection, not the answer; and a front never sets cookies on
  the gate's address (the login cookie lives there)."
  #{"connection" "keep-alive" "transfer-encoding" "upgrade" "proxy-authenticate"
    "proxy-authorization" "te" "trailer" "set-cookie"})

(def ^:private json-type "application/json; charset=utf-8")

(defn- unreachable [server]
  {:error (str (:name server) " is not reachable")})

(defn who-headers
  "Who is calling, for the front: X-Claudia-Email, -View, -Writes."
  [{:keys [email view writes]}]
  (cond-> {"x-claudia-email" email}
    view (assoc "x-claudia-view" view)
    (seq writes) (assoc "x-claudia-writes" (str/join "," writes))))

(defn- hub-headers
  "Everything the hub tells that server's front: its key (claudia.hub.hub-key) and who is calling."
  [server who]
  (merge (who-headers who) (hub-key/header (:name server))))

(defn pick-headers
  "The browser headers passed on to a front: the few in passed-request-headers. in: a JS object."
  [^js in]
  (reduce (fn [h k] (if-let [v (aget in k)] (assoc h k v) h)) {} passed-request-headers))

(defn response-headers
  "The front's answer's headers (a JS object) without the dropped ones, as a JS object."
  [^js headers]
  (let [out #js {}]
    (doseq [[k v] (js/Object.entries headers)
            :when (not (dropped-response-headers k))]
      (aset out k v))
    out))

(defn- guard-connect
  "Gives a new connection to a front `connect-ms` to come up; a kept one is already connected."
  [^js req]
  (.on req "socket"
       (fn [^js socket]
         (when (.-connecting socket)
           (let [t (js/setTimeout #(.destroy req (js/Error. "connect timeout")) connect-ms)]
             (.once socket "connect" #(js/clearTimeout t))
             (.once req "close" #(js/clearTimeout t)))))))

(defn- answered [^js res ^js answer]
  (.writeHead res (.-statusCode answer) (response-headers (.-headers answer)))
  (.on answer "error" #(.destroy res))
  (.pipe answer res))

(defn- up-failed [^js res server]
  (if (.-headersSent res)
    (.destroy res)
    (io/send-json res 502 (unreachable server))))

(def ^:private retry-ms "How soon a call a restarting front refused tries again." 300)
(def ^:private retry-for-ms
  "How long a call keeps trying a front that refuses it: a front (or its runner) restarting for an
  update is back within a second or two, so the call waits instead of failing."
  15000)

(defn retry?
  "Whether a failed try to reach a front is tried again: the front refused the connection (it is
  restarting; a front that is off or asleep times out instead), nothing went out yet that could not
  be sent again (a call without a body: GET, HEAD, a WebSocket handshake), and there is time left."
  [method code now deadline]
  (boolean (and (#{"GET" "HEAD"} method)
                (#{"ECONNREFUSED" "ECONNRESET"} code)
                (< now deadline))))

(defn- send-up!
  "One try of passing the request on; a refused one tries again (retry?)."
  [^js req ^js res server target who deadline]
  (let [method (.-method req)
        ^js up (http/request #js {:host (:host server) :port (:port server) :method method :path target
                                  :headers (clj->js (merge (pick-headers (.-headers req)) (hub-headers server who)))
                                  :agent agent})]
    (guard-connect up)
    (.on up "response" #(answered res %))
    (.on up "error" (fn [^js e]
                      (if (and (retry? method (.-code e) (js/Date.now) deadline)
                               (not (.-headersSent res)) (not (.-destroyed res)))
                        (js/setTimeout #(send-up! req res server target who deadline) retry-ms)
                        (up-failed res server))))
    ;; The caller left (closed the tab, cancelled an upload): the front's side ends too.
    (.on res "close" #(when-not (.-writableFinished res) (.destroy up)))
    (if (#{"GET" "HEAD"} method) (.end up) (.pipe req up))))

(defn http!
  "Passes one request on, streaming the body both ways. `target` is the path and query on the front."
  [^js req ^js res server target who]
  (send-up! req res server target who (+ (js/Date.now) retry-for-ms)))

(defn up?
  "Calls (done true) when the front answers GET /api/me with a 2xx within 2 s, else (done false)."
  [server email done]
  (let [answered? (atom false)
        finish (fn [v] (when-not @answered? (reset! answered? true) (done v)))
        ^js req (http/request #js {:host (:host server)
                                   :port (:port server)
                                   :method "GET"
                                   :path "/api/me"
                                   :headers (clj->js (hub-headers server {:email email}))
                                   :agent agent})
        t (js/setTimeout #(.destroy req (js/Error. "timeout")) 2000)]
    (.on req "response" (fn [^js answer]
                          (js/clearTimeout t)
                          (.resume answer)
                          (finish (<= 200 (.-statusCode answer) 299))))
    (.on req "error" (fn [_] (js/clearTimeout t) (finish false)))
    (.end req)))

(defn json!
  "Promise of {:status :body} for a JSON call on server's front as who ({:email}): method, target
  (path), body (data or nil). Unreachable or not JSON: {:status 502 :body {:error ..}}. 20 s at most."
  [server method target who body]
  (js/Promise.
   (fn [done]
     (let [text (when (some? body) (js/JSON.stringify (clj->js body)))
           ^js req (http/request #js {:host (:host server) :port (:port server) :method method :path target
                                      :headers (clj->js (cond-> (hub-headers server who)
                                                          text (assoc "content-type" json-type)))
                                      :agent agent :timeout 20000})
           fail #(done {:status 502 :body (unreachable server)})]
       (.on req "response"
            (fn [^js answer]
              (let [chunks (atom [])]
                (.on answer "data" #(swap! chunks conj %))
                (.on answer "end" #(done {:status (.-statusCode answer)
                                          :body (try (js->clj (js/JSON.parse (.toString (js/Buffer.concat (into-array @chunks)) "utf8"))
                                                              :keywordize-keys true)
                                                     (catch :default _ (unreachable server)))})))))
       (.on req "timeout" #(.destroy req (js/Error. "timeout")))
       (.on req "error" (fn [_] (fail)))
       (.end req text)))))

;; ---- WebSocket ----------------------------------------------------------------------------

(def page-deflate
  "permessage-deflate for the page's side (a phone on 4G): chat frames are JSON and shrink several
  times. A small window on what the hub sends keeps its zlib context small on the gate; frames under
  1 KB go as they are. No clientMaxWindowBits: with it, ws refuses every offer without
  client_max_window_bits, and Safari and Firefox send plain permessage-deflate (a 400, no chats)."
  #js {:serverMaxWindowBits 11
       :zlibDeflateOptions #js {:memLevel 4 :level 6}
       :threshold 1024 :concurrencyLimit 4})

(defonce ^:private wss
  (WebSocketServer. #js {:noServer true
                         :maxPayload max-frame
                         :perMessageDeflate page-deflate}))

;; Every socket of a pair (both sides), for the heartbeat.
(defonce ^:private live (js/Set.))

(defn upgrade-refusal
  "The plain HTTP answer that refuses a WebSocket handshake."
  [status body content-type]
  (str "HTTP/1.1 " status " " (aget (.-STATUS_CODES http) status) "\r\n"
       "content-type: " content-type "\r\n"
       "content-length: " (js/Buffer.byteLength body) "\r\n"
       "cache-control: no-store\r\n"
       "connection: close\r\n\r\n"
       body))

(defn refuse-upgrade
  "Answers a WebSocket handshake with a plain HTTP error."
  [^js socket status body content-type]
  (when-not (.-destroyed socket)
    (.end socket (upgrade-refusal status body content-type))))

(defn closing-code
  "The close code to pass on: the other side's own when the protocol lets it be sent; when a side
  just vanished (1006), 1014 to the page (bad gateway: the front is gone) and 1001 to the front
  (going away: the page is gone)."
  [code to-page?]
  (cond
    (or (<= 1000 code 1003) (<= 1007 code 1014) (<= 3000 code 4999)) code
    (= code 1006) (if to-page? 1014 1001)
    :else nil))

(def ^:private ping-bytes
  "The page's heartbeat frame (PROTOCOL.md, page heartbeat), byte for byte as the page writes it."
  (js/Buffer.from (protocol/write-json {:type "ping"})))

(def ^:private pong-text (protocol/write-json {:type "pong"}))

(defn ping-frame?
  "Whether a frame from the page is its heartbeat: the hub answers it and passes nothing on."
  [data binary?]
  (and (not binary?) (instance? js/Buffer data) (.equals ^js data ping-bytes)))

(defn- forward!
  "One frame from `from` on to `to`. When `to` cannot keep up, `from` is paused until its buffer
  drains, so a slow page never piles a chat up in the hub."
  [^js from ^js to data binary?]
  (when (= (.-readyState to) 1)
    (.send to data #js {:binary binary?}
           (fn [_]
             (when (and (.-isPaused from) (< (.-bufferedAmount to) (* 256 1024)))
               (.resume from))))
    (when (> (.-bufferedAmount to) (* 1024 1024))
      (.pause from))))

(defn- relay
  "Frames from `from` to `to` as they are (text stays text, binary binary). With pong? (`from` is
  the page), the page's heartbeat is answered here and not passed on."
  [^js from ^js to pong?]
  (.on from "message"
       (fn [data binary?]
         (if (and pong? (ping-frame? data binary?))
           (.send from pong-text)
           (forward! from to data binary?)))))

(defn- follow
  "When `from` closes, `to` closes with the same code (or the one closing-code gives)."
  [^js from ^js to to-page?]
  (.on from "close"
       (fn [code reason]
         (.delete live from)
         (when (= (.-readyState to) 1)
           (if-let [c (closing-code code to-page?)]
             (.close to c reason)
             (.close to))))))

(defn- pair!
  "Ties the page's socket and the front's socket together: frames both ways, and when one closes
  the other closes with the same code."
  [^js page ^js front]
  (doseq [^js s [page front]]
    (aset s "alive" true)
    (.add live s)
    (.on s "pong" #(aset s "alive" true))
    (.on s "error" (fn [_] (.terminate s))))
  (relay page front true)
  (relay front page false)
  (follow page front false)
  (follow front page true))

(defn heartbeat!
  "Every 30 s: a socket that did not answer the last ping is dropped (a page whose network went
  away would otherwise hold its front's socket open for ever); the rest get a ping."
  []
  (js/setInterval
   (fn []
     (doseq [^js s (es6-iterator-seq (.values live))]
       (if (aget s "alive")
         (do (aset s "alive" false)
             (try (.ping s) (catch :default _ nil)))
         (.terminate s))))
   30000))

(defn front-url
  "The front's WebSocket address for a target path."
  [server target]
  (str "ws://" (io/url-host (:host server)) ":" (:port server) target))

(defn- front-refused
  "The front answered the handshake with an HTTP answer: the page gets that status and up to 4 KB
  of its body."
  [^js front ^js answer refuse!]
  (let [chunks #js []
        size (atom 0)]
    (.on answer "data" (fn [c]
                         (when (< @size 4096)
                           (swap! size + (.-length c))
                           (.push chunks c))))
    (.on answer "end"
         (fn []
           (refuse! (.-statusCode answer)
                    (.toString (.subarray (js/Buffer.concat chunks) 0 4096))
                    (or (aget (.-headers answer) "content-type") "text/plain; charset=utf-8"))
           (.terminate front)))
    (.on answer "error" (fn [_] (.terminate front)))))

(defn- open-front ^js [server target who]
  (WebSocket. (front-url server target)
              #js {:headers (clj->js (hub-headers server who))
                   :maxPayload max-frame
                   :perMessageDeflate false
                   :handshakeTimeout connect-ms}))

(defn- connect-front!
  "One try of opening the front's socket for a page's handshake (c: what ws! keeps); a refused one
  tries again while the handshake waits (retry?)."
  [{:keys [req socket head server target who pending take! refuse! front-ref deadline] :as c}]
  (let [^js front (open-front server target who)]
    (reset! front-ref front)
    (.on front "error" (fn [^js e]
                         (if (and @pending (retry? "GET" (.-code e) (js/Date.now) deadline))
                           (js/setTimeout #(when @pending (connect-front! c)) retry-ms)
                           (refuse! 502 (protocol/write-json (unreachable server)) json-type))))
    (.on front "unexpected-response" (fn [_ answer] (front-refused front answer refuse!)))
    (.on front "open"
         (fn []
           (if (take!)
             (.handleUpgrade wss req socket head (fn [page] (pair! page front)))
             (.terminate front))))))

(defn ws!
  "Opens the front's socket first and only then completes the page's handshake: the page sees the
  front's own refusal (its status and body) when the front says no, and 502 when it is not up (a
  front that refuses while it restarts is tried again for retry-for-ms)."
  [^js req ^js socket head server target who]
  (let [;; Until the page's handshake is answered (refused or upgraded), the raw socket is ours;
        ;; after that it belongs to the page's WebSocket and nothing here may write to it.
        pending (atom true)
        front-ref (atom nil)
        take! #(when @pending (reset! pending false) true)
        refuse! (fn [status body content-type]
                  (when (take!) (refuse-upgrade socket status body content-type)))]
    (.on socket "error" (fn [_] (when (take!) (some-> ^js @front-ref .terminate))))
    (.on socket "close" (fn [_] (when (take!) (some-> ^js @front-ref .terminate))))
    (connect-front! {:req req :socket socket :head head :server server :target target :who who
                     :pending pending :take! take! :refuse! refuse! :front-ref front-ref
                     :deadline (+ (js/Date.now) retry-for-ms)})))

(defn close-all!
  "On shutdown: every page is told the hub is going away (1001); their fronts' sockets follow."
  []
  (doseq [^js s (es6-iterator-seq (.values (.-clients wss)))]
    (.close s 1001 "hub restarting")))
