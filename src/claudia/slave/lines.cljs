(ns claudia.slave.lines
  "JSON lines over a stream socket (host <-> runner): one JSON object per line.")

(defn split-lines
  "Text that arrived so far as {:lines the complete, non-empty lines (newline taken off), :tail the
  unfinished end}."
  [text]
  (let [i (.lastIndexOf text "\n")]
    {:lines (if (neg? i) [] (filterv seq (.split (subs text 0 i) "\n")))
     :tail (subs text (inc i))}))

(defn on-lines
  "Calls f with every complete line that arrives on sock (UTF-8 text, the newline taken off)."
  [^js sock f]
  (let [buf (atom "")]
    (.setEncoding sock "utf8")
    (.on sock "data"
         (fn [text]
           (let [{:keys [lines tail]} (split-lines (str @buf text))]
             (reset! buf tail)
             (doseq [line lines]
               (try (f line)
                    (catch :default e (js/console.error "bad line:" e)))))))))

(defn write-line
  "Writes one JS object as a line. Node's socket queues what does not fit at once, in order."
  [^js sock obj]
  (when (and sock (not (.-destroyed sock)))
    (.write sock (str (js/JSON.stringify obj) "\n"))))
