(ns claudia.web.views.pure.switches
  "A chat's voice, bell and sound (README decisions 21, 90), kept with the chat: its Row's `voice`,
  `bell` and `sound` for a started chat (k = [server id]), the new chat's picks (`:new-settings`)
  for k nil. The voice starts Off (\"\"), the bell on, the sound the Bell: a Row from a runner that
  sends none of them reads that way."
  (:require [claudia.shared.sounds :as sounds]
            [claudia.shared.voices :as voices]))

(defn- row-of [db [server id]] (get-in db [:servers server :rows id]))

(defn- switch-of [db k field]
  (get (if k (row-of db k) (:new-settings db)) field))

(defn voice-of
  "The chat's voice id, \"\" when Off (k nil: the new chat's pick)."
  [db k]
  (voices/voice-id (switch-of db k :voice)))

(defn bell-on?
  "Whether the chat's bell is on (k nil: the new chat's pick)."
  [db k]
  (not (false? (switch-of db k :bell))))

(defn row-bell-on?
  "Whether a Row's bell is on."
  [row]
  (not (false? (:bell row))))

(defn row-sound
  "The sound a Row rings: its sound's id, the Bell when it names none."
  [row]
  (sounds/sound-id (:sound row)))

(defn notify-of
  "The chat's notify pick (README decision 90): its sound's id while its bell is on, \"\" (Off)
  while it is off (k nil: the new chat's pick)."
  [db k]
  (if (bell-on? db k) (sounds/sound-id (switch-of db k :sound)) ""))

(defn notify-switches
  "The switches a notify pick sets: a sound id turns the bell on with that sound; \"\" (Off) turns
  the bell off and keeps the sound for later."
  [id]
  (if (= "" id) {:bell false} {:bell true :sound (sounds/sound-id id)}))

(defn root-on?
  "Whether the chat's root switch is on (README decision 52); a new chat has none."
  [db k]
  (boolean (and k (true? (:root (row-of db k))))))

(defn with-switches
  "db with the chat's switches m ({:voice :bell :sound}, those set) laid over: its Row, or the new chat's picks."
  [db [server id :as k] m]
  (if k
    (update-in db [:servers server :rows id] merge m)
    (update db :new-settings merge m)))
