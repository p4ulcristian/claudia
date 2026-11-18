(ns claudia.slave.folders.trash
  "Claudia's own Trash on this server (README decision 63): a folder moved to Trash goes to
  RUNNER_DATA/trash/<id>/<its name> with a row in the runner's store (where it was, when, its size,
  who). Restore moves it back, and refuses while something sits at its old path. After 7 days it
  goes for good (checked every hour); Delete now skips the Trash. Every answer is a promise of the
  reply ({:error :status} or the body)."
  (:require ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [claudia.slave.folders.disk :as disk]
            [claudia.slave.folders.rules :as rules]
            [claudia.slave.store :as store]))

(def ^:private fsp (.-promises fs))
(def ^:private purge-every-ms (* 60 60 1000))

(defn- os-error
  "A rejected OS error about p as a reply."
  [p]
  (fn [^js e] (rules/error-reply (.-code e) (.-message e) p)))

(defn- refused [real why]
  (js/Promise.resolve {:status 403 :error (str "Claudia never deletes " real ": " why ".")}))

(defn- kept!
  "The row of a folder now in the Trash (moved: where it is now); a size not known yet is measured
  there."
  [item moved]
  (store/add-trash! (assoc item :name (path/basename (:path item))))
  (disk/forget-size! (:path item))
  (when-not (:size item)
    (.then (disk/du! moved) #(when % (store/set-trash-size! (:id item) %)))))

(defn- trash-failed
  "The reply when moving item's folder to the Trash (dir) failed with e: a whole copy whose original
  could not all be removed stays in the Trash (so it can be restored); else the Trash's folder goes."
  [^js e dir item]
  (let [reply (rules/error-reply (.-code e) (.-message e) (:path item))]
    (if (.-copied e)
      (do (kept! item (:moved item)) (update reply :error str " (a whole copy is in the Trash; what could not be removed is still at the old path)"))
      (.then (.rm fsp dir #js {:recursive true :force true}) (constantly reply)))))

(defn trash!
  "Moves the folder real (symlinks followed) to the Trash for by (an email); kept: the folders never
  deleted (rules/delete-refusal) and :data (RUNNER_DATA)."
  [real kept by]
  (if-let [why (rules/delete-refusal real kept)]
    (refused real why)
    (let [id (crypto/randomUUID)
          {:keys [dir moved]} (rules/trash-place (:data kept) id (path/basename real))
          size (:size (disk/size real))]
      (-> (.mkdir fsp dir #js {:recursive true})
          (.then #(disk/move! real moved))
          (.then (fn [_] (kept! {:id id :path real :at (js/Date.now) :size size :by by} moved) {:ok true :id id}))
          (.catch (fn [^js e] (trash-failed e dir {:id id :path real :at (js/Date.now) :size size :by by :moved moved})))))))

(defn delete-now!
  "Deletes the folder real for good, without the Trash."
  [real kept]
  (if-let [why (rules/delete-refusal real kept)]
    (refused real why)
    (-> (disk/remove! real)
        (.then (fn [_] (disk/forget-size! real) {:ok true}))
        (.catch (os-error real)))))

(defn listed
  "The Trash as the page gets it: {:items :total (bytes)}."
  []
  (let [items (store/trash-items)]
    {:items (mapv rules/trash-row items) :total (rules/trash-total items)}))

(defn restore!
  "Moves trashed item id back where it was (its parent made when gone); refuses while something
  sits there."
  [data id]
  (let [{item-path :path item-name :name :as item} (store/trash-item id)
        {:keys [dir moved]} (when item (rules/trash-place data id item-name))]
    (cond
      (not item) (js/Promise.resolve {:status 404 :error "No such item in the Trash."})
      (disk/there? item-path) (js/Promise.resolve {:status 409 :error (str "Something already sits at " item-path "; move it first.")})
      :else (-> (.mkdir fsp (path/dirname item-path) #js {:recursive true})
                (.then #(disk/move! moved item-path))
                (.then #(.rm fsp dir #js {:recursive true :force true}))
                (.then (fn [_] (store/drop-trash! id) (disk/forget-size! item-path) {:ok true :path item-path}))
                (.catch (os-error item-path))))))

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

(defn purge!
  "Deletes trashed item id for good, or (id nil) empties the whole Trash."
  [data id]
  (cond
    (nil? id) (purge-each! data (store/trash-items))
    (store/trash-item id) (purge-one! data id)
    :else (js/Promise.resolve {:status 404 :error "No such item in the Trash."})))

(defn- purge-expired! [data]
  (-> (purge-each! data (rules/expired (store/trash-items) (js/Date.now)))
      (.then #(when (:error %) (js/console.error "trash purge:" (:error %))))))

(defn start!
  "The purge job: items older than 7 days go, now and every hour."
  [data]
  (purge-expired! data)
  (js/setInterval #(purge-expired! data) purge-every-ms))
