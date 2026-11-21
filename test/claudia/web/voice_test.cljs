(ns claudia.web.voice-test
  "The page plays the hub's voice as it comes: chunks cut at whole 16-bit samples."
  (:require [cljs.test :refer [deftest is async]]
            [claudia.web.voice :as voice]))

(defn- u8 [& xs] (js/Uint8Array.from (clj->js (vec xs))))
(defn- v [b] (vec (array-seq (js/Uint8Array. b))))

(deftest split-even-carries-the-odd-byte
  (let [[whole carry] (voice/split-even (u8) (u8 1 2 3))]
    (is (= [1 2] (v whole)))
    (is (= [3] (v carry)) "a chunk ending mid-sample keeps its last byte")
    (let [[whole2 carry2] (voice/split-even carry (u8 4 5 6))]
      (is (= [3 4 5 6] (v whole2)) "the kept byte starts the next piece")
      (is (= [] (v carry2)))))
  (let [[whole carry] (voice/split-even (u8 7) (u8))]
    (is (= [] (v whole)))
    (is (= [7] (v carry))))
  (let [[whole carry] (voice/split-even (u8 7) (u8 8))]
    (is (= [7 8] (v whole)))
    (is (= [] (v carry)))))

(defn- reader-of [chunks]
  (let [left (atom chunks)]
    #js {:read (fn []
                 (if-let [c (first @left)]
                   (do (swap! left rest) (js/Promise.resolve #js {:done false :value (apply u8 c)}))
                   (js/Promise.resolve #js {:done true})))}))

(deftest read-pieces-gives-whole-samples-in-order
  (async done
         (let [pieces (atom [])]
           (.then (voice/read-pieces (reader-of [[1] [2 3] [4] [5 6 7 8]]) #(swap! pieces conj (v %)))
                  (fn [_]
                    (is (= [[1 2] [3 4] [5 6 7 8]] @pieces) "no piece split a sample, none empty")
                    (done))))))
