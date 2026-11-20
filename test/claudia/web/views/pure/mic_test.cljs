(ns claudia.web.views.pure.mic-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.views.pure.mic :as mic]))

(deftest heard-text-joins-the-draft
  (is (= "hello" (mic/with-heard "" " hello ")))
  (is (= "fix it please" (mic/with-heard "fix it" "please")))
  (is (= "line\nnext" (mic/with-heard "line\n" "next")))
  (is (= "kept" (mic/with-heard "kept" "  ")))
  (is (= "x" (mic/with-heard nil "x"))))

(defn- spectrum-with
  "A 256-bin spectrum at 48 kHz (93.75 Hz a bin): v in the bins from..to Hz, 0 elsewhere."
  [v from-hz to-hz]
  (vec (for [i (range 256) :let [hz (* i 93.75)]] (if (<= from-hz hz to-hz) v 0))))

(deftest bars-are-mirrored-levels
  (let [levels (mic/bar-levels (vec (repeat 256 255)) 48000)]
    (is (= mic/bar-count (count levels)))
    (is (every? #(= 1 %) levels) "loud everywhere: every bar full"))
  (let [levels (mic/bar-levels (spectrum-with 255 0 300) 48000)]
    (is (= (first levels) (last levels)) "mirrored")
    (is (pos? (first levels)) "a low voice lights the first bars")
    (is (zero? (nth levels 20)) "and not the high ones")))

(deftest bars-show-only-the-voice
  (is (every? zero? (mic/bar-levels (vec (repeat 256 70)) 48000)) "room noise (-80 dB): no bar")
  (is (every? zero? (mic/bar-levels (spectrum-with 255 4500 24000) 48000)) "above 4 kHz: no bar")
  (is (every? pos? (take 24 (mic/bar-levels (spectrum-with 220 90 4000) 44100)))
      "a voice over the whole band lights every bar, at 44.1 kHz too"))

(deftest bars-ease
  (is (= [0.6 0.8] (mic/eased [0 1] [1 0])))
  (is (= [0.6] (mic/eased nil [1]))))

(deftest petals-up-send-right-cancel-down-now-left-add
  (is (= :send (mic/gesture 0 0)) "let go in place")
  (is (= :send (mic/gesture 0 (- mic/drag-px))) "up")
  (is (= :send (mic/gesture 30 -90)) "more up than right")
  (is (= :send (mic/gesture 0 (- 1 mic/drag-px))) "not far enough up: still in place")
  (is (= :cancel (mic/gesture mic/drag-px 0)) "right")
  (is (= :cancel (mic/gesture 200 -60)) "more right than up")
  (is (= :send (mic/gesture (dec mic/drag-px) 0)) "not far enough right")
  (is (= :now (mic/gesture 0 80)) "down")
  (is (= :now (mic/gesture -30 90)) "more down than left")
  (is (= :send (mic/gesture 0 (dec mic/drag-px))) "not far enough down")
  (is (= :add (mic/gesture (- mic/drag-px) 0)) "left")
  (is (= :add (mic/gesture -90 60)) "more left than down")
  (is (= :send (mic/gesture (- 1 mic/drag-px) 0)) "not far enough left"))

(deftest the-half-flower-reads-the-left-half
  (is (= :send (mic/half-gesture 0 0)) "in place")
  (is (= :send (mic/half-gesture 60 -60)) "to the right of the mic")
  (is (= :cancel (mic/half-gesture 0 -80)) "up")
  (is (= :cancel (mic/half-gesture -30 -80)) "up, a little left")
  (is (= :add (mic/half-gesture -80 -30)) "left, a little up")
  (is (= :send (mic/half-gesture -80 30)) "left, a little down")
  (is (= :now (mic/half-gesture -30 80)) "down, a little left")
  (is (= :send (mic/half-gesture -30 0)) "not far enough"))

(deftest the-move-ring-starts-past-the-petals
  (is (not (mic/in-ring? 100 0)) "on a petal")
  (is (not (mic/in-ring? 87 87)) "123 px out, between the petals")
  (is (mic/in-ring? 0 (- mic/ring-in)) "the ring's inner edge")
  (is (mic/in-ring? -100 -100) "141 px out, in the ring")
  (is (mic/in-ring? 400 0) "past the ring"))

(deftest a-moved-button-stays-on-the-screen
  (is (= {:x 32 :y 788} (mic/clamp-spot {:x 10 :y 900} 1180 820 32)) "only the button's radius from the walls")
  (is (= {:x 1148 :y 400} (mic/clamp-spot {:x 1170 :y 400} 1180 820 32)) "over the rail too")
  (is (= {:x 600 :y 400} (mic/clamp-spot {:x 600 :y 400} 1180 820 32)))
  (is (= {:x 590 :y 410} (mic/stored->spot (mic/spot->stored {:x 590 :y 410} 1180 820) 1180 820 32)))
  (is (= {:x 788 :y 1148} (mic/stored->spot {:fx 0.99 :fy 0.99} 820 1180 32)) "turned: clamped again")
  (is (nil? (mic/stored->spot {} 1180 820 32))))

(deftest circles-overlap
  (is (= 0 (mic/overlap-area 64 32 32)) "touching")
  (is (< (js/Math.abs (- (mic/overlap-area 0 32 32) (* js/Math.PI 1024))) 1e-9) "one on the other")
  (is (< (js/Math.abs (- (mic/overlap-area 5 10 30) (* js/Math.PI 100))) 1e-9) "the small one inside")
  (is (< (js/Math.abs (- (mic/overlap-area 1 1 1) (- (/ (* 2 js/Math.PI) 3) (/ (js/Math.sqrt 3) 2)))) 1e-9)
      "two unit circles a radius apart"))

(deftest home-only-when-it-covers-half-its-slot
  (let [slot {:x 1136 :y 410 :r 32}]
    (is (mic/home? {:x 1136 :y 410 :r 32} slot) "on it")
    (is (mic/home? {:x 1112 :y 410 :r 32} slot) "24 px off: over half")
    (is (not (mic/home? {:x 1108 :y 410 :r 32} slot)) "28 px off: under half")
    (is (not (mic/home? {:x 1136 :y 100 :r 32} slot)) "elsewhere on the rail it stays")
    (is (not (mic/home? {:x 600 :y 400 :r 32} slot)))
    (is (not (mic/home? {:x 600 :y 400 :r 32} nil)) "no slot"))
  (is (= :left (mic/flower-side 1136 1180 88)) "in the rail: half, to the left")
  (is (nil? (mic/flower-side 968 1180 88)) "out of the rail: whole"))

(deftest heard-clips-go-out-in-spoken-order
  (let [l0 {:next 0 :held {}}
        [l1 out1] (mic/in-order l0 1 {:text "second"})
        [l2 out2] (mic/in-order l1 0 {:text "first"})]
    (is (= [] out1) "the second clip, heard first, waits")
    (is (= [{:text "first"} {:text "second"}] out2) "the first one lets both out, in order")
    (is (= {:next 2 :held {}} l2)))
  (let [[l1 out1] (mic/in-order {:next 0 :held {}} 1 {:text "b"})
        [_ out2] (mic/in-order l1 0 nil)]
    (is (= [] out1))
    (is (= [{:text "b"}] out2) "a failed earlier clip holds nothing back")))

(deftest the-clock-shows-milliseconds
  (is (= "0.000" (mic/clock-text 0)))
  (is (= "0.007" (mic/clock-text 7.9)))
  (is (= "12.345" (mic/clock-text 12345)))
  (is (= "1:02.345" (mic/clock-text 62345)))
  (is (= "0.000" (mic/clock-text -5)) "never below 0"))

(deftest speak-here-follows-the-mic-and-the-turn
  (let [front {:port "MicrophoneBuiltIn" :location "Upper" :orientation "Front"}
        bottom {:port "MicrophoneBuiltIn" :location "Lower" :orientation "Bottom"}]
    (is (= :top (mic/speak-edge front 0)))
    (is (= :left (mic/speak-edge front 90)) "turned left: the device's top is on the left")
    (is (= :right (mic/speak-edge front 270)))
    (is (= :right (mic/speak-edge front -90)))
    (is (= :bottom (mic/speak-edge bottom 0)))
    (is (= :top (mic/speak-edge bottom 180)))
    (is (= :top (mic/speak-edge {:port "MicrophoneBuiltIn" :orientation "Back"} 0)) "no location: the back faces from the top")
    (is (nil? (mic/speak-edge {:port "BluetoothHFP"} 0)) "AirPods: nothing to show")
    (is (nil? (mic/speak-edge nil 0)))))

(deftest the-screens-turn-trusts-the-app-first
  (is (= 90 (mic/turn-angle 90 0 0)))
  (is (= 270 (mic/turn-angle nil -90 0)) "window.orientation -90: turned right")
  (is (= 90 (mic/turn-angle nil 90 0)))
  (is (= 0 (mic/turn-angle nil nil 0)))
  (is (= 0 (mic/turn-angle nil nil nil))))

(deftest a-resting-finger-may-tremble-a-little
  (is (true? (mic/still? {:x 100 :y 100} 105 103)))
  (is (false? (mic/still? {:x 100 :y 100} 110 100))))
