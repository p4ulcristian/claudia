(ns claudia.slave.lines
  "JSON lines over a stream socket (host <-> runner): one JSON object per line.")

(defn on-lines
  "Calls f with every complete line that arrives on sock (UTF-8 text, the newline taken off)."
  [^js sock f]
  (let [buf (atom "")]
    (.setEncoding sock "utf8")
    (.on sock "data"
         (fn [chunk]
           (swap! buf str chunk)
           (loop []
             (let [s @buf
                   i (.indexOf s "\n")]
               (when (>= i 0)
                 (reset! buf (subs s (inc i)))
                 (let [line (subs s 0 i)]
                   (when (seq line)
                     (try (f line)
                          (catch :default e (js/console.error "bad line:" e)))))
                 (recur))))))))

(defn write-line
  "Writes one JS object as a line. Node's socket queues what does not fit at once, in order."
  [^js sock obj]
  (when (and sock (not (.-destroyed sock)))
    (.write sock (str (js/JSON.stringify obj) "\n"))))
