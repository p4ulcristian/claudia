(ns claudia.hub.front
  "The hub's own calls on a server's front (PROTOCOL.md: calls only the hub makes, refused under
  /s/<server>/): the Claude token in use and its limits (claudia.hub.tokens), the machine's numbers
  (claudia.hub.systems); and calls it makes for a person on their runner (call-as: a 3D scene's
  video, claudia.hub.videos)."
  (:require ["node:http" :as http]
            [claudia.hub.hub-key :as hub-key]
            [claudia.shared.protocol :as protocol]))

(defn- request-options [server method path text ms email]
  #js {:host (:host server) :port (:port server) :method method :path path :timeout ms
       :headers (clj->js (cond-> (assoc (hub-key/header (:name server)) "accept" "application/json")
                           text (assoc "content-type" "application/json"
                                       "content-length" (js/Buffer.byteLength text))
                           email (assoc "x-claudia-email" email)))})

(defn- answer-body [chunks]
  (or (try (protocol/read-json (.toString (js/Buffer.concat chunks) "utf8")) (catch :default _ nil)) {}))

(defn call-as
  "Promise of [status body-map] of a call on a server's front for a person (email: theirs, the front
  passes it to their runner; nil: a hub-only call), or [0 {:error}] when it does not answer within
  ms (connecting included) or cannot be reached."
  [server email method path body ms]
  (js/Promise.
   (fn [done]
     (let [text (when body (protocol/write-json body))
           on-answer (fn [^js res]
                       (let [chunks #js []]
                         (.on res "data" #(.push chunks %))
                         (.on res "end" #(done [(.-statusCode res) (answer-body chunks)]))))
           ^js req (http/request (request-options server method path text ms email) on-answer)
           t (js/setTimeout #(.destroy req (js/Error. (str "no answer in " (/ ms 1000) " s"))) ms)]
       (.on req "close" #(js/clearTimeout t))
       (.on req "error" #(done [0 {:error (.-message %)}]))
       (.end req text)))))

(defn call
  "Promise of [status body-map] of a hub-only call on a server's front (see call-as)."
  [server method path body ms]
  (call-as server nil method path body ms))

(defn- answer-of
  "Promise of [status body-map] once res (an answer) has ended."
  [^js res]
  (js/Promise. (fn [done]
                 (let [chunks #js []]
                   (.on res "data" #(.push chunks %))
                   (.on res "end" #(done [(.-statusCode res) (answer-body chunks)]))
                   (.on res "error" #(done [0 {:error (.-message %)}]))))))

(defn- pipe-on!
  "The source's answer (src, 200) streamed as the body of a POST to path on server `to` as email;
  done gets the target's [status body]. Returns the target's request."
  [^js src to email path ms done]
  (let [^js up (http/request (request-options to "POST" path nil ms email)
                             #(.then (answer-of %) done))]
    (.setHeader up "content-type" "application/x-ndjson")
    (.on up "error" #(do (.destroy src) (done [0 {:error (str (:name to) ": " (.-message %))}])))
    (.on src "error" #(.destroy up (js/Error. (str "the copy broke off: " (.-message %)))))
    (.pipe src up)
    up))

(defn pipe-as
  "Promise of [status body-map] of streaming a GET of from-path on server `from` into a POST to
  to-path on server `to`, both for the person email; the hub keeps none of it (a chat moved to
  another server, claudia.hub.move). The source answering other than 200: its [status body]; no
  end within ms: [0 {:error}]."
  [from from-path to to-path email ms]
  (js/Promise.
   (fn [done]
     (let [timer (atom nil)
           up (atom nil)
           finish (fn [v] (some-> @timer js/clearTimeout) (done v))
           ^js down (http/request (request-options from "GET" from-path nil ms email)
                                  (fn [^js src]
                                    (if (= 200 (.-statusCode src))
                                      (reset! up (pipe-on! src to email to-path ms finish))
                                      (.then (answer-of src) finish))))]
       (reset! timer (js/setTimeout #(do (some-> ^js @up .destroy) (.destroy down)
                                         (done [0 {:error (str "no end in " (/ ms 1000) " s")}]))
                                    ms))
       (.on down "error" #(finish [0 {:error (str (:name from) ": " (.-message %))}]))
       (.end down)))))
