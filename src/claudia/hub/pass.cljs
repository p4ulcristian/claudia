(ns claudia.hub.pass
  "Passing /s/<server>/api/… and /s/<server>/ws on to that server's front, as the caller: the
  front gets who is calling (X-Claudia-Email) and whose chats to show (X-Claudia-View), and none of
  the browser's own headers (its cookies hold the login)."
  (:require ["http" :as http]
            ["ws" :refer [WebSocket WebSocketServer]]))

;; Connections to the fronts are kept open between requests: each one is a TCP handshake over
;; WireGuard saved. Node drops a kept connection just before the front's Keep-Alive timeout.
(def ^:private agent (http/Agent. #js {:keepAlive true :maxFreeSockets 8}))

(def ^:private connect-ms
  "How long a front may take to accept the connection before the caller gets 502."
  5000)

(def ^:private max-frame
  "The largest WebSocket frame either way: a chat's full state can be big, not unbounded."
  (* 64 1024 1024))

(def ^:private passed-request-headers
  "The only browser headers a front gets (besides the hub's own X-Claudia-*)."
  ["content-type" "content-length" "accept"])

(def ^:private dropped-response-headers
  "Hop-by-hop headers belong to one connection, not the answer; and a front never sets cookies on
  the gate's address (the login cookie lives there)."
  #{"connection" "keep-alive" "transfer-encoding" "upgrade" "proxy-authenticate"
    "proxy-authorization" "te" "trailer" "set-cookie"})

(defn- json-text [m]
  (js/JSON.stringify (clj->js m)))

(defn- hub-headers [email view]
  (cond-> {"x-claudia-email" email}
    view (assoc "x-claudia-view" view)))

(defn- guard-connect
  "Gives a new connection to a front `connect-ms` to come up; a kept one is already connected."
  [^js req]
  (.on req "socket"
       (fn [^js socket]
         (when (.-connecting socket)
           (let [t (js/setTimeout #(.destroy req (js/Error. "connect timeout")) connect-ms)]
             (.once socket "connect" #(js/clearTimeout t))
             (.once req "close" #(js/clearTimeout t)))))))

(defn http!
  "Passes one request on, streaming the body both ways. `target` is the path and query on the front."
  [^js req ^js res server target email view]
  (let [in (.-headers req)
        headers (reduce (fn [h k] (if-let [v (aget in k)] (assoc h k v) h))
                        (hub-headers email view)
                        passed-request-headers)
        ^js up (http/request #js {:host (:host server)
                                  :port (:port server)
                                  :method (.-method req)
                                  :path target
                                  :headers (clj->js headers)
                                  :agent agent})]
    (guard-connect up)
    (.on up "response"
         (fn [^js answer]
           (let [out #js {}]
             (doseq [[k v] (js/Object.entries (.-headers answer))
                     :when (not (dropped-response-headers k))]
               (aset out k v))
             (.writeHead res (.-statusCode answer) out)
             (.on answer "error" #(.destroy res))
             (.pipe answer res))))
    (.on up "error"
         (fn [_]
           (if (.-headersSent res)
             (.destroy res)
             (do (.writeHead res 502 #js {"content-type" "application/json; charset=utf-8"
                                          "cache-control" "no-store"})
                 (.end res (json-text {:error (str (:name server) " is not reachable")}))))))
    ;; The caller left (closed the tab, cancelled an upload): the front's side ends too.
    (.on res "close" #(when-not (.-writableFinished res) (.destroy up)))
    (.pipe req up)))

(defn up?
  "Calls (done true) when the front answers GET /api/me with a 2xx within 2 s, else (done false)."
  [server email done]
  (let [answered (atom false)
        finish (fn [v] (when-not @answered (reset! answered true) (done v)))
        ^js req (http/request #js {:host (:host server)
                                   :port (:port server)
                                   :method "GET"
                                   :path "/api/me"
                                   :headers (clj->js (hub-headers email nil))
                                   :agent agent})
        t (js/setTimeout #(.destroy req (js/Error. "timeout")) 2000)]
    (.on req "response" (fn [^js answer]
                          (js/clearTimeout t)
                          (.resume answer)
                          (finish (<= 200 (.-statusCode answer) 299))))
    (.on req "error" (fn [_] (js/clearTimeout t) (finish false)))
    (.end req)))

;; ---- WebSocket ----------------------------------------------------------------------------

(defonce ^:private wss
  (WebSocketServer. #js {:noServer true
                         :maxPayload max-frame
                         ;; deflate keeps a zlib context per socket: real memory on the gate
                         :perMessageDeflate false}))

;; Every socket of a pair (both sides), for the heartbeat.
(defonce ^:private live (js/Set.))

(defn refuse-upgrade
  "Answers a WebSocket handshake with a plain HTTP error."
  [^js socket status body content-type]
  (when-not (.-destroyed socket)
    (.end socket (str "HTTP/1.1 " status " " (aget (.-STATUS_CODES http) status) "\r\n"
                      "content-type: " content-type "\r\n"
                      "content-length: " (js/Buffer.byteLength body) "\r\n"
                      "cache-control: no-store\r\n"
                      "connection: close\r\n\r\n"
                      body))))

(defn- closing-code
  "The close code to pass on: the other side's own when the protocol lets it be sent; when a side
  just vanished (1006), 1014 to the page (bad gateway: the front is gone) and 1001 to the front
  (going away: the page is gone)."
  [code to-page?]
  (cond
    (or (<= 1000 code 1003) (<= 1007 code 1014) (<= 3000 code 4999)) code
    (= code 1006) (if to-page? 1014 1001)
    :else nil))

(defn- relay
  "Frames from `from` to `to` as they are (text stays text, binary binary). When `to` cannot keep
  up, `from` is paused until its buffer drains, so a slow page never piles a chat up in the hub."
  [^js from ^js to]
  (.on from "message"
       (fn [data binary?]
         (when (= (.-readyState to) 1)
           (.send to data #js {:binary binary?}
                  (fn [_]
                    (when (and (.-isPaused from) (< (.-bufferedAmount to) (* 256 1024)))
                      (.resume from))))
           (when (> (.-bufferedAmount to) (* 1024 1024))
             (.pause from))))))

(defn- pair!
  "Ties the page's socket and the front's socket together: frames both ways, and when one closes
  the other closes with the same code."
  [^js page ^js front]
  (doseq [^js s [page front]]
    (aset s "alive" true)
    (.add live s)
    (.on s "pong" #(aset s "alive" true))
    (.on s "error" (fn [_] (.terminate s))))
  (relay page front)
  (relay front page)
  (letfn [(follow [^js from ^js to to-page?]
            (.on from "close"
                 (fn [code reason]
                   (.delete live from)
                   (when (= (.-readyState to) 1)
                     (if-let [c (closing-code code to-page?)]
                       (.close to c reason)
                       (.close to))))))]
    (follow page front false)
    (follow front page true)))

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

(defn ws!
  "Opens the front's socket first and only then completes the page's handshake: the page sees the
  front's own refusal (its status and body) when the front says no, and 502 when it is not up."
  [^js req ^js socket head server target email view]
  (let [host (:host server)
        ^js front (WebSocket. (str "ws://" (if (re-find #":" host) (str "[" host "]") host)
                                   ":" (:port server) target)
                              #js {:headers (clj->js (hub-headers email view))
                                   :maxPayload max-frame
                                   :perMessageDeflate false
                                   :handshakeTimeout connect-ms})
        ;; Until the page's handshake is answered (refused or upgraded), the raw socket is ours;
        ;; after that it belongs to the page's WebSocket and nothing here may write to it.
        pending (atom true)
        refuse! (fn [status body content-type]
                  (when @pending
                    (reset! pending false)
                    (refuse-upgrade socket status body content-type)))]
    (.on socket "error" (fn [_] (when @pending (reset! pending false) (.terminate front))))
    (.on socket "close" (fn [_] (when @pending (reset! pending false) (.terminate front))))
    (.on front "error"
         (fn [_]
           (refuse! 502 (json-text {:error (str (:name server) " is not reachable")})
                    "application/json; charset=utf-8")))
    (.on front "unexpected-response"
         (fn [_ ^js answer]
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
             (.on answer "error" (fn [_] (.terminate front))))))
    (.on front "open"
         (fn []
           (if-not @pending
             (.terminate front)
             (do (reset! pending false)
                 (.handleUpgrade wss req socket head (fn [page] (pair! page front)))))))))

(defn close-all!
  "On shutdown: every page is told the hub is going away (1001); their fronts' sockets follow."
  []
  (doseq [^js s (es6-iterator-seq (.values (.-clients wss)))]
    (.close s 1001 "hub restarting")))
