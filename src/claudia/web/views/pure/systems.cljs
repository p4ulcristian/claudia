(ns claudia.web.views.pure.systems
  "The Systems popup's words and numbers (README decision 12), pure: sizes in binary units (GiB, as
  the kernel counts), rates, ages and which colour a share gets.")

(def ^:private units ["B" "KiB" "MiB" "GiB" "TiB" "PiB"])

(defn bytes-text
  "Bytes as \"13.1 GiB\" (one decimal under 100, none above; whole bytes as they are)."
  [n]
  (loop [v (or n 0) i 0]
    (if (and (>= v 1024) (< i (dec (count units))))
      (recur (/ v 1024) (inc i))
      (str (cond (zero? i) v (< v 100) (.toFixed v 1) :else (js/Math.round v)) " " (nth units i)))))

(defn rate-text [n] (str (bytes-text n) "/s"))

(defn percent
  "part of whole in whole percent (0 when whole is 0)."
  [part whole]
  (if (and whole (pos? whole)) (js/Math.round (* 100 (/ part whole))) 0))

(defn level
  "A bar's colour by its percent."
  [p]
  (cond (>= p 90) "red" (>= p 70) "amber" :else "ok"))

(defn uptime-text
  "Seconds since boot as \"12 d 4 h\", \"5 h 3 m\" or \"7 m\"."
  [secs]
  (let [m (js/Math.floor (/ secs 60)) h (js/Math.floor (/ m 60)) d (js/Math.floor (/ h 24))]
    (cond (pos? d) (str d " d " (mod h 24) " h")
          (pos? h) (str h " h " (mod m 60) " m")
          :else (str m " m"))))

(defn age-ms
  "How old a sample is now: its age when the server answered plus the time since the page got it."
  [sample got-at now]
  (+ (or (:age sample) 0) (max 0 (- now got-at))))

(def stale-ms
  "A sample older than this is greyed: the numbers no longer say how the machine is now (a sample is
  up to 2 s old when the server answers, and the page asks again 2 s after an answer)."
  8000)

(defn age-text [ms] (str (js/Math.round (/ ms 1000)) " s ago"))

(defn core-tip
  "A core's hover: its busy share and how it splits."
  [i {:keys [busy user sys iowait steal]}]
  (str "Core " i ": " busy "% busy · user " user "% · system " sys "% · waiting on disk " iowait "%"
       (when (pos? steal) (str " · stolen by the host " steal "%"))))

(defn core-stack
  "A core's bar from the bottom: user, system, steal (filled), iowait (not busy, drawn faint), as
  [[kind percent] …] without the empty ones."
  [{:keys [user sys steal iowait]}]
  (filterv (comp pos? second) [["user" user] ["sys" sys] ["steal" steal] ["iowait" iowait]]))

(defn head-text
  "The card's line under its name: cores, load and uptime."
  [{:keys [cpu uptime]}]
  (str (count (:cores cpu)) " cores · load " (apply str (interpose " " (map #(.toFixed % 2) (:load cpu))))
       " · up " (uptime-text uptime)))

(def ^:private kind-names
  {"front" "front" "runner" "runners" "host" "hosts" "chats" "chats" "hub" "hub"})

(defn claudia-text
  "Claudia's own processes on the machine: \"runners ×2\" and their resident memory."
  [{:keys [kind rss] n :count}]
  [(str (get kind-names kind kind) (when (> n 1) (str " ×" n))) (bytes-text rss)])

(def kind-tips
  {"chats" "Claude Code and everything it runs, started by the hosts: resident memory (RSS)"})

(defn gpu-text
  "A card's line: use, memory, temperature and power where reported."
  [{:keys [util memUsed memTotal temp power]}]
  (apply str (interpose " · " (remove nil? [(when util (str util "%"))
                                            (when memTotal (str (bytes-text memUsed) " / " (bytes-text memTotal)))
                                            (when temp (str temp " °C"))
                                            (when power (str (js/Math.round power) " W"))]))))

(defn temp-level
  "A temperature's colour: amber from 80 °C, red from 90 °C."
  [c]
  (cond (>= c 90) "red" (>= c 80) "amber" :else "ok"))

(defn machine-lines
  "What the project popup says of a chat's computer from its sample: the system, the kernel and
  architecture, the processor and its cores, the memory, the uptime. A part not reported is left out."
  [{:keys [host cpu memory uptime]}]
  (vec (remove nil?
               [(:os host)
                (when (:kernel host) (str "kernel " (:kernel host) (when (:arch host) (str " · " (:arch host)))))
                (when (:model cpu) (str (:model cpu) (when (seq (:cores cpu)) (str " · " (count (:cores cpu)) " cores"))))
                (when (:total memory) (str (bytes-text (:total memory)) " memory"))
                (when uptime (str "up " (uptime-text uptime)))])))
