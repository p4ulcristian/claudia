(ns claudia.web.pcm
  "Raw sound for the mic (README decision 20): the samples the microphone gives (Float32Array
  chunks, -1..1), a rolling buffer of the last moments before a press, and the clip as 16 kHz mono
  16-bit WAV, the rate the listen service hears at. Every function returns new arrays; none changes
  what it was given.")

(def rate "The clip's sample rate (the listen service decodes to this anyway)." 16000)

(defn size
  "Samples in chunks."
  [chunks]
  (reduce + 0 (map #(.-length ^js %) chunks)))

(defn ring-push
  "The rolling buffer with chunk added at the end and the oldest chunks dropped while what stays
  still holds at least limit samples without them."
  [chunks chunk limit]
  (loop [cs (conj (vec chunks) chunk)]
    (if (and (> (count cs) 1) (>= (- (size cs) (.-length ^js (first cs))) limit))
      (recur (subvec cs 1))
      cs)))

(defn tail
  "The last n samples of chunks, as chunks (the first one cut to fit)."
  [chunks n]
  (loop [cs (reverse chunks) taken () need n]
    (if (or (empty? cs) (<= need 0))
      (vec taken)
      (let [^js c (first cs) len (.-length c)]
        (if (<= len need)
          (recur (rest cs) (conj taken c) (- need len))
          (vec (conj taken (.subarray c (- len need)))))))))

(defn joined
  "The chunks as one Float32Array."
  [chunks]
  (let [out (js/Float32Array. (size chunks))]
    (reduce (fn [at ^js c] (.set out c at) (+ at (.-length c))) 0 chunks)
    out))

(defn- kernel
  "The low-pass weight of an input sample d samples from the output point: a Hann-windowed sinc
  with cutoff fc (cycles per input sample), half-width w samples."
  [d fc w]
  (if (zero? d)
    (* 2 fc)
    (* (/ (js/Math.sin (* 2 js/Math.PI fc d)) (* js/Math.PI d))
       (+ 0.5 (* 0.5 (js/Math.cos (/ (* js/Math.PI d) w)))))))

(defn- sample-at
  "The low-passed value of input at fractional position t."
  [^js input t fc w]
  (let [lo (max 0 (js/Math.ceil (- t w))) hi (min (dec (.-length input)) (js/Math.floor (+ t w)))]
    (loop [i lo acc 0]
      (if (> i hi) acc (recur (inc i) (+ acc (* (aget input i) (kernel (- t i) fc w))))))))

(defn resampled
  "input (Float32Array at from Hz) at to Hz: low-passed below the new Nyquist, so nothing folds back."
  [^js input from to]
  (if (= from to)
    (js/Float32Array. input)
    (let [ratio (/ from to)
          n (js/Math.floor (/ (.-length input) ratio))
          fc (* 0.45 (min 1 (/ to from)))
          w (* 8 (max 1 ratio))
          out (js/Float32Array. n)]
      (dotimes [i n] (aset out i (sample-at input (* i ratio) fc w)))
      out)))

(defn- write-ascii! [^js view at text]
  (dotimes [i (count text)] (.setUint8 view (+ at i) (.charCodeAt text i))))

(defn- write-header! [^js view n sample-rate]
  (write-ascii! view 0 "RIFF")
  (.setUint32 view 4 (+ 36 (* 2 n)) true)
  (write-ascii! view 8 "WAVEfmt ")
  (.setUint32 view 16 16 true)
  (.setUint16 view 20 1 true)
  (.setUint16 view 22 1 true)
  (.setUint32 view 24 sample-rate true)
  (.setUint32 view 28 (* 2 sample-rate) true)
  (.setUint16 view 32 2 true)
  (.setUint16 view 34 16 true)
  (write-ascii! view 36 "data")
  (.setUint32 view 40 (* 2 n) true))

(defn wav
  "samples (Float32Array, -1..1) as a mono 16-bit PCM WAV file (Uint8Array)."
  [^js samples sample-rate]
  (let [n (.-length samples)
        buf (js/ArrayBuffer. (+ 44 (* 2 n)))
        view (js/DataView. buf)]
    (write-header! view n sample-rate)
    (dotimes [i n]
      (let [s (max -1 (min 1 (aget samples i)))]
        (.setInt16 view (+ 44 (* 2 i)) (js/Math.round (* s (if (neg? s) 32768 32767))) true)))
    (js/Uint8Array. buf)))
