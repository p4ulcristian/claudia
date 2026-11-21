(ns claudia.hub.listen-prefs
  "A person's dictation choices in the hub's store (README decision 94): the words they said No to
  when the chat offered them (never offered again), the words they added from the chat (marked in
  Settings), and whether the chat offers words at all. GET/PUT /hub/listen/prefs (claudia.hub.ops),
  the same on every device; the word lists are cleaned like My words (claudia.hub.listen-words)."
  (:require [claudia.hub.listen-words :as listen-words]
            [claudia.hub.store :as store]
            [claudia.shared.protocol :as protocol]))

(def blank "Before any choice: nothing declined or learned, offers on."
  {:declined [] :learned [] :offers true})

(defn- clean-words [ws]
  (let [{:keys [words]} (listen-words/checked-words (if (sequential? ws) (filter string? ws) []))]
    (or words [])))

(defn clean
  "Prefs from what was stored or sent: the two word lists cleaned (bad entries and repeats dropped),
  offers a boolean (on unless it is false)."
  [m]
  {:declined (clean-words (:declined m))
   :learned (clean-words (:learned m))
   :offers (not (false? (:offers m)))})

(defn of
  "A person's prefs as the page gets them."
  [email]
  (if-let [{:keys [prefs]} (store/listen-prefs email)]
    (clean (protocol/read-json prefs))
    blank))

(defn save!
  "Keeps m as the person's whole prefs, cleaned; the prefs as kept."
  [email m]
  (store/save-listen-prefs! email (protocol/write-json (clean m)))
  (of email))
