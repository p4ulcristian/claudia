(ns claudia.node.audio-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.node.audio :as audio]))

(defn- pcm [& xs]
  (let [b (js/Buffer.alloc (* 2 (count xs)))]
    (doseq [[i x] (map-indexed vector xs)] (.writeInt16LE b x (* 2 i)))
    b))

(defn- values [^js b] (vec (for [i (range (/ (.-length b) 2))] (.readInt16LE b (* 2 i)))))

(deftest a-wav-file
  (let [w (audio/wav (pcm 1 -1) 24000)]
    (is (= 48 (.-length w)))
    (is (= "RIFF" (.toString w "ascii" 0 4)))
    (is (= 24000 (.readUInt32LE w 24)))
    (is (= 48000 (.readUInt32LE w 28)))
    (is (= 4 (.readUInt32LE w 40)))
    (is (= [1 -1] (values (.subarray w 44))))))

(deftest pcm-as-samples
  (is (= [5 -7 32767] (vec (array-seq (audio/samples (pcm 5 -7 32767)))))))

(deftest clips-on-one-track
  (testing "each clip where it starts (rate 2: 1 s = 2 samples), silence between"
    (is (= [0 0 1 2 0 0 3 0] (values (audio/lay [[1 (js/Int16Array. #js [1 2])] [3 (js/Int16Array. #js [3])]] 8 2)))))
  (testing "clips that meet add up, within 16 bits; past the end is cut"
    (is (= [32767 -4] (values (audio/lay [[0 (js/Int16Array. #js [30000 -2 9])] [0 (js/Int16Array. #js [30000 -2])]] 2 2))))))
