(ns claudia.web.pictures
  "Picture history (the stepper), the right half's view (Picture | Timeline) and its fold: the frames,
  events and subscriptions. The newest picture is live; an earlier one is fetched once when it is
  looked at, and the picture shown until then stays."
  (:require [re-frame.core :as rf]
            [claudia.web.db :as db]
            [claudia.web.events :as events]))

;; ---- frames ----

(defmethod events/on-frame "pictures" [db server {:keys [chat pictures]}]
  (let [k [server chat]
        turns (set (map :turn pictures))]
    {:db (cond-> (assoc-in db [:pictures k] (vec pictures))
           ;; The picture looked at is gone (or became the live one): back to live.
           (let [v (get-in db [:viewing k])]
             (and v (or (not (turns v)) (= v (:turn (last pictures))))))
           (update :viewing dissoc k))}))

(defmethod events/on-frame "picture" [db server {:keys [chat turn scene]}]
  {:db (assoc-in db [:past-scenes [server chat turn]] (or scene {:title "" :blocks []}))})

;; ---- stepping ----

(defn- index-of [pictures turn]
  (first (keep-indexed (fn [i p] (when (= turn (:turn p)) i)) pictures)))

(defn shown-index
  "The index of the picture shown: the one looked at, else the newest; -1 when there is none."
  [pictures viewing]
  (or (when viewing (index-of pictures viewing)) (dec (count pictures))))

(rf/reg-event-fx
 :picture/pick
 (fn [{:keys [db]} [_ i]]
   (let [[s c :as k] (:open db)
         pictures (get-in db [:pictures k])
         n (count pictures)]
     (cond
       (or (nil? c) (not (< -1 i n))) {}
       (= i (dec n)) {:db (update db :viewing dissoc k)}
       :else
       (let [turn (:turn (nth pictures i))]
         (cond-> {:db (assoc-in db [:viewing k] turn)}
           (not (contains? (:past-scenes db) [s c turn]))
           (assoc :ws/send [s {:type "picture" :chat c :turn turn}])))))))

(rf/reg-event-fx
 :picture/step
 (fn [{:keys [db]} [_ delta]]
   (let [k (:open db)
         pictures (get-in db [:pictures k])
         i (shown-index pictures (get-in db [:viewing k]))]
     {:fx [[:dispatch [:picture/pick (+ i delta)]]]})))

(rf/reg-event-db
 :picture/live
 (fn [db _] (update db :viewing dissoc (:open db))))

(rf/reg-event-fx
 :right/view
 ;; v: :picture, :timeline, or nil to switch.
 (fn [{:keys [db]} [_ v]]
   (let [v (or v (if (= :timeline (:right-view db)) :picture :timeline))]
     {:db (assoc db :right-view v :view-at (js/Date.now)) :store ["claudia.right" (name v)]})))

(rf/reg-event-fx
 :right/fold
 ;; Desktop: fold or unfold the right half (not while Claude asks). Phone: back to the chat.
 (fn [{:keys [db]} _]
   (let [asks (seq (get-in db [:chats (:open db) :asks]))]
     (cond
       (db/phone?) {:db (assoc db :screen :chat)}
       (and asks (zero? (:folded-at db))) {}
       :else (let [folded (zero? (:folded-at db))]
               {:db (assoc db :folded-at (if folded (js/Date.now) 0))
                :store ["claudia.folded" folded]})))))

;; ---- subscriptions ----

(rf/reg-sub :right-view (fn [db] (:right-view db)))

(rf/reg-sub
 :picture-history
 ;; What the bar and the picture need: the list, which one is shown, and its scene.
 (fn [db]
   (let [[s c :as k] (:open db)
         pictures (get-in db [:pictures k] [])
         viewing (get-in db [:viewing k])
         i (shown-index pictures viewing)
         live (get-in db [:chats k :scene])
         past (when viewing (get-in db [:past-scenes [s c viewing]]))]
     {:pictures pictures
      :index i
      ;; The last turn ended without drawing: its last user message is newer than the newest picture.
      :unchanged? (let [state (get-in db [:chats k])
                        asked (some #(when (= "user" (:role %)) (:at %)) (rseq (vec (:messages state))))]
                    (boolean (and (seq pictures) (not (:running state)) asked
                                  (> asked (:at (peek pictures) 0)))))
      :viewing? (boolean viewing)
      :loading? (boolean (and viewing (nil? past)))
      :scene (or past live)
      :title (or (:title (get pictures i)) (:title live) "")})))

(rf/reg-sub
 :fold-state
 (fn [db]
   (let [k (:open db)
         newest (:at (peek (get-in db [:pictures k] [])) 0)
         asks (seq (get-in db [:chats k :asks]))
         folded-at (:folded-at db)]
     {;; A question unfolds the right half while it is open.
      :folded? (and (pos? folded-at) (not asks))
      :unseen? (> newest folded-at)
      :fold-disabled? (boolean asks)
      :picture-behind? (and (= :timeline (:right-view db)) (> newest (:view-at db)))})))

;; ---- keys: ← → step through the pictures, Alt+T switches the view, Alt+P folds ----

(defn- typing? []
  (let [el js/document.activeElement
        tag (some-> el .-tagName .toLowerCase)]
    (or (#{"input" "textarea" "select"} tag)
        (some-> el .-isContentEditable))))

(defn- on-key [^js e]
  (cond
    (and (.-altKey e) (not (.-ctrlKey e)) (not (.-metaKey e)) (= "KeyT" (.-code e)))
    (do (.preventDefault e) (rf/dispatch [:right/view nil]))

    (and (.-altKey e) (not (.-ctrlKey e)) (not (.-metaKey e)) (= "KeyP" (.-code e)))
    (do (.preventDefault e) (rf/dispatch [:right/fold]))

    (and (#{"ArrowLeft" "ArrowRight"} (.-key e))
         (not (or (.-altKey e) (.-ctrlKey e) (.-metaKey e) (.-shiftKey e)))
         (not (typing?))
         (= :picture @(rf/subscribe [:right-view])))
    (do (.preventDefault e)
        (rf/dispatch [:picture/step (if (= "ArrowLeft" (.-key e)) -1 1)]))))

(defonce ^:private installed (atom false))

(defn install-keys! []
  (when-not @installed
    (reset! installed true)
    (js/window.addEventListener "keydown" on-key)))
