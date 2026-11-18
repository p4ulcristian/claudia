(ns claudia.slave.chat-ops
  "Chat operations over HTTP (README decision 48, Claudia's own chat): what a person does with the
  socket's frames (new, send, archive, stop), as plain calls, so the hub can carry out Claudia's
  tools on every server as that person (claudia.hub.claudia). Read only callers are refused by
  the front before they get here (it lets only GET through).

  GET  /api/chat-ops/chats                    -> {chats: [Row]}
  POST /api/chat-ops/new   {text, folder?}    -> {chat}
  POST /api/chat-ops/send  {chat, text}       -> {ok}
  POST /api/chat-ops/archive {chat, archived} -> {ok}
  POST /api/chat-ops/stop  {chat}             -> {ok}"
  (:require [clojure.string :as str]
            [claudia.node.http :as io]))

(def ^:private max-body (* 256 1024))

(defn route
  "Which operation a request is: :chats, :new, :send, :archive, :stop, or nil (not one of these)."
  [method url]
  (case [method url]
    ["GET" "/api/chat-ops/chats"] :chats
    ["POST" "/api/chat-ops/new"] :new
    ["POST" "/api/chat-ops/send"] :send
    ["POST" "/api/chat-ops/archive"] :archive
    ["POST" "/api/chat-ops/stop"] :stop
    nil))

(defn problem
  "What is wrong with operation op's body b (known?: whether a chat id is in the store), or nil."
  [op b known?]
  (cond
    (and (#{:new :send} op) (str/blank? (:text b))) "text is needed"
    (and (#{:send :archive :stop} op) (not (known? (str (:chat b))))) "no such chat"
    :else nil))

(defn- operate! [op b {:keys [new! send! archive! stop!]}]
  (case op
    :new {:chat (new! (str (:text b)) (not-empty (str (:folder b))))}
    :send (do (send! (str (:chat b)) (str (:text b))) {:ok true})
    :archive (do (archive! (str (:chat b)) (boolean (:archived b))) {:ok true})
    :stop (do (stop! (str (:chat b))) {:ok true})))

(defn handle
  "Answers the call when it is a chat operation (fns: :rows :known? :new! :send! :archive! :stop!);
  false when not."
  [^js req ^js res {:keys [rows known?] :as fns}]
  (let [op (route (.-method req) (first (str/split (.-url req) #"\?")))]
    (cond
      (nil? op) false
      (= op :chats) (do (io/send-json res 200 {:chats (rows)}) true)
      :else (do (-> (io/read-json req max-body)
                    (.then (fn [b]
                             (if-let [p (problem op b known?)]
                               (io/send-json res 400 {:error p})
                               (io/send-json res 200 (operate! op b fns)))))
                    (.catch #(io/send-json res 400 {:error (.-message %)})))
                true))))
