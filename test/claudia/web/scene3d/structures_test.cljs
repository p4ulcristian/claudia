(ns claudia.web.scene3d.structures-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.scene3d.structures :as structures]))

(def index
  [{:id "femur.r" :name "Femur" :latin "Os femoris" :side "r" :system "skeletal" :tags ["Bones of lower limb" "Right lower limb"] :file "skeletal/femur.r.glb"}
   {:id "femur.l" :name "Femur" :latin "Os femoris" :side "l" :system "skeletal" :tags ["Bones of lower limb" "Left lower limb"] :file "skeletal/femur.l.glb"}
   {:id "sacrum" :name "Sacrum" :latin "Os sacrum" :side nil :system "skeletal" :tags ["Pelvis"] :file "skeletal/sacrum.glb"}
   {:id "adductor-longus.r" :name "Adductor longus" :latin "Adductor longus" :side "r" :system "muscular" :tags ["Medial compartment of thigh" "Obturator nerve" "Right lower limb"] :file "muscular/adductor-longus.r.glb"}
   {:id "gracilis-muscle.r" :name "Gracilis muscle" :latin "Musculus gracilis" :side "r" :system "muscular" :tags ["Obturator nerve"] :file "muscular/gracilis-muscle.r.glb"}
   {:id "adductor-longus.or" :name "Adductor longus" :latin "Adductor longus" :side "r" :system "attachment" :kind "origin" :tags [] :file "attachment/adductor-longus.or.glb"}])

(defn- ids [part] (mapv :id (structures/structures-of index part)))

(deftest finding-structures
  (testing "by English name, both sides unless one is asked for"
    (is (= ["femur.r" "femur.l"] (ids {:ref "femur"})))
    (is (= ["femur.r"] (ids {:ref "Femur" :side "right"}))))
  (testing "by Latin name, by id, by a name without \"muscle\""
    (is (= ["femur.l"] (ids {:ref "os femoris" :side "left"})))
    (is (= ["sacrum"] (ids {:ref "sacrum" :side "right"})))
    (is (= ["gracilis-muscle.r"] (ids {:ref "gracilis"}))))
  (testing "by a tag: a nerve's territory, a region"
    (is (= ["adductor-longus.r" "gracilis-muscle.r"] (ids {:ref "Obturator nerve"})))
    (is (= ["femur.r" "adductor-longus.r"] (ids {:ref "right lower limb"}))))
  (testing "attachment patches only when asked for; several refs at once"
    (is (= ["adductor-longus.r"] (ids {:ref "adductor longus"})))
    (is (= ["adductor-longus.or"] (ids {:ref "adductor longus" :system "attachment"})))
    (is (= ["femur.r" "sacrum"] (ids {:ref ["femur" "sacrum"] :side "r"})))))

(deftest a-part-becomes-its-structures
  (let [one (structures/expand {:id "thigh" :shape "anatomy" :ref "femur" :pos [0 1 0]} (take 1 index))
        two (structures/expand {:id "legs" :shape "anatomy" :ref "femur" :label false} (take 2 index))]
    (testing "one structure keeps the part's id, with Latin label and English note"
      (is (= [{:id "thigh" :shape "anatomy" :pos [0 1 0] :file "skeletal/femur.r.glb" :structure "femur.r"
               :color "#e8dcc4" :label "Os femoris" :note "Femur, right"}] one)))
    (testing "several get <id>:<structure>, and label false leaves them unlabelled"
      (is (= ["legs:femur.r" "legs:femur.l"] (mapv :id two)))
      (is (= [nil nil] (mapv :label two))))
    (testing "an attachment patch is coloured and noted by its kind"
      (is (= ["#e0453c" "Adductor longus, origin, right"]
             ((juxt :color :note) (first (structures/expand {:id "o" :ref "x"} [(last index)]))))))))

(deftest nerves-and-vessels-are-tubes
  (let [nerve {:id "obturator-nerve.r" :name "Obturator nerve" :latin "Nervus obturatorius" :side "r" :system "nervous" :shape "lines" :tags [] :file "nervous/obturator-nerve.r.json"}
        vein {:id "great-saphenous-vein.r" :name "Great saphenous vein" :side "r" :system "vascular" :shape "lines" :tags [] :file "vascular/great-saphenous-vein.r.json"}
        artery (assoc vein :id "femoral-artery.r" :name "Femoral artery")]
    (is (= [true 0.003 "#e6c84a"] ((juxt :lines :radius :color) (first (structures/expand {:id "n"} [nerve])))))
    (is (= 0.01 (:radius (first (structures/expand {:id "n" :radius 0.01} [nerve])))))
    (is (= ["#3d6fd6" "#d23b3b"] (mapv :color (structures/expand {:id "v"} [vein artery]))))))
