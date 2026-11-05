(ns claudia.node.audio
  "Sound as the hub and the runner pass it around: raw 16-bit mono PCM (the narrator's clips) and
  the WAV file around it, and clips laid on one track at given times (a story video's sound).")

(def bytes-per-sample 2)

(defn wav
  "Raw 16-bit mono PCM (a Buffer) at rate samples per second as a WAV file: the 44-byte RIFF
  header, then the samples."
  [^js pcm rate]
  (let [out (js/Buffer.alloc (+ 44 (.-length pcm)))]
    (.write out "RIFF" 0 "ascii") (.writeUInt32LE out (+ 36 (.-length pcm)) 4)
    (.write out "WAVE" 8 "ascii") (.write out "fmt " 12 "ascii")
    (.writeUInt32LE out 16 16) (.writeUInt16LE out 1 20) (.writeUInt16LE out 1 22)
    (.writeUInt32LE out rate 24) (.writeUInt32LE out (* rate bytes-per-sample) 28)
    (.writeUInt16LE out bytes-per-sample 32) (.writeUInt16LE out 16 34)
    (.write out "data" 36 "ascii") (.writeUInt32LE out (.-length pcm) 40)
    (.copy pcm out 44)
    out))

(defn samples
  "A PCM Buffer's samples as an Int16Array of their own."
  [^js pcm]
  (let [n (js/Math.floor (/ (.-length pcm) bytes-per-sample))
        out (js/Int16Array. n)]
    (dotimes [i n] (aset out i (.readInt16LE pcm (* i bytes-per-sample))))
    out))

(defn- clamp16 [x] (max -32768 (min 32767 x)))

(defn lay
  "One track, n samples long at rate, with each clip laid where it starts: placed is [[at-seconds
  Int16Array] …]; clips that meet are added (and kept within 16 bits), what runs past the end is
  cut. -> a PCM Buffer."
  [placed n rate]
  (let [track (js/Int32Array. n)]
    (doseq [[at ^js clip] placed
            :let [start (js/Math.round (* at rate))]]
      (dotimes [i (.-length clip)]
        (let [j (+ start i)]
          (when (< -1 j n) (aset track j (+ (aget track j) (aget clip i)))))))
    (let [out (js/Buffer.alloc (* n bytes-per-sample))]
      (dotimes [j n] (.writeInt16LE out (clamp16 (aget track j)) (* j bytes-per-sample)))
      out)))
