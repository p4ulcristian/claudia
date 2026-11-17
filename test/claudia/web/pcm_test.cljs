(ns claudia.web.pcm-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.mic :as mic]
            [claudia.web.pcm :as pcm]))

(defn- f32 [& xs] (js/Float32Array.from (clj->js xs)))
(defn- v [^js a] (vec (array-seq a)))

(deftest ring-keeps-at-least-the-limit
  (let [r (-> [] (pcm/ring-push (f32 1 2) 3) (pcm/ring-push (f32 3 4) 3) (pcm/ring-push (f32 5 6) 3))]
    (is (= [[3 4] [5 6]] (mapv v r)) "the oldest chunk goes once the rest still holds 3")
    (is (= 4 (pcm/size r))))
  (is (= [[1 2 3 4 5]] (mapv v (pcm/ring-push [] (f32 1 2 3 4 5) 2))) "a lone chunk stays whole"))

(deftest tail-cuts-the-first-chunk-to-fit
  (is (= [[2] [3 4]] (mapv v (pcm/tail [(f32 1 2) (f32 3 4)] 3))))
  (is (= [[1 2] [3 4]] (mapv v (pcm/tail [(f32 1 2) (f32 3 4)] 9))))
  (is (= [] (pcm/tail [(f32 1 2)] 0))))

(deftest joined-in-order
  (is (= [1 2 3] (v (pcm/joined [(f32 1 2) (f32 3)]))))
  (is (= [] (v (pcm/joined [])))))

(deftest resampled-keeps-a-slow-tone-and-the-length
  (let [from 48000 n 4800
        tone (js/Float32Array.from (clj->js (for [i (range n)] (js/Math.sin (/ (* 2 js/Math.PI 300 i) from)))))
        out (pcm/resampled tone from 16000)]
    (is (= 1600 (.-length out)))
    (testing "a 300 Hz tone comes through at about its height (away from the edges)"
      (is (< 0.9 (apply max (subvec (v out) 100 1500)) 1.05))))
  (is (= [1 2] (v (pcm/resampled (f32 1 2) 16000 16000)))))

(deftest resampled-drops-what-would-fold-back
  (let [from 48000 n 4800
        tone (js/Float32Array.from (clj->js (for [i (range n)] (js/Math.sin (/ (* 2 js/Math.PI 12000 i) from)))))
        out (pcm/resampled tone from 16000)]
    (is (> 0.05 (apply max (map js/Math.abs (subvec (v out) 100 1500)))) "12 kHz is above 8 kHz: gone")))

(deftest wav-header-and-samples
  (let [b (pcm/wav (f32 0 1 -1) 16000)
        view (js/DataView. (.-buffer b))
        text (fn [at n] (apply str (map #(char (aget b (+ at %))) (range n))))]
    (is (= 50 (.-length b)))
    (is (= "RIFF" (text 0 4)))
    (is (= "WAVE" (text 8 4)))
    (is (= 16000 (.getUint32 view 24 true)))
    (is (= 6 (.getUint32 view 40 true)))
    (is (= [0 32767 -32768] (mapv #(.getInt16 view (+ 44 (* 2 %)) true) (range 3))))))

(deftest mic-warm-state
  (let [w (mic/heard {:ring [] :clip nil} (f32 1 2) 10 1000)]
    (is (= [[1 2]] (mapv v (:ring w))))
    (is (= 1000 (:last w)) "when the last samples came")
    (is (nil? (:clip w)) "no clip runs: only the buffer"))
  (let [w (mic/began {:ring [(f32 1 2) (f32 3 4)] :clip nil :timer 7} 3)]
    (is (= [[2] [3 4]] (mapv v (:clip w))) "a clip starts with the last moments")
    (is (nil? (:timer w)))
    (is (= [[2] [3 4] [5]] (mapv v (:clip (mic/heard w (f32 5) 10 0)))))))

(deftest mic-sounding
  (is (not (mic/sounding? {:ring []} 5000)) "never a sample: no sound")
  (is (mic/sounding? {:last 4800} 5000) "samples 200 ms ago")
  (is (not (mic/sounding? {:last 4000} 5000)) "nothing for a second: no sound"))

(deftest mic-retry
  (is (mic/retry? mic/no-sound 1) "a silent first opening is tried again")
  (is (mic/retry? mic/no-sound 2) "and a silent second one")
  (is (not (mic/retry? mic/no-sound 3)) "the third silent opening fails the press")
  (is (not (mic/retry? "The microphone didn't answer: try again." 1)) "only no sound is retried"))
