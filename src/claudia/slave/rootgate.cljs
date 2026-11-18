(ns claudia.slave.rootgate
  "The front's word on root (README decision 52), as data: the proof of a person's tap lives in the
  front (user claudia, which an agent user cannot reach), never in a runner (which runs as the agent
  user). Its state: {:switches {\"<profile>/<chat>\" true} :pending {nonce {:profile :chat :argv
  :cwd}}}. The edges are in claudia.slave.rootfront."
  (:require [claudia.root.rules :as rules]))

(defn switch-key [profile chat] (str profile "/" chat))

(defn switch-on?
  "Whether the front holds the root switch of profile's chat on."
  [switches profile chat]
  (true? (get switches (switch-key profile chat))))

(defn with-switch
  "switches with profile's chat turned on or off (off is left out)."
  [switches profile chat on?]
  (if on? (assoc switches (switch-key profile chat) true) (dissoc switches (switch-key profile chat))))

(defn owner?
  "Whether the caller (the front's route of a hub call: {:own :target}) is the target profile's own
  person, not one with a write tick or a viewer."
  [{:keys [own target]}]
  (boolean (and own target (= (:profile own) (:profile target)))))

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
  "The root helper's answer for chat: allow (true: the person tapped Allow), or the why of a no."
  [chat allow why]
  (if allow
    {:allow true :via "allow" :chat chat}
    {:allow false :why why :chat chat}))
