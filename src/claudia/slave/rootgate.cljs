(ns claudia.slave.rootgate
  "The front's word on root (README decision 52), as data: the proof of a person's tap lives in the
  front (user claudia, which an agent user cannot reach), never in a runner (which runs as the agent
  user). Its state: {:switches {\"<profile>/<chat>\" true} :pending {nonce {:profile :chat :argv
  :cwd}}}. The edges are in claudia.slave.rootfront."
  (:require [clojure.string :as str]
            [claudia.root.rules :as rules]))

(defn switch-key [profile chat] (str profile "/" chat))

(defn switch-on?
  "Whether the front holds the root switch of profile's chat on (kept as the email of the person who
  turned it on, or true from before README decision 59)."
  [switches profile chat]
  (let [v (get switches (switch-key profile chat))]
    (boolean (or (true? v) (and (string? v) (seq v))))))

(defn switch-by
  "The email of the person who turned the root switch of profile's chat on, when kept."
  [switches profile chat]
  (let [v (get switches (switch-key profile chat))]
    (when (and (string? v) (seq v)) v)))

(defn with-switch
  "switches with profile's chat turned on (kept as by, the email of the person who did, else true)
  or off (left out)."
  [switches profile chat on? by]
  (if on?
    (assoc switches (switch-key profile chat) (if (and (string? by) (seq by)) by true))
    (dissoc switches (switch-key profile chat))))

(defn owner?
  "Whether the caller (the front's route of a hub call: {:own :target}) is the target profile's own
  person, not one with a write tick or a viewer."
  [{:keys [own target]}]
  (boolean (and own target (= (:profile own) (:profile target)))))

(defn switches-here
  "switches without those of profiles that have no runner here (profiles: the front's list)."
  [switches profiles]
  (let [names (set (map :profile profiles))]
    (into {} (filter (fn [[k _]] (contains? names (first (str/split (str k) #"/" 2))))) switches)))

(defn tap-problem
  "[status body] when a person's tap (an answer or a switch) on chat cannot count, before asking the
  runner whether the chat is there; nil when it may go on. Only the owner taps (route: the caller's)."
  [route chat]
  (cond (not (owner? route)) [403 {:error "Only the chat's owner may give it root."}]
        (not (string? chat)) [400 {:error "chat is needed."}]))

(defn chat-problem
  "[status body] when the runner's answer to POST /api/root/known ({:status :body}) does not say the
  chat is there, else nil. Nothing is kept or answered for a chat the profile does not have."
  [{:keys [status body]}]
  (cond (= 200 status) nil
        (= 404 status) [404 {:error "No such chat."}]
        :else [502 {:error (or (:error body) (str "The runner answered " status "."))}]))

(defn ask-problem
  "Why the root helper's ask {agentUser chatKey argv cwd} cannot go to a runner here (profiles: the
  front's list, [{:profile :socket}]), or nil."
  [{:keys [agentUser] :as req} profiles]
  (let [profile (rules/profile-of agentUser)]
    (cond (not profile) "Not an agent user."
          (not (some #(= profile (:profile %)) profiles)) (str "No profile " profile " on this server.")
          :else (rules/request-problem req))))

(defn answer-problem
  "Why a person's answer {chat ask allow argv cwd} to a \"Run as root\" card does not count, or nil:
  pending is the front's open ask under that nonce (nil: none), route the caller. Allow must name
  the very command and folder the front asked about (the card a runner showed may lie)."
  [pending {:keys [chat allow argv cwd]} route]
  (cond (not (owner? route)) "Only the chat's owner may give it root."
        (nil? pending) "That root ask is no longer open."
        (not= (:profile pending) (get-in route [:target :profile])) "That root ask is another profile's."
        (not= (:chat pending) chat) "That root ask is another chat's."
        (not (true? allow)) nil
        (not= (:argv pending) argv) "The command shown is not the one asked for."
        (not= (:cwd pending) cwd) "The folder shown is not the one asked for."))

(defn verdict-of
  "The root helper's answer for chat: allow (true: the person whose email is by tapped Allow), or the
  why of a no."
  [chat allow why by]
  (if allow
    {:allow true :via "allow" :chat chat :by by}
    {:allow false :why why :chat chat}))
