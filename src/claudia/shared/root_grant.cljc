(ns claudia.shared.root-grant
  "A root grant (README decision 79): the key broker's signed word that a person's passkey approved
  root for one chat. The front makes a one-time nonce for {profile chat action ask}; the person's
  passkey signs a broker challenge bound to it; the broker signs the grant (ed25519) and the front
  checks it with the broker's public key pinned on the server. Pure: what a grant holds, what makes
  a purpose, and why a grant does not count."
  (:require [clojure.string :as str]))

(def grant-ms "How long a grant (and the front's nonce it answers) holds." (* 5 60 1000))

(def actions "What a grant is for: turning the chat's root switch on, or one Allow." #{"on" "allow"})

(defn nonce?
  "A front's one-time nonce: 32 lower-case hex characters (16 random bytes)."
  [s]
  (boolean (and (string? s) (re-matches #"^[0-9a-f]{32}$" s))))

(defn- short-text? [s] (and (string? s) (<= 1 (count s) 200)))

(defn purpose
  "What approving root is for, from the page's body: {:kind \"root\" :nonce :profile :chat :action
  :ask}, or {:error text}. ask (the \"Run as root\" card's nonce) only for allow."
  [{:keys [nonce profile chat action ask]}]
  (cond
    (not (nonce? nonce)) {:error "nonce (the front's, 32 hex) is needed."}
    (not (short-text? profile)) {:error "profile is needed."}
    (not (short-text? chat)) {:error "chat is needed."}
    (not (contains? actions action)) {:error "action must be on or allow."}
    (and (= "allow" action) (not (short-text? ask))) {:error "ask (the root card's) is needed for allow."}
    :else (cond-> {:kind "root" :nonce nonce :profile profile :chat chat :action action}
            (= "allow" action) (assoc :ask ask))))

(defn grant
  "The grant the broker signs for an approved root purpose: who approved it and until when."
  [{:keys [nonce profile chat action ask]} email now]
  (cond-> {:v 1 :kind "root" :email email :nonce nonce :profile profile :chat chat :action action
           :expires (+ now grant-ms)}
    ask (assoc :ask ask)))

(defn- tap-fields
  "What a tap is about, nil fields left out (an on has no ask)."
  [m]
  (into {} (filter (comp some? val)) (select-keys m [:profile :chat :action :ask])))

(defn problem
  "Why grant g (the broker's, its signature already checked) does not let tap {:profile :chat
  :action :ask} through, or nil. expected: the front's open nonce entry {:profile :chat :action :ask
  :expires} under g's nonce (nil: none open); owner: the target profile's email from the server's
  profiles file (never the hub's word)."
  [g expected tap owner now]
  (cond
    (not (and (map? g) (= 1 (:v g)) (= "root" (:kind g)) (number? (:expires g)))) "That is not a root grant."
    (nil? expected) "That passkey approval is not open any more. Try again."
    (or (< (:expires g) now) (< (:expires expected) now)) "That passkey approval has ended. Try again."
    (not= (tap-fields expected) (tap-fields tap)) "That passkey approval is for something else."
    (not= (tap-fields g) (tap-fields tap)) "That passkey approval is for something else."
    (not (and (string? owner) (= (str/lower-case owner) (:email g))))
    "Only the chat's owner's passkey gives it root."))

(defn open-nonce
  "The front's open nonces with one more: nonce -> {:profile :chat :action :ask :expires}; ended
  ones dropped on the way."
  [nonces nonce tap now]
  (-> (into {} (remove (fn [[_ e]] (< (:expires e) now))) nonces)
      (assoc nonce (assoc (tap-fields tap) :expires (+ now grant-ms)))))
