(ns claudia.shared.sounds
  "The chats' notify sounds (README decision 90): each chat whose bell is on rings one of these
  when it finishes or waits on you, made by the page with Web Audio (claudia.web.alerts, no sound
  files) and pitched by the chat's colour. A chat keeps its sound's id beside its bell (the Row's
  `sound`); one that names none, or an unknown one, rings the Bell, so an old `bell: true` reads as
  the Bell.")

(def all
  "The sounds, in the notify pill's order."
  [{:id "bell" :name "Bell"}
   {:id "marimba" :name "Marimba"}
   {:id "chime" :name "Chime"}
   {:id "drop" :name "Drop"}
   {:id "knock" :name "Knock"}])

(def default-id "The sound of a chat that names none." "bell")

(defn by-id
  "The sound with this id, or nil."
  [id]
  (some #(when (= id (:id %)) %) all))

(defn sound-id
  "id when it names a sound, else the Bell's."
  [id]
  (if (by-id id) id default-id))

(defn partials
  "The partials [mult level] of a note at f Hz that the page plays: those under 10 kHz (a high
  colour's note keeps its fundamental and drops what would only hiss)."
  [f parts]
  (filterv (fn [[mult]] (< (* mult f) 10000)) parts))

(defn octave-under
  "f Hz moved down by whole octaves until it is under top Hz (a gliding or knocked sound stays soft
  on a high colour's note, still on that note's pitch)."
  [f top]
  (if (or (< f top) (<= f 0)) f (recur (/ f 2) top)))

(defn notify-name
  "The notify pill's value for a pick: the sound's name, or Off (\"\" or an unknown id)."
  [id]
  (or (:name (by-id id)) "Off"))
