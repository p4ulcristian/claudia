(ns claudia.web.views.pure.keys
  "Settings → Keys as data (README decision 66): the ticks the broker keeps, the ticks an admin
  changed but has not saved yet, and the batch that goes to the broker.")

(defn ticked
  "The saved ticks as a set of [item email]."
  [grants]
  (set (map (juxt :item :email) grants)))

(defn on?
  "Whether a box shows ticked: the unsaved change if there is one, else the saved tick."
  [saved changes item email]
  (let [k [item email]]
    (if (contains? changes k) (get changes k) (contains? saved k))))

(defn toggle
  "The unsaved changes after clicking a box; a change back to the saved state drops out."
  [saved changes item email]
  (let [k [item email]
        want (not (on? saved changes item email))]
    (if (= want (contains? saved k)) (dissoc changes k) (assoc changes k want))))

(defn batch
  "The unsaved changes as the broker takes them: [{:item :email :on}], in a stable order."
  [changes]
  (mapv (fn [[[item email] v]] {:item item :email email :on v}) (sort-by key changes)))

(defn others
  "The people an admin ticks keys for: everyone but the broker's admins (they use every key)."
  [people admins]
  (let [a (set admins)]
    (vec (remove #(a (:email %)) people))))
