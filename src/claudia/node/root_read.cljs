(ns claudia.node.root-read
  "The front's side of the root helper's read socket (README decision 96): one frame out, the whole
  answer in. A missing socket means no helper, or one from before System (decision 54: a person
  copies the helper with host/, the automatic update never does)."
  (:require ["node:net" :as net]
            [claudia.node.env :as env]
            [claudia.root.frames :as frames]))

(def socket (env/env "FRONT_ROOT_READ_SOCKET" "/run/claudia-root-read/read.sock"))

(def ^:private wait-ms 20000)

(defn why-not
  "What the page is told when the helper did not answer (code: the socket's error code)."
  [code]
  (case code
    ("ENOENT" "ECONNREFUSED") "The root helper's read socket is not there: the helper is missing or older than System (a person applies host/ to update it)."
    "EACCES" "The front may not open the root helper's read socket (its folder's group must be the front's)."
    (str "The root helper did not answer (" (or code "no answer") ").")))

(defn ask!
  "Promise of {:out text}, {:error why} (the helper refused or the read failed) or {:missing why}
  (no helper answered) for one read frame."
  [frame]
  (js/Promise.
   (fn [done]
     (let [^js sock (net/createConnection socket)
           chunks #js []
           finish (fn [v] (.destroy sock) (done v))]
       (.setTimeout sock wait-ms #(finish {:missing (why-not "ETIMEDOUT")}))
       (.on sock "connect" #(.write sock (frames/line frame)))
       (.on sock "data" #(.push chunks %))
       (.on sock "error" #(finish {:missing (why-not (.-code %))}))
       (.on sock "end" #(finish (or (frames/read-frame (.toString (js/Buffer.concat chunks) "utf8"))
                                    {:missing (why-not "EPROTO")})))))))
