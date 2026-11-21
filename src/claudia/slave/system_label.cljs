(ns claudia.slave.system-label
  "System's proposals (README decision 96): a chat's system_label tool proposes a project name for
  a unit, container, port or program on System; the runner keeps it (system_proposals) until the
  person accepts it (the page writes the hub's label) or says no. Nothing a chat proposes applies by
  itself. Also the front's lookup of chats by their key's hash (POST /api/system/chats), which names
  the chats' processes on System."
  (:require [clojure.string :as str]
            [claudia.node.crypto :as crypto]
            [claudia.node.http :as io]
            [claudia.slave.folders :as folders]
            [claudia.slave.store :as store]))

(def kinds ["unit" "container" "port" "process"])

(def mcp
  "The tool, in every chat's workspace server."
  {:system_label
   {:description "Propose a project name for something on Claudia's System sheet (a service unit, a container, a port or a program) that shows as unknown or wrong. The user sees the proposal on System and accepts it or says no; nothing changes until then. Use it when the user asks, or when you found out for certain what something belongs to."
    :inputSchema {:type "object"
                  :additionalProperties false
                  :required ["kind" "match" "project" "why"]
                  :properties
                  (array-map
                   :server {:type "string" :description "The server it is on, or \"*\" for every server (default this server)"}
                   :kind {:type "string" :enum kinds :description "What it is"}
                   :match {:type "string" :description "The unit's, container's or program's name, or the port number"}
                   :project {:type "string" :description "The project it belongs to"}
                   :why {:type "string" :description "One line for the user: how you know"})}}})

(def allowed ["mcp__workspace__system_label"])

(def prompt
  "Its line in the system prompt."
  "## System
Claudia's System sheet groups every port, service, container and process by project, from labels the user accepts. When something shows as unknown and you know for certain what it belongs to, propose a label with mcp__workspace__system_label; the user accepts it or says no on System.")

(defn- text [v] (str/trim (str (or v ""))))

(defn proposal
  "A system_label call's args as a proposal for chat at time at ({:chat :server :kind :match
  :project :why :at}), or {:error}. here: this server's name, \"\" when the runner does not know it
  (a runner of an agent user has no hub link): the page reads it as the server it asked."
  [args chat here at]
  (let [server (if (str/blank? (:server args)) here (text (:server args)))
        [kind match project why] (map #(text (get args %)) [:kind :match :project :why])]
    (cond (not (some #{kind} kinds)) {:error (str "kind must be one of " (str/join ", " kinds) ".")}
          (or (str/blank? match) (> (count match) 120)) {:error "match must be 1 to 120 characters."}
          (or (str/blank? project) (> (count project) 120)) {:error "project must be 1 to 120 characters."}
          (> (count server) 64) {:error "server is too long."}
          :else {:chat chat :server server :kind kind :match match :project project :why (subs why 0 (min 500 (count why))) :at at})))

(defn proposed-text
  "What the chat is told once its proposal is kept."
  [{:keys [server kind match project]}]
  (str "Proposed: " kind " " match " on " (if (str/blank? server) "this server" server) " belongs to " project
       ". The user accepts it or says no on System."))

(defn listing
  "Proposals with their chat ({id title colour}, from chat-of id) for the page."
  [rows chat-of]
  (vec (for [r rows]
         (assoc (select-keys r [:id :server :kind :match :project :why :at])
                :chat (or (chat-of (:chat r)) {:id (:chat r) :title "" :colour nil})))))

(defn chats-by-hash
  "The chats whose key hashes are in hashes: {hash {:id :title :colour :running}}, live?: whether a
  chat's session is live in the host."
  [chats hashes live?]
  (let [wanted (set hashes)]
    (into {} (for [c chats
                   :let [h (when (seq (:chat-key c)) (crypto/token-hash (:chat-key c)))]
                   :when (and h (wanted h))]
               [h {:id (:id c) :title (:title c) :colour (:color c) :running (boolean (live? (:id c)))}]))))

(defn- chat-of [id] (when-let [c (store/chat id)] {:id (:id c) :title (:title c) :colour (:color c)}))

(defn- chats-call [^js req ^js res live?]
  (-> (io/read-json req (* 256 1024))
      (.then (fn [body]
               (io/send-json res 200 {:chats (chats-by-hash (store/chats) (filter string? (:keys body)) live?)})))))

(defn handle
  "Answers System's runner calls (true), else nil: the proposals (the profile's own person only) and
  the front's chats lookup. who: the caller ({:via}); live?: (fn [chat id]) its session is live."
  [^js req ^js res url who live?]
  (let [method (.-method req)
        [_ id] (re-matches #"^/api/system/proposals/(\d+)$" url)]
    (cond (and (= url "/api/system/chats") (= "POST" method)) (do (chats-call req res live?) true)
          (not (or (= url "/api/system/proposals") id)) nil
          (not (folders/own? who)) (do (io/send-json res 403 {:error "Proposals are the profile's own person's only."}) true)
          (and (= url "/api/system/proposals") (= "GET" method))
          (do (io/send-json res 200 {:proposals (listing (store/proposals) chat-of)}) true)
          (and id (= "DELETE" method))
          (do (store/drop-proposal! (js/parseInt id 10)) (io/send-json res 200 {:ok true}) true)
          :else (do (io/send-json res 405 {:error "GET the proposals, DELETE one"}) true))))
