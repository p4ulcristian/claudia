(ns claudia.web.scene3d.city-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.scene3d.city :as city]))

(def files [["src/a/one.clj" 100] ["src/a/two.clj" 50] ["src/b/three.clj" 25] ["README.md" 10] ["empty" 0]])

(deftest the-tree
  (is (= [["a/one.clj" 100] ["a/two.clj" 50] ["b/three.clj" 25]] (city/under files "src/")))
  (let [root (city/tree (city/under files ""))]
    (is (= 185 (:lines root)))
    (is (= 4 (:count root)))
    (is (= ["src"] (map :name (:dirs root))))
    (is (= [["README.md" 10 "README.md"]] (:files root)))
    (is (= ["src/a" "src/b"] (map :path (:dirs (first (:dirs root))))))))

(deftest the-cut
  (let [root (city/tree (city/under files ""))]
    (is (= 12 (city/cut-depth {:files [] :dirs []} 4)) "nothing to cut: deepest")
    (is (<= 2 (city/cut-depth root 100)))
    (is (= 0 (city/cut-depth root 2)) "README and src as one tile")))

(deftest the-treemap
  (let [rects (city/squarify [6 3 1] {:x 0 :z 0 :w 10 :d 10})
        area #(* (:w %) (:d %))]
    (is (= 3 (count rects)))
    (is (< (js/Math.abs (- 60 (area (first rects)))) 1e-6) "areas follow the weights")
    (is (< (js/Math.abs (- 10 (area (last rects)))) 1e-6))
    (is (every? #(and (>= (:x %) -1e-9) (>= (:z %) -1e-9) (<= (+ (:x %) (:w %)) (+ 10 1e-9)) (<= (+ (:z %) (:d %)) (+ 10 1e-9))) rects)
        "inside the rect")
    (is (= [] (city/squarify [] {:x 0 :z 0 :w 1 :d 1})))))

(deftest the-parts
  (let [ps (city/parts {:id "c" :pos [1 0 2] :size 10} files)
        by-id (into {} (map (juxt :id identity)) ps)]
    (testing "a group, an area per folder, a tower per file"
      (is (= "group" (:shape (by-id "c"))))
      (is (= [1 0 2] (:pos (by-id "c"))))
      (is (every? #(= "c" (:parent %)) (rest ps)))
      (is (contains? by-id "c:src/a/"))
      (is (= "src/a/one.clj" (:file (by-id "c:src/a/one.clj"))))
      (is (> (get-in (by-id "c:src/a/one.clj") [:size 1]) (get-in (by-id "c:src/a/two.clj") [:size 1])) "taller with more lines"))
    (testing "files stand on their folder's area"
      (let [area (by-id "c:src/a/")
            t (by-id "c:src/a/two.clj")
            top (+ (get-in area [:pos 1]) (/ (get-in area [:size 1]) 2))]
        (is (< (js/Math.abs (- top (- (get-in t [:pos 1]) (/ (get-in t [:size 1]) 2)))) 1e-9))))
    (testing "a city of a folder keeps paths from the root"
      (is (= "src/b/three.clj" (:file (some #(when (= "c:b/three.clj" (:id %)) %) (city/parts {:id "c" :path "src"} files))))))
    (testing "top folders differ in hue"
      (is (not= (city/colour (city/hue-of 0 2) 1 true) (city/colour (city/hue-of 1 2) 1 true))))))

(deftest hues-by-depth
  (let [root (city/tree (city/under files ""))]
    (is (= ["src"] (city/folders-at root 1)))
    (is (= ["src/a" "src/b"] (city/folders-at root 2)))
    (is (= 2 (count (set (vals (city/hues root 2))))))
    (let [ps (into {} (map (juxt :id identity)) (city/parts {:id "c" :hue 2} files))]
      (is (not= (:color (ps "c:src/a/one.clj")) (:color (ps "c:src/b/three.clj"))) "a and b differ at hue 2"))
    (let [ps (into {} (map (juxt :id identity)) (city/parts {:id "c"} files))]
      (is (not= (:color (ps "c:src/a/one.clj")) (:color (ps "c:README.md"))) "root files are grey"))))
