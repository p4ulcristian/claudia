(ns claudia.web.face.paint
  "Claudia's face from her state to SVG attributes (2026-11-18, the noir look): part name ->
  {attribute value} (:html for the lenses). The view writes only what changed. Her glasses come down
  when she has something to show with her eyes; the eyes follow where she looks; the lenses show
  what claudia.web.face.lens picks."
  (:require [claudia.web.face.art :as art]
            [claudia.web.face.lens :as lens]))

(defn- r1 [v] (.toFixed (js/Number v) 2))

(defn transform
  "translate by x y, then turn r degrees and scale sx sy round the point c."
  ([x y] (transform x y 0 1 1 [0 0]))
  ([x y r sx sy [cx cy]]
   (str "translate(" (r1 (+ x cx)) " " (r1 (+ y cy)) ") rotate(" (r1 r) ") scale(" (r1 sx) " " (r1 sy)
        ") translate(" (- cx) " " (- cy) ")")))

(def ^:private still-eyes #{nil :dot})

(defn glasses-down?
  "Whether her glasses are pulled down: a chat's news, the mouse near or on her, a mood she shows
  with her eyes, or any eyes but plain ones."
  [{:keys [pose ticker hover? pointer mood]}]
  (boolean (or ticker hover?
               (and pointer (< (js/Math.hypot (first pointer) (second pointer)) 1))
               (#{:wait :sleep :sleepy :offline} mood)
               (some #(not (contains? still-eyes (% pose))) [:eyes :eye-far :eye-near]))))

(defn eye-look
  "How an eye of kind looks, shut by blink (0 to 1): :cover = how far the upper lid is down (0 to 1),
  :line = :lid (open), :shut or :happy, :wide? for a bigger iris."
  [kind blink]
  (let [[cover line] (case kind
                       :narrow [0.4 :lid] :half [0.55 :lid] :sad [0.3 :lid]
                       (:happy :squeeze) [1 :happy] :shut [1 :shut]
                       [0 :lid])
        shut? (or (= cover 1) (> blink 0.8))]
    {:cover (max cover blink) :line (cond (= line :happy) :happy shut? :shut :else :lid)
     :wide? (= kind :wide)}))

(defn- eye-attrs
  "One eye's parts for kind and blink, its iris moved by look [x y]."
  [side kind blink [lx ly]]
  (let [{:keys [cover line wide?]} (eye-look kind blink)
        {[ix iy] :iris [_ by _ bh] :box :as e} (art/eyes side)
        p (name side)
        shut? (not= line :lid)]
    {(keyword (str "open-" p)) {:display (if shut? "none" "inline")}
     (keyword (str "iris-" p)) {:transform (transform (+ ix (* 9 lx)) (+ iy (* 4 ly)) 0 (if wide? 1.15 1) (if wide? 1.15 1) [0 0])}
     (keyword (str "cover-" p)) {:height (r1 (* bh cover)) :y by}
     (keyword (str "lid-" p)) {:d (e line)}
     (keyword (str "low-" p)) {:display (if shut? "none" "inline")}}))

(defn- eyes-attrs [{:keys [eyes eye-far eye-near blink]} look]
  (merge (eye-attrs :far (or eye-far eyes :dot) (or blink 0) look)
         (eye-attrs :near (or eye-near eyes :dot) (or blink 0) look)))

(defn- body-attrs
  "The whole head: its moves, lean and squash round her feet (none when calm)."
  [{:keys [dx dy rot sx sy]} calm?]
  {:body {:transform (if calm?
                       (transform 0 0)
                       (transform (or dx 0) (or dy 0) (or rot 0) (or sx 1) (or sy 1) art/feet))}})

(defn- spark-attrs [spin]
  (let [[x y r s] art/spark-at]
    {:spark {:transform (str (transform x y (+ r spin) s s [0 0]) " translate(-150 -150)")}}))

(defn attrs
  "Every part's attributes for her state at now (calm?: no moving about, for reduced motion)."
  [{:keys [pose look spin ticker] :as state} now calm?]
  (let [down? (glasses-down? state)
        shown (lens/show pose ticker)]
    (merge (body-attrs pose calm?)
           (eyes-attrs pose look)
           (spark-attrs spin)
           {:root {:class (if (= :text (:kind shown)) "face big" "face")}
            :up {:opacity (if down? 0 1)}
            :down {:opacity (if down? 1 0)}
            :eyes {:opacity (if down? 1 0)}
            :lens-box {:clip-path (str "url(#face-lens-" (if down? "down" "up") ")")}
            :lens {:html (lens/markup (art/lenses (if down? :down :up)) shown now calm?)}})))
