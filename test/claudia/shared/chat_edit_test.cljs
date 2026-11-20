(ns claudia.shared.chat-edit-test
  (:require [cljs.test :refer [deftest is]]
            [clojure.string :as str]
            [claudia.shared.chat-edit :as ce]))

(deftest rename-chat-takes-a-title-of-at-most-200
  (is (= {:title "Deploy fix"} (ce/title-change {:title "  Deploy fix "})) "trimmed")
  (is (= {:title (str/join (repeat 200 "a"))} (ce/title-change {:title (str/join (repeat 200 "a"))})))
  (is (= {:error "title: over 200 characters"} (ce/title-change {:title (str/join (repeat 201 "a"))})))
  (is (= {:error "title: empty"} (ce/title-change {:title "   "})))
  (is (= {:error "title: text"} (ce/title-change {:title 5})))
  (is (= {:error "title: needed"} (ce/title-change {}))))

(deftest set-chat-colour-takes-a-colour-of-the-box
  (is (= {:color "jade"} (ce/colour-change {:color "jade"})))
  (is (= {:color "dusk"} (ce/colour-change {:color "dusk"})) "a gradient")
  (is (= {:color "ash"} (ce/colour-change {:color "ash"})) "passive grey")
  (is (str/starts-with? (:error (ce/colour-change {:color "wake"})) "color: one of "))
  (is (str/starts-with? (:error (ce/colour-change {:color "neon"})) "color: one of "))
  (is (= {:error "color: needed"} (ce/colour-change {}))))
