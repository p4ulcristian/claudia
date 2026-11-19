(ns claudia.slave.folders.trash
  "What is left of Claudia's Trash on this server (README decision 63, ended by decision 69: Folders
  no longer delete or trash anything). Folders moved there before sit in RUNNER_DATA/trash/<id>/
  with a row in the runner's store; each goes for good 7 days after it was trashed (checked at start
  and every hour), so the Trash empties itself."
  (:require ["node:fs" :as fs]
            [claudia.slave.folders.rules :as rules]
            [claudia.slave.store :as store]))

(def ^:private fsp (.-promises fs))
(def ^:private purge-every-ms (* 60 60 1000))

(defn- os-error
  "A rejected OS error about p as a reply."
  [p]
  (fn [^js e] (rules/error-reply (.-code e) (.-message e) p)))

(defn- purge-one!
  "Promise: trashed item id gone for good (its folder and its row; a folder already gone is fine)."
  [data id]
  (let [{:keys [dir]} (rules/trash-place data id "")]
    (-> (.rm fsp dir #js {:recursive true :force true})
        (.then (fn [_] (store/drop-trash! id) {:ok true}))
        (.catch (os-error dir)))))

(defn- purge-each!
  "Promise: the items purged one after another; the first failure's reply, else {:ok true}."
  [data items]
  (reduce (fn [p {:keys [id]}] (.then p #(if (:error %) % (purge-one! data id))))
          (js/Promise.resolve {:ok true})
          items))

(defn- purge-expired! [data]
  (-> (purge-each! data (rules/expired (store/trash-items) (js/Date.now)))
      (.then #(when (:error %) (js/console.error "trash purge:" (:error %))))))

(defn start!
  "The purge job: items older than 7 days go, now and every hour."
  [data]
  (purge-expired! data)
  (js/setInterval #(purge-expired! data) purge-every-ms))
