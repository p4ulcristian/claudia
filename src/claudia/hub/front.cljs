(ns claudia.hub.front
  "The hub's own calls on a server's front (PROTOCOL.md: calls only the hub makes, refused under
  /s/<server>/): the Claude token in use and its limits (claudia.hub.tokens), the machine's numbers
  (claudia.hub.systems); and calls it makes for a person on their runner (call-as: a 3D scene's
  video, claudia.hub.videos)."
  (:require ["node:http" :as http]
            [claudia.shared.protocol :as protocol]))

(defn- request-options [server method path text ms email]
  #js {:host (:host server) :port (:port server) :method method :path path :timeout ms
       :headers (clj->js (cond-> {"accept" "application/json"}
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
