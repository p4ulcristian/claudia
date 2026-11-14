(ns claudia.web.views.pure.switches
  "A chat's speaker and bell (README decision 21), kept with the chat: its Row's `talk` and `bell`
  for a started chat (k = [server id]), the new chat's picks (`:new-settings`) for k nil. The
  speaker starts off and the bell on: a Row from a runner that sends neither reads that way.")

(defn- row-of [db [server id]] (get-in db [:servers server :rows id]))

(defn- switch-of [db k field]
  (get (if k (row-of db k) (:new-settings db)) field))

(defn talk-on?
  "Whether the chat's speaker is on (k nil: the new chat's pick)."
  [db k]
  (true? (switch-of db k :talk)))

(defn bell-on?
  "Whether the chat's bell is on (k nil: the new chat's pick)."
  [db k]
  (not (false? (switch-of db k :bell))))

(defn row-bell-on?
  "Whether a Row's bell is on."
  [row]
  (not (false? (:bell row))))

(defn with-switches
  "db with the chat's switches m ({:talk :bell}, those set) laid over: its Row, or the new chat's picks."
  [db [server id :as k] m]
  (if k
    (update-in db [:servers server :rows id] merge m)
    (update db :new-settings merge m)))
