(ns claudia.web.voice-back
  "Listen back to dictated messages (README decision 20): a sent message's clips stay on this
  device (claudia.web.clip-store, store sounds) for 30 minutes after they were spoken, so its
  sender can hear whether the voice or the dictation went wrong. Never sent anywhere: other
  devices and other people never have them. Swept on start and every minute."
  (:require [re-frame.core :as rf]
            [reagent.core :as r]
            [claudia.web.clip-store :as store]
            [claudia.web.sound :as sound]
            [claudia.web.views.pure.voice-back :as pure]))

;; id -> the clip's sound (a Blob), for the sounds still in time.
(defonce ^:private blobs (atom {}))
;; {:msg id :audio Audio :url object URL} while a message plays.
(defonce playing (r/atom nil))

(defn- sweep
  "Splits sounds into the ones in time and the ids whose time is up."
  [sounds now]
  [(remove #(pure/expired? % now) sounds) (map :id (filter #(pure/expired? % now) sounds))])

(defn refresh!
  "Reads the kept sounds: expired ones are deleted, the rest given to the page by message."
  []
  (.then (store/kept-sounds)
         (fn [sounds]
           (let [now (js/Date.now)
                 [live gone] (sweep sounds now)]
             (doseq [id gone] (store/drop-sound! id))
             (reset! blobs (into {} (map (juxt :id :blob)) live))
             (rf/dispatch [:voice-back/set (pure/by-msg live now)])))))

(rf/reg-event-db :voice-back/set (fn [db [_ m]] (assoc db :voice-back m)))
(rf/reg-sub :voice-back/of (fn [db [_ msg]] (get-in db [:voice-back msg])))
(rf/reg-fx :voice-back/tag (fn [[ids msg]] (.then (store/tag! ids msg) refresh!)))

(defn stop! []
  (sound/release! ::listen-back)
  (when-let [{:keys [^js audio url]} @playing]
    (.pause audio)
    (js/URL.revokeObjectURL url)
    (reset! playing nil)))

(defn- play-from!
  "Plays clip ids of message msg one after the other, from the first."
  [msg ids]
  (if-let [blob (some->> (first ids) (get @blobs))]
    (let [url (js/URL.createObjectURL blob)
          audio (js/Audio. url)]
      (sound/claim! ::listen-back stop!)
      (reset! playing {:msg msg :audio audio :url url})
      (set! (.-onended audio) (fn [] (js/URL.revokeObjectURL url) (play-from! msg (rest ids))))
      (.catch (.play audio) (fn [_] (stop!))))
    (reset! playing nil)))

(defn toggle!
  "Plays message msg's clips (stopping whatever plays), or stops it when it is the one playing."
  [msg clips]
  (let [was (:msg @playing)]
    (stop!)
    (when-not (= was msg) (play-from! msg (map :id clips)))))

(defonce ^:private sweeping (atom nil))

(defn start!
  "The page started: kept sounds read and swept now and every minute."
  []
  (when-not @sweeping
    (refresh!)
    (reset! sweeping (js/setInterval refresh! 60000))))
