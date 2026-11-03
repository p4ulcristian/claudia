(ns claudia.web.views.clojure-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.views.clojure :as cl]))

(def ^:private project
  [{:name "app.main" :vars 2 :requires ["app.db" "app.web.page"]}
   {:name "app.db" :vars 5 :requires []}
   {:name "app.web.page" :vars 3 :requires ["app.db"]}
   {:name "app.web.css" :vars 1 :requires []}])

(deftest node-id-is-the-next-segment
  (is (= "app" (cl/node-id "" "app.db")))
  (is (= "app.db" (cl/node-id "app" "app.db")))
  (is (= "app.web" (cl/node-id "app" "app.web.page")))
  (is (= "=app" (cl/node-id "app" "app"))))

(deftest bubbles-count-and-group
  (let [b (cl/bubbles (filter #(cl/under? (:name %) "app") project) "app")]
    (is (= #{"app.main" "app.db" "app.web"} (set (keys b))))
    (is (= {:count 2 :vars 4 :group true :label "web"} (select-keys (b "app.web") [:count :vars :group :label])))
    (is (false? (:group (b "app.db"))))
    (is (every? :color (vals b)))))

(deftest layout-puts-required-below
  (let [{:keys [nodes edges layers rows]} (cl/layout project "app")
        layer (into {} (map (juxt :key :layer)) nodes)]
    (is (= 3 layers))
    (is (= 1 rows))
    (is (= {"app.main" 0 "app.web" 1 "app.db" 2} layer))
    (is (= #{["app.main" "app.db"] ["app.main" "app.web"] ["app.web" "app.db"]} (set (map (juxt :from :to) edges))))
    (is (not-any? :back edges))))

(deftest back-edges-close-a-circle
  (let [out {"a" ["b"] "b" ["a"]}]
    (is (= #{["b" "a"]} (cl/back-edges ["a" "b"] out #{"a" "b"}))))
  (is (= #{} (cl/back-edges ["a" "b"] {"a" ["b"] "b" []} #{"b"}))))

(deftest layer-of-skips-back-arrows
  (is (= {"a" 0 "b" 1 "c" 2} (cl/layer-of ["a" "b" "c"] [["a" "b"] ["b" "c"] ["c" "a"]] #{["c" "a"]}))))

(deftest order-columns-follows-neighbours
  (is (= [["a" "b"] ["y" "x"]] (cl/order-columns [["a" "b"] ["x" "y"]] [["a" "y"] ["b" "x"]]))))

(deftest view-at-opens-a-lone-group
  (is (= "app" (:at (cl/view-at project ""))))
  (is (= "app.web" (:at (cl/view-at [{:name "app.web.page"} {:name "app.web.css"}] "")))))

(deftest open-target-reads-what-claude-opens
  (let [nodes [{:key "app.db"} {:key "app.web" :group true}]]
    (is (= {:ns "app.db" :var nil} (cl/open-target "app.db" nodes)))
    (is (= {:ns "app.db" :var "query"} (cl/open-target "app.db/query" nodes)))
    (is (= {:edge {:from {:key "app.web" :group true} :to {:key "app.db"}}} (cl/open-target "app.web.page -> app.db" nodes)))
    (is (nil? (cl/open-target "x -> app.db" nodes)))))

(deftest lit-and-edge-look
  (let [a {:key "a"} b {:key "b"} c {:key "c"}
        edges [{:from "a" :to "b"}]]
    (testing "nothing focused: all lit"
      (is (true? (cl/lit? edges nil nil c))))
    (testing "focus lights its neighbours"
      (is (cl/lit? edges nil a b))
      (is (not (cl/lit? edges nil a c))))
    (is (= {:on nil :tone "blue" :dim false} (cl/edge-look {:from "a" :to "b"} nil a)))
    (is (= "violet" (:tone (cl/edge-look {:from "a" :to "b"} {:from a :to b} nil))))
    (is (= "red" (:tone (cl/edge-look {:from "a" :to "b" :back true} nil nil))))))

(deftest crumbs-and-labels
  (is (= [{:label "a" :path "a"} {:label "b" :path "a.b"}] (cl/crumb-paths "a.b")))
  (is (empty? (cl/crumb-paths "")))
  (is (= "page" (cl/from-label {:key "app.web"} "app.web.page")))
  (is (= "app.web" (cl/from-label {:key "app.web"} "app.web")))
  (is (= "x → db: 2 requires (closes a circle). Click for the functions it uses."
         (cl/edge-title {:key "app.x"} {:key "app.db"} {:pairs 2 :back true} "app"))))

(deftest demo-answers
  (let [demo {:vars {"app.db" {:doc "The db." :vars [{:name "q" :code "(defn q [])" :row 3}] :external ["pg"]}}
              :uses {"app.main" {"app.db" ["q" "q2"]} :app.web.page {:app.db ["q"]}}}]
    (is (= {:doc "The db." :vars [{:name "q" :code "(defn q [])" :row 3}] :external ["pg"]} (cl/demo-detail demo "app.db")))
    (is (= {:code "(defn q [])" :row 3 :lang "clj"} (cl/demo-source demo "app.db" "q")))
    (is (nil? (cl/demo-source demo "app.db" "nope")))
    (is (= #{{:count 2 :ns "app.db" :name "q" :from ["app.main" "app.web.page"]}
             {:count 1 :ns "app.db" :name "q2" :from ["app.main"]}}
           (set (cl/demo-used (:uses demo) {:key "app"} {:key "app.db"}))))))

(deftest zoom-math
  (let [fr {:x 0 :y 0 :w 100 :h 50}]
    (is (= {:x 0 :y 0 :w 100 :h 50} (cl/view-of fr nil)))
    (is (= {:x 50 :y 25 :w 50 :h 25} (cl/view-of fr {:k 2 :cx 200 :cy 200})) "kept inside the map")
    (is (nil? (cl/zoom-around nil 0.5 50 25 fr fr)) "never further out than the whole map")
    (is (= {:k 2 :cx 50 :cy 25} (cl/zoom-around nil 2 50 25 fr fr)))
    (is (= "1 2 3 4" (cl/viewbox {:x 1 :y 2 :w 3 :h 4})))
    (is (= {:x 5 :y 6 :cx 50 :cy 25 :s 0.5} (cl/pan-start fr 2 5 6)))
    (is (= {:k 2 :cx 45 :cy 20} (cl/panned {:k 2} {:cx 50 :cy 25 :s 0.5} 10 10)))
    (is (nil? (cl/bounds-of 0 0 0 10)))
    (is (= {:x -4 :y -4 :w 38 :h 48} (cl/bounds-of 10 10 10 20)))))

(deftest direction-by-shape
  (is (= 0.8 (cl/aim false 1000 800)))
  (is (= "down" (cl/best-dir 4 1 0.8)))
  (is (= "across" (cl/best-dir 1 6 0.8))))
