(ns claudia.web.world.state
  "The world mode's state in app-db, as pure functions (claudia.web.world registers them): whether
  the world is open, which panel is beside it (:world-panel: a robot's chat or a server's Systems
  card), what a click in it does, which robot the view follows and what its keys do.")

(defn toggled
  "db with the world opened or closed (open? nil: switched); closing it closes its panel too."
  [db open?]
  (let [on? (if (nil? open?) (not (:world? db)) open?)]
    (cond-> (assoc db :world? on?) (not on?) (dissoc :world-panel))))

(defn pick-fx
  "What a click in the world does: a robot (or its screen) opens its chat in the panel; an island's
  board opens its server's Systems card; anything else closes an open panel; else a server's island
  starts a new chat there; the gate's island and the sky do nothing. pick: nil for the sky."
  [db {:keys [robot board island kind]}]
  (cond
    robot {:db (assoc db :world-panel {:kind :chat :target (:id robot)})
           :fx [[:dispatch [:nav/open (:key robot)]]]}
    board {:db (assoc db :world-panel {:kind :stats :target board})}
    (:world-panel db) {:world/close-panel true}
    (and island (= kind "server")) {:db (assoc db :world-panel {:kind :chat})
                                    :fx [[:dispatch [:new/server island]] [:dispatch [:nav/new]]]}
    :else {}))

(defn focus-id
  "The robot the view follows: the open chat's, while its panel is open."
  [db]
  (let [[s c] (:open db)]
    (when (and (= :chat (get-in db [:world-panel :kind])) c) (str s "/" c))))

(defn stats-url
  "Where an island's Systems numbers come from: the gate's own, or a server's."
  [island]
  (if (= island "gate") "/hub/gate/system" (str "/hub/servers/" (js/encodeURIComponent island) "/system")))

(defn key-action
  "What a key does: :toggle (Alt+W), :back (Escape while the world is open), else nil."
  [{:keys [alt? code]} world?]
  (cond (and alt? (= "KeyW" code)) :toggle
        (and world? (= "Escape" code)) :back))
