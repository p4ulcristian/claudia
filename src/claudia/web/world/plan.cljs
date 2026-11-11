(ns claudia.web.world.plan
  "The world's layout as plain data, from the servers the person may use and their chats' rows: the
  gate's island in the middle, one island per server on a ring around it, a bridge from the gate to
  each, a building per project on its server's island, and a robot per chat with the spot it heads
  for. Pure: the engine (claudia.web.world.engine) turns it into three.js.")

(def ^:private tau (* 2 js/Math.PI))

(def gate-radius 3.2)

(defn hash01
  "A stable number in [0, 1) from a string (FNV-1a), for placement that never jumps between loads."
  [s]
  (let [h (reduce (fn [h c] (js/Math.imul (bit-xor h (.charCodeAt c 0)) 16777619))
                  2166136261 (str s))]
    (/ (unsigned-bit-shift-right h 0) 4294967296)))

(defn shown-rows
  "The chats that get a robot: not archived, not passive ash."
  [rows]
  (remove #(or (:archived %) (= "ash" (:color %))) rows))

(defn robot-state
  "What a chat's robot does: :off (its server is not connected), :waiting (an open question),
  :working (a turn or its agents run), :done (finished, not looked at yet), else :idle."
  [row]
  (cond (:off row) :off
        (:waiting row) :waiting
        (or (:running row) (:runningFor row)) :working
        (:unseen row) :done
        :else :idle))

(defn project-key
  "The building a chat belongs in: its project's root, or nil (the island's square)."
  [row]
  (get-in row [:project :root]))

(defn island-radius
  "An island grows with its buildings and its robots (each robot stands under its own screen)."
  [n-buildings n-robots]
  (max (+ 5 (* 1.6 (js/Math.sqrt n-buildings)))
       (+ 4 (* 3.4 (js/Math.sqrt n-robots)))))

(defn ring-distance
  "How far the server islands sit from the gate: clear of the gate and of each other."
  [n max-r]
  (let [apart (+ (* 2 max-r) 5)
        chord (if (> n 1) (/ apart (* 2 (js/Math.sin (/ js/Math.PI n)))) 0)]
    (max (+ gate-radius max-r 9) chord)))

(defn- angle-of [i n] (- (* tau (/ i (max n 1))) (/ js/Math.PI 2)))

(defn- polar [[cx cy cz] angle r] [(+ cx (* r (js/Math.cos angle))) cy (+ cz (* r (js/Math.sin angle)))])

(defn- server-island [server i n dist radius]
  (let [id (:name server)]
    {:id id :label (or (:label server) id) :kind :server :radius radius
     :angle (angle-of i n)
     :pos (polar [0 (- (* 1.8 (hash01 (str id "/y"))) 0.8) 0] (angle-of i n) dist)}))

(defn- projects-by-server
  "server -> sorted distinct project roots of its shown chats, with each project's name."
  [rows]
  (->> rows
       (filter project-key)
       (group-by :server)
       (into {} (map (fn [[s rs]] [s (->> rs (map (juxt project-key #(get-in % [:project :name])))
                                          distinct (sort-by first) vec)])))))

(defn islands
  "The gate, then a server island per server (in the order given), each sized for its projects and
  its robots (robot-counts: server -> how many)."
  [servers projects robot-counts]
  (let [radii (mapv #(island-radius (count (get projects (:name %))) (get robot-counts (:name %) 0)) servers)
        dist (ring-distance (count servers) (reduce max gate-radius radii))]
    (into [{:id "gate" :label "gate" :kind :gate :radius gate-radius :pos [0 0 0] :angle 0}]
          (map-indexed (fn [i s] (server-island s i (count servers) dist (radii i))) servers))))

(defn building-angle
  "Where building i of n stands round its island, leaving the side facing the gate (the bridge) free."
  [island-angle i n]
  (let [toward-gate (+ island-angle js/Math.PI)
        free 1.1]
    (+ toward-gate free (* (- tau (* 2 free)) (/ (+ i 0.5) (max n 1))))))

(defn- building [island i n [root project-name]]
  (let [a (building-angle (:angle island) i n)
        h (hash01 root)]
    {:id (str (:id island) "|" root) :island (:id island) :root root
     :label (or project-name root) :angle a :hue h
     :pos (polar (:pos island) a (* 0.62 (:radius island)))
     :size [(+ 1.6 (* 0.8 h)) (+ 1.6 (* 1.6 (hash01 (str root "/h")))) (+ 1.4 (* 0.6 h))]}))

(defn buildings
  "A building per project on each server island."
  [islands-list projects]
  (vec (for [isl islands-list
             :when (= :server (:kind isl))
             :let [ps (get projects (:id isl))]
             [i p] (map-indexed vector ps)]
         (building isl i (count ps) p))))

(defn bridges
  "A bridge from the gate's edge to each server island's edge."
  [islands-list]
  (let [[gate & servers] islands-list]
    (vec (for [s servers
               :let [a (:angle s)]]
           {:id (:id s) :to (:id s)
            :from-pos (polar (:pos gate) a (- gate-radius 0.4))
            :to-pos (polar (:pos s) (+ a js/Math.PI) (- (:radius s) 0.4))}))))

(defn world
  "The fixed part of the world (changes when servers or projects come and go)."
  [servers rows]
  (let [shown (shown-rows rows)
        projects (projects-by-server shown)
        isl (islands servers projects (frequencies (map :server shown)))]
    {:islands isl :buildings (buildings isl projects) :bridges (bridges isl)}))

(defn- door
  "The spot in front of a building, toward its island's middle."
  [isl b]
  (polar (:pos isl) (:angle b) (- (* 0.62 (:radius isl)) (/ (first (:size b)) 2) 2.2)))

(def per-row
  "Robots side by side at one place; more go in rows nearer the island's middle."
  3)

(defn spot
  "Where robot j of k at one place stands: in a row across the place (out: the angle from the
  island's middle toward the place), later rows nearer the middle and shifted half a step, so the
  screens over them don't hide each other."
  [[x y z] out j k]
  (let [row (quot j per-row)
        in-row (min per-row (- k (* row per-row)))
        side (* 4.6 (+ (- (mod j per-row) (/ (dec in-row) 2)) (if (odd? row) 0.5 0)))
        back (* 3.4 row)]
    [(- (+ x (* side (- (js/Math.sin out)))) (* back (js/Math.cos out)))
     y
     (- (+ z (* side (js/Math.cos out))) (* back (js/Math.sin out)))]))

(defn- place-of
  "The robot's place: its building's door, else its island's square."
  [w row]
  (let [isl (first (filter #(= (:server row) (:id %)) (:islands w)))
        b (first (filter #(= (str (:server row) "|" (project-key row)) (:id %)) (:buildings w)))]
    (when isl
      (if b
        {:key (:id b) :island isl :pos (door isl b) :out (:angle b)}
        {:key (:id isl) :island isl :pos (:pos isl) :out (:angle isl)}))))

(defn- robot [row place j k]
  {:id (str (:server row) "/" (:id row)) :key [(:server row) (:id row)]
   :title (:title row) :color (:color row) :state (robot-state row)
   :preview (:preview row) :ask (:text (first (:asks row))) :at (:at row)
   :project (get-in row [:project :name])
   :row (quot j per-row)
   :island (:id (:island place)) :building (:key place)
   :ground (second (:pos (:island place)))
   :out (:out place)
   :spot (spot (:pos place) (:out place) j k)})

(defn robots
  "A robot per shown chat at its place, spread so none stand on another."
  [w rows]
  (->> (shown-rows rows)
       (keep #(when-let [p (place-of w %)] [p %]))
       (group-by (comp :key first))
       vals
       (mapcat (fn [pairs] (map-indexed (fn [j [p row]] (robot row p j (count pairs)))
                                        (sort-by (comp :started second) pairs))))
       (sort-by :id)
       vec))

(defn working-buildings
  "Building ids with a working robot (their windows light up)."
  [robot-list]
  (into #{} (comp (filter #(= :working (:state %))) (map :building)) robot-list))

(def ^:private skies
  ;; hour -> [zenith horizon] as hex: night, dawn, day, dusk.
  [[0 [0x0b1026 0x1d2547]] [5 [0x1b2350 0x8a6f9e]] [7 [0x5b8fd6 0xf2c89b]] [10 [0x4f8fe0 0xbfe0f5]]
   [17 [0x5a86d0 0xf4d2a6]] [19 [0x34306b 0xe58a6a]] [21 [0x121735 0x2c2f5a]] [24 [0x0b1026 0x1d2547]]])

(defn- mix-hex [a b t]
  (let [ch (fn [shift] (let [ca (bit-and (bit-shift-right a shift) 255) cb (bit-and (bit-shift-right b shift) 255)]
                         (bit-shift-left (js/Math.round (+ ca (* t (- cb ca)))) shift)))]
    (bit-or (ch 16) (ch 8) (ch 0))))

(defn sky
  "The sky's [zenith horizon] colours at an hour of the day (0-24, fractions blend)."
  [hour]
  (let [[[h0 [z0 r0]] [h1 [z1 r1]]] (first (filter (fn [[[a] [b]]] (<= a hour b)) (partition 2 1 skies)))
        t (if (= h0 h1) 0 (/ (- hour h0) (- h1 h0)))]
    [(mix-hex z0 z1 t) (mix-hex r0 r1 t)]))
