(ns claudia.hub.move
  "Moving a chat to another server (README decision 82): POST /hub/chat-move {chat, from, to,
  folder?} from the page, for the signed-in person's own chats, between two servers where they have
  a profile. The hub drives both runners through their fronts as that person (claudia.slave.move):
  the target says where the chat will work; the source holds the chat; the copy streams from the
  source into the target (the hub keeps none of it); the target reads it back and the hub checks it
  against the source (claudia.shared.chat-move/check-problem). Only then does the target take the
  chat in and the source archive its own (never deleted) and turn its root switch off. Any failure
  gives the move up: the target drops its copy, the source keeps the chat as it was."
  (:require [claudia.hub.config :as config]
            [claudia.hub.front :as front]
            [claudia.node.http :as io]
            [claudia.shared.chat-move :as cm]))

(def ^:private step-ms 30000)
(def ^:private copy-ms (* 15 60000))

(defn- call!
  "Promise of the body of a move call on server for email, rejected with why (named by the server)
  when it is not a 200."
  [server email method path body]
  (.then (front/call-as server email method path body step-ms)
         (fn [[status b]]
           (if (= 200 status) b (throw (ex-info (str (:name server) ": " (or (:error b) "no answer")) {}))))))

(defn- copy!
  "Promise of the target's check of the copy streamed into it from the source."
  [{:keys [from to email chat]}]
  (let [q (str "?chat=" (js/encodeURIComponent chat))]
    (.then (front/pipe-as from (str "/api/chat-move/export" q) to (str "/api/chat-move/import" q) email copy-ms)
           (fn [[status b]]
             (if (= 200 status) (:check b) (throw (ex-info (str "The copy failed: " (or (:error b) "no answer")) {})))))))

(defn- verified!
  "Promise that the target's check (dst) matches the source's (src), rejected with why not."
  [{:keys [from to]} src dst]
  (if-let [why (cm/check-problem (:name from) (:name to) src dst)]
    (js/Promise.reject (ex-info why {}))
    (js/Promise.resolve nil)))

(defn- give-up!
  "The move failed: the target's copy goes, the source keeps the chat (each best effort: a hold and a
  copy also end by themselves within 15 minutes)."
  [{:keys [from to email chat]}]
  (js/Promise.all #js [(.catch (call! to email "POST" "/api/chat-move/drop" {:chat chat}) (fn [_] nil))
                       (.catch (call! from email "POST" "/api/chat-move/release" {:chat chat}) (fn [_] nil))]))

(defn- source-done!
  "The chat lives on the target now: the source archives its copy as moved there and its root switch
  goes off (both logged when they fail: the move itself has happened)."
  [{:keys [from to email chat]}]
  (-> (call! from email "POST" "/api/chat-move/release" {:chat chat :to (:name to)})
      (.then #(call! from email "POST" "/api/root/switch" {:chat chat :on false}))
      (.catch #(js/console.error (str "move " chat ": " (:name from) " after the move: " (ex-message %))))))

(defn- move!
  "Promise of the moved chat's {:folder} on the target; rejected with why it did not move."
  [{:keys [from to email chat folder] :as m}]
  (-> (call! to email "POST" "/api/chat-move/check" {:chat chat :folder folder})
      (.then (fn [{there :folder}]
               (-> (call! from email "POST" "/api/chat-move/hold" {:chat chat})
                   (.then (fn [{src :check}]
                            (-> (copy! m)
                                (.then #(verified! m src %))
                                (.then #(call! to email "POST" "/api/chat-move/commit"
                                               {:chat chat :folder there :from (:name from) :to (:name to)}))
                                (.catch (fn [e] (.then (give-up! m) #(throw e))))))))))
      (.then (fn [{there :folder}] (.then (source-done! m) (fn [_] {:folder there}))))))

(defn call
  "POST /hub/chat-move for a signed-in person (email)."
  [^js req ^js res person email]
  (if (not= "POST" (.-method req))
    (do (.setHeader res "allow" "POST") (io/send-json res 405 {:error "POST only"}))
    (-> (io/read-json req 65536)
        (.then (fn [{:keys [chat from to folder] :as b}]
                 (if-let [why (cm/request-problem b (map :name (config/servers-of person)))]
                   (io/send-json res 400 {:error why})
                   (-> (move! {:from (config/server-of person from) :to (config/server-of person to)
                              :email email :chat chat :folder folder})
                       (.then (fn [{there :folder}]
                                (js/console.log (str "move " chat ": " from " -> " to " by " email))
                                (io/send-json res 200 {:ok true :server to :chat chat :folder there})))
                       (.catch (fn [e]
                                 (js/console.log (str "move " chat ": " from " -> " to " given up: " (ex-message e)))
                                 (io/send-json res 409 {:error (ex-message e)})))))))
        (.catch (fn [e] (when-not (.-headersSent res) (io/send-json res 400 {:error (.-message e)})))))))
