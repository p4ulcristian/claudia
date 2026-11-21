(ns claudia.node.ports
  "Listening ports for System (README decision 96, PROTOCOL.md `Port`): pure functions over `ss
  -Htlnup` (Linux) and `lsof -nP -F pcPn` (macOS), and who an address reaches."
  (:require [clojure.string :as str]))

(defn split-address
  "A socket's local address as ss or lsof write it (`127.0.0.1:22`, `[::]:22`, `*:53`,
  `127.0.0.53%lo:53`) as [addr port], the brackets and the interface dropped; nil when it has no
  port."
  [text]
  (when-let [[_ addr port] (re-matches #"^(.*):(\d+|\*)$" (str text))]
    (when (re-matches #"\d+" port)
      [(-> addr (str/replace #"^\[|\]$" "") (str/replace #"%.*$" "")) (js/parseInt port 10)])))

(defn- ipv4-octets [addr]
  (when-let [m (re-matches #"^(?:::ffff:)?(\d+)\.(\d+)\.(\d+)\.(\d+)$" addr)]
    (mapv #(js/parseInt % 10) (rest m))))

(defn reach-of
  "Who may reach an address: \"all\" (0.0.0.0, :: or *), \"headscale\" (100.64.0.0/10 or
  fd7a:115c:a1e0::/48), \"local\" (127.0.0.0/8 or ::1), else \"lan\"."
  [addr]
  (let [a (str/lower-case (str addr))
        [o1 o2] (ipv4-octets a)]
    (cond (#{"0.0.0.0" "::" "*" ""} a) "all"
          (and (= o1 100) (<= 64 o2 127)) "headscale"
          (str/starts-with? a "fd7a:115c:a1e0:") "headscale"
          (or (= o1 127) (= a "::1")) "local"
          :else "lan")))

(defn- first-process
  "ss's users:((\"name\",pid=1,fd=3),…) as {:name :pid} of the first, nil when there is none."
  [text]
  (when-let [[_ prog pid] (re-find #"users:\(\(\"([^\"]*)\",pid=(\d+)" (str text))]
    {:name prog :pid (js/parseInt pid 10)}))

(defn ss-row
  "One `ss -Htlnup` line as a Port without its owner, nil for another line."
  [line]
  (let [[proto _ _ _ local & more] (str/split (str/trim line) #"\s+")
        [addr port] (split-address local)]
    (when (and port (#{"tcp" "udp"} proto))
      (merge {:proto proto :port port :addr addr :reach (reach-of addr)}
             (first-process (str/join " " more))))))

(defn one-per-address
  "Port rows, one per protocol, port and address (a row with a process wins), in port order."
  [rows]
  (->> (group-by (juxt :proto :port :addr) rows)
       vals
       (map (fn [rs] (or (first (filter :pid rs)) (first rs))))
       (sort-by (juxt :port :proto :addr))
       vec))

(defn ss-ports
  "`ss -Htlnup`'s text as Ports without owners."
  [text]
  (one-per-address (keep ss-row (str/split-lines (str text)))))

(defn- lsof-step
  "One `lsof -F pcPn` line folded into {:cur {:pid :name :proto} :rows […]}."
  [{:keys [cur] :as acc} line]
  (let [k (first line) v (subs line 1)]
    (case k
      "p" (assoc acc :cur {:pid (js/parseInt v 10)})
      "c" (assoc-in acc [:cur :name] v)
      "P" (assoc-in acc [:cur :proto] (str/lower-case v))
      "n" (if-let [[addr port] (when-not (str/includes? v "->") (split-address v))]
            (update acc :rows conj (assoc cur :port port :addr addr :reach (reach-of addr)))
            acc)
      acc)))

(defn lsof-ports
  "`lsof -nP -iTCP -sTCP:LISTEN -F pcPn` and `lsof -nP -iUDP -F pcPn` texts as Ports without owners
  (a connected UDP socket, `a->b`, is not a bound port)."
  [& texts]
  (->> (mapcat #(str/split-lines (str %)) texts)
       (remove str/blank?)
       (reduce lsof-step {:cur {} :rows []})
       :rows
       (filter #(#{"tcp" "udp"} (:proto %)))
       one-per-address))
