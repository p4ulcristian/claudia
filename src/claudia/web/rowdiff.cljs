(ns claudia.web.rowdiff
  "What changed in the chat lists between two states of app-db, as [kind [server chat] row]: a chat
  :started or :done with a turn, started :waiting on you, came :new or is :gone (removed or
  archived). Sounds and popups (claudia.web.alerts) and Claudia's face (claudia.web.face) both
  read it.")

(defn keyed-rows
  "Every chat row of every server as [[server chat] row]."
  [db]
  (for [[s {:keys [rows]}] (:servers db) [id row] rows] [[s id] row]))

(defn- turn-kind
  "How one chat's turn moved between was and row (both present)."
  [was row]
  (cond (and (:waiting row) (not (:waiting was))) :waiting
        (and (:running was) (not (:running row)) (not (:waiting row))) :done
        (and (:running row) (not (:running was))) :started
        (and (:archived row) (not (:archived was))) :gone))

(defn- arrivals
  "Chats in after that before did not have, on servers whose list before had already loaded (so the
  first load of a list is not a crowd of new chats)."
  [before after was-rows]
  (for [[[s _ :as k] row] (keyed-rows after)
        :when (and (not (contains? was-rows k)) (seq (get-in before [:servers s :rows])))]
    [:new k row]))

(defn- departures
  "Chats in before that after no longer has, while their server's list is still there."
  [before after]
  (let [now-rows (into {} (keyed-rows after))]
    (for [[[s _ :as k] row] (keyed-rows before)
          :when (and (not (contains? now-rows k)) (seq (get-in after [:servers s :rows])))]
      [:gone k row])))

(defn row-events
  "Every change between two states of app-db, in list order of after then the departures."
  [before after]
  (let [was-rows (into {} (keyed-rows before))]
    (concat (for [[k row] (keyed-rows after)
                  :let [was (get was-rows k)
                        kind (when was (turn-kind was row))]
                  :when kind]
              [kind k row])
            (arrivals before after was-rows)
            (departures before after))))
