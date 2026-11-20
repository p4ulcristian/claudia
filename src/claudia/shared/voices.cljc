(ns claudia.shared.voices
  "The chats' voices (README decision 21): each a name and the description the hub's voice service
  makes ONE reference clip from (claudia.hub.narrate), so every line of a voice is the same person.
  A chat's voice is its id; \"\" is Off. Chats that had talking mode on before voices got the first.")

(def all
  "The voices, in the voice pill's order."
  [{:id "nora" :name "Nora" :description "A calm woman in her thirties, warm and clear, unhurried."}
   {:id "bence" :name "Bence" :description "A friendly man in his thirties, warm mid-range voice, relaxed."}
   {:id "zita" :name "Zita" :description "A bright young woman, quick and lively, smiling as she speaks."}
   {:id "otto" :name "Otto" :description "An older man with a deep, slow, steady voice."}
   {:id "lena" :name "Lena" :description "A soft-spoken woman, gentle and quiet, close to the microphone."}
   {:id "max" :name "Max" :description "A young man, energetic and crisp, a radio presenter's pace."}])

(def default-id "The voice a chat with talking mode on got when voices came." "nora")

(defn by-id
  "The voice with this id, or nil (\"\", nil or an unknown id: Off)."
  [id]
  (some #(when (= id (:id %)) %) all))

(defn voice-id
  "id when it names a voice, else \"\" (Off)."
  [id]
  (if (by-id id) id ""))
