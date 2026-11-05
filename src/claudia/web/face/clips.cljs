(ns claudia.web.face.clips
  "What Claudia's face does (2026-11-05). A mood is how she is while it lasts (working, waiting on
  you, asleep...), a pose from the time t in ms. A clip is a short act on top (a hop when a chat is
  done, a yawn, a sneeze), a pose from its progress p (0 to 1) over :ms milliseconds.

  A pose is a map, every key optional: :eyes (both) or :eye-far / :eye-near (kinds in
  claudia.web.face.art), :mouth, :tongue?, :look [x y] (-1 to 1, where she looks), :dx :dy (moves,
  frame units) :rot (degrees) :sx :sy (squash), :spin (how fast the clip turns), :sway (the front
  lock, degrees), :blush (0 to 1), :blink (0 to 1, forced), :fx (effects, claudia.web.face.fx).")

(def ^:private pi js/Math.PI)
(defn- sin [v] (js/Math.sin v))

(defn bump "0 at both ends, 1 in the middle." [p] (sin (* p pi)))

(defn hops
  "n hops of height h (frame units, upward is negative) dying out over p."
  [p n h]
  (* -1 h (- 1 p) (js/Math.abs (sin (* p pi n)))))

(defn shake "A side-to-side shake of amplitude a dying out." [p a] (* a (- 1 p) (sin (* p 60))))

(defn- between? [p a b] (and (>= p a) (< p b)))

;; ---- moods ----

(defn- scan
  "Eyes reading along lines: left to right, then down a line."
  [t ms]
  [(+ -0.9 (* 0.55 (sin (/ t ms)))) (+ 0.55 (* 0.15 (sin (/ t (* ms 3.1)))))])

(defn mood-pose
  "The pose of a mood at time t."
  [mood t]
  (case mood
    :work {:eyes :dot :mouth :small :look (scan t 600) :spin 2 :fx [[:dots false]]}
    :busy {:eyes :narrow :mouth :wavy :look (scan t 200) :spin 7 :fx [[:dots true] [:sweat]]}
    :wait {:eyes :wide :mouth :o :look [0.25 -0.1] :rot (* 5 (sin (/ t 160))) :spin 0.5
           :fx [[:bang 1]]}
    :offline {:eyes :sad :mouth :frown :look [0 0.5] :spin 0 :fx [[:cloud]]}
    :sleep {:eyes :shut :mouth :small :look [0 0.6] :rot -8 :dy (* 6 (sin (/ t 1300))) :spin 0
            :blink 0 :fx [[:z]]}
    :sleepy {:eyes :half :mouth :small :spin 0.1}
    {:eyes :dot :mouth :smile :spin 0.15}))

(def hover-pose
  "The mouse on her: a grin, blushing, leaning in."
  {:mouth :grin :blush 1 :rot 6 :spin 0.6})

;; ---- clips: things that happen ----

(defn- done [p _]
  {:eyes :happy :mouth :grin :dy (hops p 2 70) :spin 5 :blush 0.9 :fx [[:sparkles p]]})

(defn- many-done [p _]
  (assoc (done p nil) :dy (hops p 3 90) :rot (* 8 (sin (* p 12))) :fx [[:confetti p] [:sparkles p]]))

(defn- new-chat [p _]
  (if (< p 0.35)
    {:eyes :wide :mouth :o :sy (+ 1 (* 0.15 (bump (/ p 0.35)))) :sx (- 1 (* 0.1 (bump (/ p 0.35))))
     :fx [[:bang (/ p 0.35)]]}
    {:eyes :happy :mouth :grin :dy (hops (/ (- p 0.35) 0.65) 1 40)}))

(defn- gone [p _]
  {:eye-near (if (between? p 0.2 0.7) :happy :dot) :mouth :smile :look [0.8 0] :fx [[:poof p]]})

(defn- failed [p _]
  {:eyes (if (< p 0.4) :wide :sad) :mouth (if (< p 0.4) :wavy :frown) :dx (shake p 22) :spin 0
   :blush 0.15 :fx [[:swirl p]]})

(defn- asked [p _]
  {:eyes :wide :mouth :o :dy (hops p 1 60) :look [0.25 -0.2] :fx [[:bang p]]})

(defn- started [p _]
  {:eyes :narrow :mouth :small :dy (* 18 (bump (* 2 (min p 0.5)))) :spin 4 :look [-0.5 0.5]})

(defn- giggle [p _]
  {:eyes :happy :mouth :grin :blush 1 :rot (* 6 (sin (* p 30))) :sy (- 1 (* 0.12 (bump (min 1 (* 3 p)))))
   :spin 4 :fx [[:hearts p]]})

(defn- dizzy [p _]
  {:eyes :spiral :mouth :wavy :rot (* 12 (sin (* p 20)) (- 1 p)) :dx (* 10 (sin (* p 14))) :spin 10
   :fx [[:orbit]]})

(defn- hello [p _]
  {:eyes :happy :mouth :grin :dy (hops p 2 50) :rot (* 10 (sin (* p 16)) (- 1 p)) :blush 0.8
   :fx [[:word ["hi!" p]]]})

(defn- list-open [p _]
  {:look [0.1 1] :mouth :smile :eyes (if (< p 0.15) :wide :dot) :dy (* 10 (bump p))})

(defn- list-close [_ _] {:look [0.6 -0.6] :mouth :small})

(defn- sent [p _]
  {:eyes (if (between? p 0.2 0.8) :happy :dot) :mouth :smile :dy (* 20 (bump (mod (* 2 p) 1))) :look [0.6 0.9]})

(defn- typing [p _]
  {:look [0.8 1] :mouth :small :dy (* 6 (bump p))})

(defn- drag [p _]
  {:eyes :wide :mouth :big-o :sy (+ 1 (* 0.12 (bump (min 1 (* 2 p))))) :look [0.6 0.3]})

(defn- wake [p _]
  {:eyes (if (< p 0.5) :wide :dot) :mouth (if (< p 0.5) :o :smile) :dy (hops (min 1 (* 2 p)) 1 80)
   :blink (bump (/ (max 0 (- p 0.55)) 0.45)) :fx [[:bang (min 1 (* 2 p))]]})

(defn- switch-chat [p _] {:look [0.9 0.2] :blink (if (between? p 0.4 0.6) (bump (/ (- p 0.4) 0.2)) 0)})

;; ---- clips: her own life when nothing happens ----

(defn- blink2 [p _] {:blink (bump (mod (* 2 p) 1))})

(defn- glance [_ dir] {:look dir})

(defn- twirl [p _] {:look [0.6 -1] :spin (* 30 (bump p)) :mouth :small :eyes (if (> p 0.6) :happy :dot)})

(defn- sway [p _] {:sway (* 9 (sin (* p pi 4)) (- 1 p)) :rot (* 3 (sin (* p pi 2)))})

(defn- yawn [p _]
  {:eyes :shut :mouth :big-o :sy (+ 1 (* 0.1 (bump p))) :blink 0 :dy (* -10 (bump p)) :look [0 -0.4]})

(defn- hum [p _]
  {:eyes :happy :mouth :small :rot (* 6 (sin (* p pi 6))) :sway (* 6 (sin (* p pi 6))) :fx [[:notes]]})

(defn- whistle [p _] {:mouth :whistle :look [0.4 -0.8] :rot (* 4 (sin (* p pi 4))) :fx [[:notes]]})

(defn- wink [p _] {:eye-near (if (between? p 0.25 0.75) :happy :dot) :mouth :grin :rot (* 5 (bump p))})

(defn- blep [_ _] {:eyes :happy :mouth :smile :tongue? true})

(defn- curious [p _] {:eyes :wide :mouth :small :rot (* 12 (bump p)) :look [0.5 -0.3] :fx [[:question p]]})

(defn- stretch [p _]
  (let [s (sin (* p pi 2))]
    {:sy (+ 1 (* 0.14 s)) :sx (- 1 (* 0.08 s)) :eyes (if (< p 0.5) :shut :dot) :mouth (if (< p 0.5) :o :smile)}))

(defn- sneeze [p _]
  (if (< p 0.55)
    {:eyes :squeeze :mouth :o :rot (* -8 (/ p 0.55)) :dy (* -10 (/ p 0.55)) :blink 0}
    (let [q (/ (- p 0.55) 0.45)]
      {:eyes (if (< q 0.3) :squeeze :dot) :mouth (if (< q 0.3) :big-o :small) :rot (* 6 (- 1 q))
       :dx (shake q 18) :fx [[:word ["achoo!" q]]]})))

(defn- hiccup [p _]
  (let [h (or (between? p 0.15 0.3) (between? p 0.6 0.75))]
    {:eyes (if h :wide :dot) :mouth (if h :o :small) :dy (if h -40 0) :fx [[:word ["hic" p]]]}))

(defn- peek-right [_ _] {:look [1 0.1] :mouth :small})

(defn- starry [p _] {:eyes :star :mouth :grin :blush (bump p) :fx [[:sparkles (* 0.6 p)]]})

(defn- puff [p _] {:mouth :o :blush 1 :sway (* 14 (bump p)) :look [0.3 -0.9]})

(defn- nod-off [p _]
  (if (< p 0.85)
    {:eyes (if (< p 0.4) :half :shut) :mouth :small :dy (* 30 (/ p 0.85)) :rot (* -6 (/ p 0.85)) :blink 0}
    {:eyes :wide :mouth :o :dy (* -30 (bump (/ (- p 0.85) 0.15)))}))

(defn- love [p _] {:eyes :heart :mouth :grin :blush 1 :rot (* 5 (sin (* p 10))) :fx [[:hearts p]]})

(defn- look-at-you [p _] {:look [0 0] :mouth :cat :blush (* 0.9 (bump p))})

(def clips
  "Every clip: how long (ms), how strong (a clip only replaces one of the same strength or weaker)
  and what it does."
  {:done {:ms 2000 :prio 4 :act done}
   :many-done {:ms 2800 :prio 4 :act many-done}
   :new-chat {:ms 1600 :prio 3 :act new-chat}
   :gone {:ms 1400 :prio 3 :act gone}
   :failed {:ms 2600 :prio 5 :act failed}
   :asked {:ms 1300 :prio 5 :act asked}
   :started {:ms 1000 :prio 2 :act started}
   :giggle {:ms 1300 :prio 3 :act giggle}
   :dizzy {:ms 2600 :prio 4 :act dizzy}
   :hello {:ms 2200 :prio 3 :act hello}
   :list-open {:ms 1400 :prio 2 :act list-open}
   :list-close {:ms 900 :prio 2 :act list-close}
   :sent {:ms 1000 :prio 2 :act sent}
   :typing {:ms 900 :prio 1 :act typing}
   :drag {:ms 1500 :prio 3 :act drag}
   :wake {:ms 1400 :prio 3 :act wake}
   :switch {:ms 800 :prio 1 :act switch-chat}
   :blink2 {:ms 600 :prio 0 :act blink2}
   :glance {:ms 1800 :prio 0 :act glance}
   :twirl {:ms 1300 :prio 0 :act twirl}
   :sway {:ms 2000 :prio 0 :act sway}
   :yawn {:ms 2600 :prio 0 :act yawn}
   :hum {:ms 3600 :prio 0 :act hum}
   :whistle {:ms 2600 :prio 0 :act whistle}
   :wink {:ms 900 :prio 0 :act wink}
   :blep {:ms 1400 :prio 0 :act blep}
   :curious {:ms 2200 :prio 0 :act curious}
   :stretch {:ms 1600 :prio 0 :act stretch}
   :sneeze {:ms 2400 :prio 0 :act sneeze}
   :hiccup {:ms 1500 :prio 0 :act hiccup}
   :peek {:ms 2600 :prio 0 :act peek-right}
   :starry {:ms 1600 :prio 0 :act starry}
   :puff {:ms 1500 :prio 0 :act puff}
   :nod-off {:ms 3200 :prio 0 :act nod-off}
   :love {:ms 1800 :prio 0 :act love}
   :look-at-you {:ms 1600 :prio 0 :act look-at-you}})

(def idle-weights
  "How often each clip of her own comes when she is idle, by day and by night (sleepy)."
  {:idle {:glance 10 :blink2 5 :twirl 3 :sway 4 :hum 3 :whistle 2 :wink 2 :blep 2 :curious 2 :stretch 2
          :yawn 1 :sneeze 1 :hiccup 1 :peek 3 :starry 1 :puff 2 :love 1 :look-at-you 3}
   :sleepy {:glance 4 :yawn 6 :nod-off 5 :stretch 2 :blink2 3 :sway 2}
   :work {:twirl 3 :peek 2 :blink2 3 :sway 1}})

(defn pick-weighted
  "The key of weights (a map of key -> weight, in a fixed order) that r (0 to 1) lands on."
  [weights r]
  (let [entries (sort-by key weights)
        goal (* r (reduce + (vals weights)))]
    (loop [[[k w] & more] entries acc 0]
      (if (or (empty? more) (< goal (+ acc w))) k (recur more (+ acc w))))))

(defn clip-pose
  "The pose of a running clip {:id :start :arg} at time now."
  [{:keys [id start arg]} now]
  (let [{:keys [ms act]} (clips id)]
    (act (min 1 (/ (- now start) ms)) arg)))
