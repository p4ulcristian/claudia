(ns claudia.slave.secrets-list
  "The list_secrets tool (README decision 77): the 1Password keys the chat's person may use, by name
  and with ready references for run_with_secret, never a value. The runner has no login of its own,
  so it asks the person's open Claudia pages (a keys_ask frame to every page of this runner that is
  not read-only); the first page to answer fetches GET /hub/keys/mine (the hub vouches for the
  login, the key broker lists only that person's keys) and sends the list back in a keys frame.
  Nobody presses anything."
  (:require [clojure.string :as str]
            [claudia.slave.chats.answers :as answers]))

(def wait-ms "How long list_secrets waits for a page to answer." 60000)

;; Open asks: ask id -> {:chat :resolve :timer}.
(defonce ^:private pending (atom {}))

;; Sends a frame to every page of this runner that may answer (set by the runner); returns how many.
(defonce send-pages! (atom (constantly 0)))

(defn- text-of [x limit] (when (string? x) (subs x 0 (min limit (count x)))))

(defn clean-list
  "The page's keys answer as data the runner trusts: {:vault :items [{:title :fields [{:label
  :section :ref}]}]} with strings only, at most 500 items of 100 fields; nil when it is not a list."
  [{:keys [vault items]}]
  (when (and (string? vault) (sequential? items))
    {:vault (text-of vault 200)
     :items (vec (for [i (take 500 items) :when (string? (:title i))]
                   {:title (text-of (:title i) 300)
                    :fields (vec (for [f (take 100 (when (sequential? (:fields i)) (:fields i)))
                                       :when (string? (:ref f))]
                                   (cond-> {:label (str (text-of (:label f) 300)) :ref (text-of (:ref f) 1000)}
                                     (string? (:section f)) (assoc :section (text-of (:section f) 300)))))}))}))

(defn- field-line [{:keys [label section ref]}]
  (str "  " ref (when (or (seq section) (not (str/ends-with? ref (str "/" label))))
                  (str "  (" (when (seq section) (str section " / ")) label ")"))))

(defn listing
  "list_secrets' answer: per key its title, then one line per field with its reference (and its
  label when the reference does not end with it)."
  [{:keys [vault items]}]
  (if (empty? items)
    (str "You may use no keys in the vault \"" vault "\" yet: an admin ticks keys for this person in Claudia's Settings → Keys.")
    (str "Keys you may use (vault \"" vault "\"); pass a reference as run_with_secret's ref:\n"
         (str/join "\n" (for [{:keys [title fields]} items]
                          (str title (if (seq fields) (str "\n" (str/join "\n" (map field-line fields))) "  (no fields)")))))))

(defn result
  "What Claude gets for a page's keys frame: the listing, or the error the page met."
  [{:keys [answer error]}]
  (if-let [l (when-not error (clean-list answer))]
    (answers/text-result (listing l))
    (answers/refusal (str "The key list failed: " (or (text-of error 500) "the page sent no list.")))))

(defn answer!
  "A page's keys frame for an open ask of chat: the first one ends the ask; later ones are dropped."
  [chat ask-id frame]
  (when-let [{done :resolve timer :timer} (let [e (get @pending ask-id)] (when (= chat (:chat e)) e))]
    (js/clearTimeout timer)
    (swap! pending dissoc ask-id)
    (done (result frame))))

(defn- expired! [ask-id]
  (when-let [{done :resolve} (get @pending ask-id)]
    (swap! pending dissoc ask-id)
    (done (answers/refusal "No Claudia page answered in 60 seconds: the key list needs Claudia open in a browser or the app, signed in as this chat's person. Try again then."))))

(defn ask!
  "Promise of list_secrets' result for chat: asks the person's open pages, first answer wins."
  [chat]
  (js/Promise.
   (fn [done]
     ;; A page's answer comes on a later tick, so the ask is kept right after the frames go out.
     (let [ask-id (str (random-uuid))]
       (if (zero? (@send-pages! {:type "keys_ask" :chat chat :ask ask-id}))
         (done (answers/refusal "No Claudia page is open for this server: the key list needs Claudia open in a browser or the app, signed in as this chat's person. Try again then."))
         (swap! pending assoc ask-id {:chat chat :resolve done :timer (js/setTimeout #(expired! ask-id) wait-ms)}))))))
