(ns claudia.web.views.flow
  "The flow form: something travelling through a system, one frame at a time. Stations side by side
  (a station may have steps inside); each lane carries one card that slides to the station or step
  it is at; a caption says what happens. Back / next (or left/right while it has focus), and several
  ways through (scenarios) to switch between."
  (:require [clojure.string :as str]
            [reagent.hooks :as h]
            [claudia.web.hooks :as hooks]
            [claudia.web.shiki :as shiki]
            [claudia.web.views.code :as code]))

(def ^:private card-w 260)
(def ^:private items code/items)

(defn token-lines
  "A card's lines as pieces {:s :color}: Shiki's tokens when there are any, else the plain lines."
  [tokens text]
  (if tokens
    (map (fn [^js l] (map (fn [^js t] {:s (.-content t) :color (.-color t)}) (array-seq l))) (array-seq tokens))
    (map (fn [l] [{:s l}]) (str/split text #"\n" -1))))

(defn- cut-piece
  "Piece t (at offset at) cut where the new part [from, to) starts and ends."
  [at t from to]
  (let [len (count (:s t))
        edges (vec (concat [0] (sort (distinct (filter #(and (pos? %) (< % len)) [(- from at) (- to at)]))) [len]))]
    (for [k (range (dec (count edges)))]
      {:s (subs (:s t) (edges k) (edges (inc k))) :color (:color t) :at (+ at (edges k))})))

(defn fresh-pieces
  "Pieces of text with their colour and offset, split where the new part [from, to) starts and
  ends; lines joined by \"\n\" pieces."
  [lines from to]
  (let [n (count lines)]
    (first
     (reduce (fn [[out at] [li line]]
               (reduce (fn [[out at] t] [(into out (cut-piece at t from to)) (+ at (count (:s t)))])
                       [out at] (concat line (when (< li (dec n)) [{:s "\n"}]))))
             [[] 0] (map-indexed vector lines)))))

(defn- card-body
  "A card's text, coloured when it names a language; fresh (a piece of it) is marked as new."
  [text lang fresh]
  (let [tokens (shiki/use-tokens text lang)
        from (if (seq fresh) (or (str/index-of text fresh) -1) -1)
        to (if (neg? from) -1 (+ from (count fresh)))]
    (into [:pre]
          (map-indexed (fn [k p]
                         ^{:key k} [:span {:class (when (and (>= (:at p) from) (< (:at p) to)) "new")
                                           :style (when (:color p) {:color (:color p)})}
                                    (:s p)])
                       (fresh-pieces (token-lines tokens text) from to)))))

;; ---- pure: the frames, the cards, where they stand ----

(defn ways-of
  "The ways through: the scenarios, or one way of the form's frames."
  [data]
  (if (seq (items (:scenarios data)))
    (mapv #(update % :frames items) (items (:scenarios data)))
    [{:id "" :frames (items (:frames data))}]))

(defn lanes-of [data] (if (seq (items (:lanes data))) (items (:lanes data)) [{:id ""}]))

(defn card-of
  "The card in lane on frame fr (a card with no lane is in the first)."
  [lanes lane fr]
  (some #(when (= (or (:lane %) (:id (first lanes))) lane) %) (items (:cards fr))))

(defn station-of
  "The station id is (a station, or a step in one)."
  [stations id]
  (some #(when (or (= (:id %) id) (some (fn [t] (= (:id t) id)) (items (:steps %)))) (:id %)) stations))

(defn lit-stations
  "The stations lit on frame f: where its cards are, and what it turns on."
  [stations f]
  (set (keep #(station-of stations %) (concat (map :at (items (:cards f))) (items (:on f))))))

(defn lane-card
  "Lane's card on frame n: its own, else the last one it had before (shown as gone)."
  [lanes frames n lane]
  (let [now (card-of lanes lane (get frames n))]
    {:now now :card (or now (some #(card-of lanes lane (nth frames %)) (range (dec n) -1 -1)))}))

(defn lane-lines
  "The most lines lane's card has on any frame (the lane's height follows it)."
  [lanes frames lane]
  (apply max 1 (for [fr frames :let [c (card-of lanes lane fr)] :when c] (count (str/split (str (:body c)) #"\n" -1)))))

(defn card-box
  "A card's width and left: under where it is, kept inside the stage. x: {:w :at {id centre}}."
  [x card]
  (let [w (min card-w (* 0.48 (:w x)))
        at (if card (get-in x [:at (:at card)] 0) 0)]
    {:w w :left (max 0 (min (- at (/ w 2)) (- (:w x) w)))}))

(defn station-columns [stations]
  (str/join " " (for [s stations] (str (max 1 (* 0.55 (count (items (:steps s))))) "fr"))))

(defn- measure-stage
  "The stage's width and the centre of each station and step on it."
  [^js el]
  (let [box (.getBoundingClientRect el)]
    {:w (.-width box)
     :at (into {} (for [s (array-seq (.querySelectorAll el "[data-at]"))
                        :let [r (.getBoundingClientRect s)]]
                    [(.. s -dataset -at) (+ (- (.-left r) (.-left box)) (/ (.-width r) 2))]))}))

;; ---- the views ----

(defn- flow-bar [ways cur frames n go set-way set-i]
  [:div.f-flow-bar
   [:button.f-flow-btn {:type "button" :on-click #(go -1) :disabled (zero? n)} "◀ back"]
   [:button.f-flow-btn {:type "button" :on-click #(go 1) :disabled (>= n (dec (count frames)))} "next ▶"]
   (when (> (count ways) 1)
     [:span.f-flow-ways {:role "radiogroup" :aria-label "Scenario"}
      (for [w ways]
        ^{:key (:id w)}
        [:button.f-flow-btn {:type "button" :role "radio" :aria-checked (= (:id w) (:id cur))
                             :class (when (= (:id w) (:id cur)) "on")
                             :on-click #(do (set-way (:id w)) (set-i 0))}
         (or (:label w) (:id w))])])
   [:span.f-flow-n (if (seq frames) (str (inc n) " / " (count frames)) "no frames yet")]])

(defn- station-view [s f lit]
  (let [has? (fn [k id] (some #{id} (items (get f k))))]
    [:div.f-flow-st.chalk-box {:class (when (lit (:id s)) "on") :data-at (:id s) :data-item (:id s)}
     [:b (or (:label s) (:id s))]
     (when (:note s) [:small (:note s)])
     (when (seq (items (:steps s)))
       [:div.f-flow-steps
        (for [t (items (:steps s))]
          ^{:key (:id t)}
          [:span.f-flow-step {:data-at (:id t)
                              :class [(when (has? :on (:id t)) "on") (when (has? :leave (:id t)) "leave") (when (has? :skip (:id t)) "skip")]}
           (or (:label t) (:id t))])])]))

(defn- lane-view [l lanes frames n x]
  (let [{:keys [now card]} (lane-card lanes frames n (:id l))
        {:keys [w left]} (card-box x card)]
    [:div.f-flow-lane {:style {:min-height (+ (* (lane-lines lanes frames (:id l)) 18) 52)}}
     (when (:label l) [:span.f-flow-lane-label (:label l)])
     (when (and card (pos? (:w x)))
       [:div.f-flow-card {:class [(when-not now "gone") (when (:dim card) "dim")]
                          :style {:left left :width w "--c" (str "var(--" (or (:color card) "grey") ")")}}
        (when (:title card) [:div.hd (:title card)])
        (when (some? (:body card)) [:f> card-body (str (:body card)) (:lang card) (:new card)])])]))

(defn- on-arrow-key [go]
  (fn [e]
    (when (#{"ArrowLeft" "ArrowRight"} (.-key e))
      (.preventDefault e)
      (.stopPropagation e)
      (go (if (= "ArrowLeft" (.-key e)) -1 1)))))

(defn flow-form [data]
  (let [stations (items (:stations data))
        lanes (lanes-of data)
        ways (ways-of data)
        [way set-way] (h/use-state (:id (first ways)))
        [i set-i] (h/use-state 0)
        cur (or (some #(when (= (:id %) way) %) ways) (first ways))
        frames (:frames cur)
        n (min i (max 0 (dec (count frames))))
        f (or (get frames n) {})
        go (fn [d] (set-i (max 0 (min (dec (count frames)) (+ n d)))))
        stage (h/use-ref nil)
        [x set-x] (h/use-state {:w 0 :at {}})
        lit (lit-stations stations f)]
    (hooks/use-resize stage #(set-x (measure-stage %)) [(pr-str stations)])
    [:div.f-flow {:tab-index 0 :on-key-down (on-arrow-key go)}
     (flow-bar ways cur frames n go set-way set-i)
     [:div.f-flow-stage {:ref #(set! (.-current stage) %)}
      [:div.f-flow-stations {:style {:grid-template-columns (station-columns stations)}}
       (for [s stations] (with-meta (station-view s f lit) {:key (:id s)}))]
      (for [l lanes] (with-meta (lane-view l lanes frames n x) {:key (:id l)}))]
     (when (:caption f) [:p.f-flow-cap (:caption f)])]))
