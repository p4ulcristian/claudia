(ns claudia.root.frames
  "The root socket's frames (PROTOCOL.md, \"Root helper\"): one JSON object per line, both ways. The
  sudo shim sends the request first, then stdin as {in: base64} frames and {eof: true}; the helper
  answers {asking: true} (the person is asked), {running: true}, {out}/{err} (base64) and last
  {exit: n} or {refused: why}. Shared by the helper (claudia.root.main) and the shim
  (claudia.root.sudo)."
  (:require [claudia.shared.protocol :as p]
            [claudia.slave.lines :as lines]))

(def max-line
  "The longest line either side accepts (a request, or one frame of output): 1 MB of text."
  (* 1024 1024))

(def chunk-bytes
  "The most bytes one data frame carries; bigger chunks go as several frames."
  (* 256 1024))

(defn line
  "A frame (a map) as one line of the socket."
  [frame]
  (str (p/write-json frame) "\n"))

(defn read-frame
  "One line as a frame (a map with keyword keys), nil when it is not a JSON object."
  [text]
  (let [v (try (p/read-json text) (catch :default _ nil))]
    (when (map? v) v)))

(defn data-frames
  "The frames carrying buf (a Buffer) under k (:in, :out or :err), chunk-bytes each at most."
  [k ^js buf]
  (vec (for [start (range 0 (.-length buf) chunk-bytes)]
         {k (.toString (.subarray buf start (min (.-length buf) (+ start chunk-bytes))) "base64")})))

(defn frame-bytes
  "The bytes a data frame carries under k as a Buffer, nil when it carries none."
  [frame k]
  (let [v (get frame k)]
    (when (string? v) (js/Buffer.from v "base64"))))

(defn on-frames
  "Calls f with every frame that arrives on sock (a line that is not a JSON object comes as nil).
  A line longer than max-line ends the connection."
  [^js sock f]
  (let [buf (atom "")]
    (.setEncoding sock "utf8")
    (.on sock "data"
         (fn [text]
           (let [{:keys [tail] split :lines} (lines/split-lines (str @buf text))]
             (reset! buf tail)
             (doseq [l split] (f (read-frame l)))
             (when (> (count tail) max-line) (.destroy sock)))))))
