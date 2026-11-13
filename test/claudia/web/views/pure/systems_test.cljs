(ns claudia.web.views.pure.systems-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.views.pure.systems :as pure]))

(deftest sizes-in-binary-units
  (is (= "512 B" (pure/bytes-text 512)))
  (is (= "1.5 KiB" (pure/bytes-text 1536)))
  (is (= "13.1 GiB" (pure/bytes-text (* 13.1 1024 1024 1024))))
  (is (= "950 GiB" (pure/bytes-text (* 950 1024 1024 1024))))
  (is (= "2.0 MiB/s" (pure/rate-text (* 2 1024 1024)))))

(deftest shares-and-colours
  (is (= 41 (pure/percent 41 100)))
  (is (= 0 (pure/percent 5 0)))
  (is (= ["ok" "amber" "red"] (map pure/level [10 75 95])))
  (is (= ["ok" "amber" "red"] (map pure/temp-level [50 85 92]))))

(deftest times
  (is (= "7 m" (pure/uptime-text 420)))
  (is (= "5 h 3 m" (pure/uptime-text (+ (* 5 3600) 180))))
  (is (= "12 d 4 h" (pure/uptime-text (+ (* 12 86400) (* 4 3600)))))
  (is (= 2500 (pure/age-ms {:age 500} 1000 3000)))
  (is (= "3 s ago" (pure/age-text 2500))))

(deftest cores
  (is (= [["user" 30] ["iowait" 2]] (pure/core-stack {:user 30 :sys 0 :steal 0 :iowait 2})))
  (is (= "Core 1: 32% busy · user 30% · system 2% · waiting on disk 0%"
         (pure/core-tip 1 {:busy 32 :user 30 :sys 2 :iowait 0 :steal 0})))
  (is (= "12 cores · load 1.80 0.50 0.25 · up 7 m"
         (pure/head-text {:cpu {:cores (repeat 12 {}) :load [1.8 0.5 0.25]} :uptime 420}))))

(deftest claudia-and-cards
  (is (= ["runners ×2" "200 MiB"] (pure/claudia-text {:kind "runner" :count 2 :rss (* 200 1024 1024)})))
  (is (= "7% · 1000 MiB / 24.0 GiB · 41 °C · 31 W"
         (pure/gpu-text {:util 7 :memUsed (* 1000 1048576) :memTotal (* 24576 1048576) :temp 41 :power 30.5})))
  (is (= "" (pure/gpu-text {}))))

(deftest the-project-popup-names-the-machine
  (is (= ["Arch Linux" "kernel 7.2.15 · x64" "AMD Ryzen 9 · 2 cores" "16.0 GiB memory" "up 1 d 0 h"]
         (pure/machine-lines {:host {:os "Arch Linux" :kernel "7.2.15" :arch "x64"}
                              :cpu {:model "AMD Ryzen 9" :cores [{} {}]}
                              :memory {:total (* 16 1024 1024 1024)} :uptime 86400})))
  (is (= [] (pure/machine-lines {}))))
