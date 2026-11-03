(ns claudia.web.chalk
  "Chalk, baked once at load (DESIGN.md \"Chalk technique\"). Every chalk edge (outlines, circles,
  lines, dots) is a mask image drawn here when the page loads: the shape with its edge roughed up by
  the SVG spec's turbulence noise. The CSS paints the chalk colour through these masks, which costs
  nothing per frame (live SVG filters made Safari redraw in software every frame).

  Two kinds of image:
  - shapes (`--chalk-<name>`): nine-slice masks (`-webkit-mask-box-image`), one image fits any box;
  - grain (`--chalk-grain`, `grain-url`): a noise tile for SVG drawings (the graph, charts).")

;; name -> [baseFrequency seed displacement-scale], the old filters' three noises.
(def ^:private noises
  {:edge [0.85 3 2.4]
   :soft [0.9 3 1.3]
   :line [0.7 5 3]})

;; Room outside a shape for its rough edge (CSS px); the CSS insets each chalk ::before by this.
(def ^:private edge-room 2)
;; Length of the repeated edge piece of a nine-slice (CSS px).
(def ^:private tile 64)

;; Every shape the CSS uses: var(--chalk-<name>). r = corner radius, b = ring width (none = solid).
(def ^:private shapes
  {"r6-15" {:r 6 :b 1.5}
   "r7-2" {:r 7 :b 2}
   "r8-15" {:r 8 :b 1.5}
   "r8-2" {:r 8 :b 2}
   "r9-15" {:r 9 :b 1.5}
   "r9-2" {:r 9 :b 2}
   "r10-2" {:r 10 :b 2}
   "r12-15" {:r 12 :b 1.5}
   "r12-2" {:r 12 :b 2}
   "r12-2-dash" {:r 12 :b 2 :dash 6}
   "r14-2" {:r 14 :b 2}
   "c32-2" {:r 16 :b 2}
   "c34-22" {:r 17 :b 2.2}
   "c34-18-dash" {:r 17 :b 1.8 :dash 2.6}
   "line2" {:r 1 :noise :line}
   "dot18" {:r 9}
   "dot56" {:r 28}
   "bar35" {:r 3.5}
   ;; The code forms (codeforms.css): file tags, kind chips, form row circles, column buttons.
   "r5-15" {:r 5 :b 1.5}
   "r7-15" {:r 7 :b 1.5}
   "r8-15-dash" {:r 8 :b 1.5 :dash 4}
   "c30-18" {:r 15 :b 1.8}
   "c30-22" {:r 15 :b 2.2}
   ;; The rest of the TypeScript Claudia's set (web/chalk.ts SHAPES).
   "r18-2" {:r 18 :b 2}
   "line3" {:r 1.5 :noise :line}
   "line3-soft" {:r 1.5 :noise :soft}
   "dot9-soft" {:r 4.5 :noise :soft}
   "dot11" {:r 5.5}
   "bar3" {:r 3}})

;; ---- The SVG spec's turbulence (Filter Effects, feTurbulence reference code) ----

(def ^:private B 0x100)
(def ^:private BM 0xff)
(def ^:private PERLIN-N 0x1000)

(defn- make-turbulence
  "Lattice and gradients for one seed, as the spec's init() makes them."
  [seed]
  (let [M 2147483647 A 16807 Q 127773 R 2836
        rnd (fn [s] (let [r (- (* A (mod s Q)) (* R (js/Math.trunc (/ s Q))))]
                      (if (<= r 0) (+ r M) r)))
        seed (cond (<= seed 0) (inc (- (mod seed (dec M))))
                   (> seed (dec M)) (dec M)
                   :else seed)
        n (+ B B 2)
        lattice (js/Int32Array. n)
        grads (into-array (repeatedly 4 #(js/Float64Array. (* n 2))))
        s (volatile! seed)]
    (dotimes [k 4]
      (let [g (aget grads k)]
        (dotimes [i B]
          (aset lattice i i)
          (dotimes [j 2]
            (vreset! s (rnd @s))
            (aset g (+ (* i 2) j) (/ (- (mod @s (+ B B)) B) B)))
          (let [x (aget g (* i 2)) y (aget g (inc (* i 2))) l (js/Math.hypot x y)]
            (aset g (* i 2) (/ x l))
            (aset g (inc (* i 2)) (/ y l))))))
    (loop [i (dec B)]
      (when (pos? i)
        (let [k (aget lattice i)]
          (vreset! s (rnd @s))
          (let [j (mod @s B)]
            (aset lattice i (aget lattice j))
            (aset lattice j k)))
        (recur (dec i))))
    (dotimes [i (+ B 2)]
      (aset lattice (+ B i) (aget lattice i))
      (dotimes [k 4]
        (let [g (aget grads k)]
          (dotimes [j 2]
            (aset g (+ (* (+ B i) 2) j) (aget g (+ (* i 2) j)))))))
    {:lattice lattice :grads grads}))

(defn- s-curve [t] (* t t (- 3 (* 2 t))))

(defn- noise2 [^js lattice ^js grads ch x y]
  (let [t (+ x PERLIN-N)
        bx0 (bit-and (js/Math.trunc t) BM)
        bx1 (bit-and (inc bx0) BM)
        rx0 (- t (js/Math.trunc t))
        rx1 (dec rx0)
        t (+ y PERLIN-N)
        by0 (bit-and (js/Math.trunc t) BM)
        by1 (bit-and (inc by0) BM)
        ry0 (- t (js/Math.trunc t))
        ry1 (dec ry0)
        i (aget lattice bx0)
        j (aget lattice bx1)
        b00 (aget lattice (+ i by0))
        b10 (aget lattice (+ j by0))
        b01 (aget lattice (+ i by1))
        b11 (aget lattice (+ j by1))
        sx (s-curve rx0)
        sy (s-curve ry0)
        g (aget grads ch)
        u (+ (* rx0 (aget g (* b00 2))) (* ry0 (aget g (inc (* b00 2)))))
        v (+ (* rx1 (aget g (* b10 2))) (* ry0 (aget g (inc (* b10 2)))))
        a (+ u (* sx (- v u)))
        u (+ (* rx0 (aget g (* b01 2))) (* ry1 (aget g (inc (* b01 2)))))
        v (+ (* rx1 (aget g (* b11 2))) (* ry1 (aget g (inc (* b11 2)))))
        b (+ u (* sx (- v u)))]
    (+ a (* sy (- b a)))))

(defn- fractal
  "fractalNoise, two octaves, as a colour value 0..1."
  [turb ch x y freq]
  (let [lattice (:lattice turb) grads (:grads turb)
        sum (+ (noise2 lattice grads ch (* x freq) (* y freq))
               (/ (noise2 lattice grads ch (* x freq 2) (* y freq 2)) 2))]
    (min 1 (max 0 (/ (inc sum) 2)))))

(def ^:private turbulence
  (memoize make-turbulence))

(defn- round-box
  "Signed distance from p to a box centred at 0 (half sizes hx hy, corner radius r); < 0 inside."
  [px py hx hy r]
  (let [qx (+ (- (js/Math.abs px) hx) r)
        qy (+ (- (js/Math.abs py) hy) r)]
    (- (+ (js/Math.hypot (max qx 0) (max qy 0)) (min (max qx qy) 0)) r)))

(def ^:private dpr
  (min 3 (max 1 (js/Math.ceil (or js/window.devicePixelRatio 1)))))

(defn- dash-at
  "A dashed ring: 1 on a dash, 0 in a gap, by the distance along the ring's middle line."
  [x y half r b dash]
  (let [straight (- half r)
        ax (js/Math.abs x) ay (js/Math.abs y)
        along (cond (<= ax straight) ax
                    (<= ay straight) ay
                    :else (+ straight (* (js/Math.atan2 (- ay straight) (- ax straight))
                                         (max 0.5 (- r (/ b 2))))))
        period (/ tile (max 1 (js/Math.round (/ tile (* dash 2)))))
        p (mod (+ along (/ period 4)) period)]
    (if (< p (/ period 2)) 1 0)))

(defn- canvas-of [px]
  (let [c (js/document.createElement "canvas")]
    (set! (.-width c) px)
    (set! (.-height c) px)
    c))

(defn- bake
  "One shape as a white image with the chalk's alpha: size x size CSS px, the shape inset `edge-room`."
  [size {:keys [r b dash noise] :or {noise :edge}}]
  (let [[freq seed scale] (noises noise)
        turb (turbulence seed)
        px (js/Math.round (* size dpr))
        canvas (canvas-of px)
        ctx (.getContext canvas "2d")
        img (.createImageData ctx px px)
        data (.-data img)
        half (- (/ size 2) edge-room)
        r (min r half)
        aa (/ 1 dpr)
        cover (fn [d] (min 1 (max 0 (- 0.5 (/ d aa)))))]
    (dotimes [y px]
      (dotimes [x px]
        (let [cx (/ (+ x 0.5) dpr) cy (/ (+ y 0.5) dpr)
              ox (- cx (/ size 2)) oy (- cy (/ size 2))
              d0 (round-box ox oy half half r)]
          (when-not (or (> d0 scale) (and b (< d0 (- (- b) scale))))
            (let [dx (* scale (- (fractal turb 0 cx cy freq) 0.5))
                  dy (* scale (- (fractal turb 1 cx cy freq) 0.5))
                  sx (+ ox dx) sy (+ oy dy)
                  d (round-box sx sy half half r)
                  a (cond-> (cover d) b (- (cover (+ d b))))
                  a (if (and dash (pos? a)) (* a (dash-at sx sy half r (or b 0) dash)) a)
                  i (* 4 (+ (* y px) x))]
              (aset data i 255)
              (aset data (+ i 1) 255)
              (aset data (+ i 2) 255)
              (aset data (+ i 3) (js/Math.round (* a 255))))))))
    (.putImageData ctx img 0 0)
    (.toDataURL canvas "image/png")))

(defn- nine-slice [{:keys [r b] :as shape}]
  (let [k (+ edge-room (max r (or b 0)))
        url (bake (+ (* 2 k) tile) shape)]
    (str "url(" url ") " (* k dpr) (when-not b " fill") " / " k "px repeat")))

(def grain-size 48)

;; edge, soft: mostly solid with chalk's gaps; fray: about half gaps, for a wider copy of a stroke
;; under it, so the stroke's edge frays.
(def ^:private grains
  {:edge [:edge 0.24 0.06] :soft [:soft 0.22 0.06] :fray [:edge 0.46 0.1]})

(defn- grain [size noise-name cut soft]
  (let [[freq seed] (noises noise-name)
        turb (turbulence (+ seed 11))
        px (js/Math.round (* size dpr))
        canvas (canvas-of px)
        ctx (.getContext canvas "2d")
        img (.createImageData ctx px px)
        data (.-data img)]
    (dotimes [y px]
      (dotimes [x px]
        (let [n (fractal turb 0 (/ (+ x 0.5) dpr) (/ (+ y 0.5) dpr) (* freq 1.4))
              a (min 1 (max 0 (/ (- n cut) soft)))
              i (* 4 (+ (* y px) x))]
          (aset data i 255)
          (aset data (+ i 1) 255)
          (aset data (+ i 2) 255)
          (aset data (+ i 3) (js/Math.round (* a 255))))))
    (.putImageData ctx img 0 0)
    (.toDataURL canvas "image/png")))

(def grain-url
  "The grain tile (:edge :soft :fray) as a data URL, for SVG <pattern> images."
  (memoize (fn [kind] (apply grain grain-size (grains kind)))))

(defn bake-all!
  "Bake every shape once and hand them to the CSS as custom properties on :root."
  []
  (let [style (.-style js/document.documentElement)]
    (doseq [[n shape] shapes]
      (.setProperty style (str "--chalk-" n) (nine-slice shape)))
    (.setProperty style "--chalk-grain" (str "url(" (grain-url :edge) ")"))
    (.setProperty style "--chalk-grain-soft" (str "url(" (grain-url :soft) ")"))
    (.setProperty style "--chalk-grain-size" (str grain-size "px"))))
