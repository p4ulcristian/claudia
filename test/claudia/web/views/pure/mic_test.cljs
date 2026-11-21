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

(deftest the-move-ring-starts-past-the-petals
  (is (not (mic/in-ring? 100 0)) "on a petal")
  (is (not (mic/in-ring? 87 87)) "123 px out, between the petals")
  (is (mic/in-ring? 0 (- mic/ring-in)) "the ring's inner edge")
  (is (mic/in-ring? -100 -100) "141 px out, in the ring")
  (is (mic/in-ring? 400 0) "past the ring"))

(deftest a-floating-mic-keeps-its-whole-flower-on-the-screen
  (is (= {:x 116 :y 704} (mic/clamp-spot {:x 10 :y 900} 1180 820)) "the petals' reach from the walls")
  (is (= {:x 1064 :y 400} (mic/clamp-spot {:x 1170 :y 400} 1180 820)) "over the edge column too")
  (is (= {:x 600 :y 400} (mic/clamp-spot {:x 600 :y 400} 1180 820)))
  (is (= {:x 100 :y 116} (mic/clamp-spot {:x 0 :y 0} 200 820)) "too narrow for the flower: the middle that way")
  (is (= {:x 590 :y 410} (mic/stored->spot (mic/spot->stored {:x 590 :y 410} 1180 820) 1180 820)))
  (is (= {:x 704 :y 1064} (mic/stored->spot {:fx 0.99 :fy 0.99} 820 1180)) "turned: clamped again")
  (is (nil? (mic/stored->spot {} 1180 820))))

(deftest a-floating-mic-starts-beside-the-edge-or-over-the-phone-composer
  (is (= {:x 982 :y 704} (mic/default-spot {:w 1180 :h 820 :edge-w 88 :r 32})) "bottom right beside the edge, the flower whole")
  (is (= {:x 195 :y 728} (mic/default-spot {:w 390 :h 844 :edge-w 0 :composer-top 792 :r 36}))
      "phone: the bottom middle, raised so the down petal fits")
  (is (= {:x 195 :y 500} (mic/default-spot {:w 390 :h 844 :edge-w 0 :composer-top 544 :r 36}))
      "phone: just over a taller composer"))

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

(deftest a-clip-pill-says-where-it-goes-and-how-it-stands
  (is (= "↑" (mic/way-mark :send)))
  (is (= "+" (mic/way-mark :add)))
  (is (= "⏵" (mic/way-mark :now)))
  (is (= "Clip 1, 4 s, send: heard, waits its turn" (mic/clip-label {:n 0 :secs 4 :dir :send :state :heard} 0)))
  (is (= "Clip 3, 6 s, add: didn't come through" (mic/clip-label {:n 2 :secs 6 :dir :add :state :failed} 0)))
  (is (re-find #"^Clip 2, 2 s, now: hearing " (mic/clip-label {:n 1 :secs 2 :dir :now :state :hearing :t0 0} 3000))))

(deftest a-chat-s-last-words-go-with-a-clip
  (let [msgs [{:role "user" :text "Is PostHog slow?"}
              {:role "tool" :text "ran: grep posthog"}
              {:role "assistant" :text " The PostHog replay keeps a frame loop running. "}
              {:role "assistant" :text ""}]]
    (is (= "Is PostHog slow?\nThe PostHog replay keeps a frame loop running." (mic/listen-context msgs))
        "people's and Claude's words, oldest first; tool steps and empty ones left out")
    (is (= "" (mic/listen-context nil)) "a new chat has none")
    (is (= "m3\nm4\nm5\nm6\nm7\nm8" (mic/listen-context (for [i (range 1 9)] {:role "user" :text (str "m" i)})))
        "the last six messages"))
  (let [long (apply str (repeat 500 "word "))
        out (mic/listen-context [{:role "user" :text (str "START " long "END")}])]
    (is (<= (count out) mic/context-chars) "cut to its last context-chars")
    (is (re-find #"^word " out) "starting at a whole word")
    (is (re-find #"END$" out) "the newest words kept")))

(deftest a-circle-skips-where-the-mic-adds
  (is (= :skip (mic/circle-way (mic/gesture -60 0))) "left on a circle: skip all")
  (is (= [:send :cancel :now] (map mic/circle-way [:send :cancel :now])) "the other ways as on the mic"))

(defn- near [a b] (< (js/Math.abs (- a b)) 1e-9))

(deftest move-is-written-along-the-ring-upright
  (let [pi js/Math.PI
        top (mic/arc-letters [10 10] 100 (- (/ pi 2)))
        bottom (mic/arc-letters [10 10] 100 (/ pi 2))]
    (is (every? true? (map near (map :angle top) [(- (- (/ pi 2)) 0.05) (+ (- (/ pi 2)) 0.05)])) "over the middle: left to right, clockwise")
    (is (every? true? (map near (map :turn top) [-0.05 0.05])) "each letter turned along the ring, its top outwards")
    (is (every? true? (map near (map :angle bottom) [(+ (/ pi 2) 0.05) (- (/ pi 2) 0.05)])) "under it: left to right, counterclockwise")
    (is (every? true? (map near (map :turn bottom) [0.05 -0.05])) "its top inwards, so it reads upright")))

(deftest a-tap-while-a-chat-is-read-skips-its-message
  (let [blue ["s" "blue"] red ["s" "red"]]
    (is (= {:phase :idle :heard [blue ["m1"]] :play blue}
           (mic/tap-plan {:circle blue :reading blue :reading-msg "m1"}))
        "on the chat's own circle: m1 heard, the next of blue plays (it used to replay m1)")
    (is (= {:phase :idle :heard [blue ["m1"]] :play blue}
           (mic/tap-plan {:circle nil :reading blue :reading-msg "m1"}))
        "on the mic: the same skip")
    (is (= {:phase :idle :play red} (mic/tap-plan {:circle red :reading blue :reading-msg "m1"}))
        "another chat's circle: that chat plays, nothing is marked heard")
    (is (= {:phase :idle :play blue} (mic/tap-plan {:circle nil :reading blue :reading-msg nil}))
        "a reply's play button (no message id): skipped, nothing to mark")
    (is (= {:phase :tap} (mic/tap-plan {:circle nil :reading nil}))
        "nothing read: the hint to hold")
    (is (= {:phase :idle :play red} (mic/tap-plan {:circle red :reading nil}))
        "a circle with nothing read plays it")))
