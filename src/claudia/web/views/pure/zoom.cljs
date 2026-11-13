(ns claudia.web.views.pure.zoom
  "The big image's zoom, as data: z = {:k scale :x :y} (the image's shift in px from its fitted,
  centred place, then scaled about its centre). Points are in px from the window's centre.")

(def none "Fitted, not zoomed." {:k 1 :x 0 :y 0})
(def max-k "The closest zoom: eight times the fitted size." 8)
(def tap-k "A double tap zooms this far in (another goes back)." 2.5)

(defn- clamp [lo hi v] (max lo (min hi v)))

(defn kept-in
  "z with the image kept over the window: no shift beyond its edges; fitted when k is about 1.
  img and win: [width height] of the fitted image and of the window."
  [{:keys [k x y]} [iw ih] [ww wh]]
  (let [k (clamp 1 max-k k)
        mx (max 0 (/ (- (* k iw) ww) 2))
        my (max 0 (/ (- (* k ih) wh) 2))]
    (if (< k 1.01)
      none
      {:k k :x (clamp (- mx) mx x) :y (clamp (- my) my y)})))

(defn zoom-at
  "z zoomed by f with the point (px, py) kept still on the screen."
  [{:keys [k x y]} f px py]
  (let [k2 (clamp 1 max-k (* k f))
        r (/ k2 k)]
    {:k k2 :x (- px (* r (- px x))) :y (- py (* r (- py y)))}))

(defn moved
  "z shifted by (dx, dy)."
  [z dx dy]
  (-> z (update :x + dx) (update :y + dy)))

(defn pinched
  "z after two fingers moved from (a, b) to (a2, b2) (each [x y] from the window's centre): zoomed
  by how much further apart they are, about their middle, and moved with the middle."
  [z [a b] [a2 b2]]
  (let [dist (fn [[x1 y1] [x2 y2]] (js/Math.hypot (- x2 x1) (- y2 y1)))
        mid (fn [[x1 y1] [x2 y2]] [(/ (+ x1 x2) 2) (/ (+ y1 y2) 2)])
        [mx my] (mid a b)
        [mx2 my2] (mid a2 b2)
        f (/ (dist a2 b2) (max 1 (dist a b)))]
    (moved (zoom-at z f mx my) (- mx2 mx) (- my2 my))))

(defn double-tapped
  "z after a double tap at (px, py): in to tap-k there, or back to fitted."
  [z px py]
  (if (> (:k z) 1.01) none (zoom-at z tap-k px py)))

(defn wheel-factor
  "How much one wheel event zooms: a trackpad pinch (ctrl held) more finely than a wheel."
  [delta-y ctrl?]
  (js/Math.exp (* (- delta-y) (if ctrl? 0.01 0.002))))

(defn transform
  "The CSS transform for z."
  [{:keys [k x y]}]
  (str "translate(" x "px, " y "px) scale(" k ")"))
