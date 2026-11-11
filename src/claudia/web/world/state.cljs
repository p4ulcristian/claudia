(ns claudia.web.world.state
  "The world mode's state in app-db, as pure functions (claudia.web.world registers them): whether
  the world and its chat panel are open, what a click in it does, which robot the view follows and
  what its keys do.")

(defn toggled
  "db with the world opened or closed (open? nil: switched); closing it closes the chat panel too."
  [db open?]
  (let [on? (if (nil? open?) (not (:world? db)) open?)]
    (cond-> (assoc db :world? on?) (not on?) (assoc :world-chat? false))))

(defn pick-fx
  "What a click in the world does: a robot opens its chat in the panel, a server's island starts a
  new chat there; the gate does nothing."
  [db {:keys [robot island kind]}]
  (cond
    robot {:db (assoc db :world-chat? true) :fx [[:dispatch [:nav/open (:key robot)]]]}
    (and island (= kind "server")) {:db (assoc db :world-chat? true)
                                    :fx [[:dispatch [:new/server island]] [:dispatch [:nav/new]]]}
    :else {}))

(defn focus-id
  "The robot the view follows: the open chat's, while its panel is open."
  [db]
  (let [[s c] (:open db)]
    (when (and (:world-chat? db) c) (str s "/" c))))

(defn key-action
  "What a key does: :toggle (Alt+W), :back (Escape while the world is open), else nil."
  [{:keys [alt? code]} world?]
  (cond (and alt? (= "KeyW" code)) :toggle
        (and world? (= "Escape" code)) :back))
