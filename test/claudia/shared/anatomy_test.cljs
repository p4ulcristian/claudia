(ns claudia.shared.anatomy-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.shared.anatomy :as anatomy]))

(deftest which-paths-may-be-asked-for
  (let [v anatomy/version]
    (is (anatomy/path-ok? (str v "/index.json")))
    (is (anatomy/path-ok? (str v "/skeletal/femur.r.glb")))
    (is (anatomy/path-ok? (str v "/nervous/obturator-nerve.r.json")))
    (testing "another version, another kind of file, or a way out are refused"
      (is (not (anatomy/path-ok? "v0.0.1/skeletal/femur.r.glb")))
      (is (not (anatomy/path-ok? (str v "/skeletal/femur.r.obj"))))
      (is (not (anatomy/path-ok? (str v "/../secret.glb"))))
      (is (not (anatomy/path-ok? (str v "/skeletal/../../x.glb")))))))

(deftest where-files-come-from
  (is (= (str "https://raw.githubusercontent.com/p4ulcristian/claudia-anatomy/" anatomy/version "/models/skeletal/femur.r.glb")
         (anatomy/source-url (str anatomy/version "/skeletal/femur.r.glb"))))
  (is (= (str "/anatomy/" anatomy/version "/index.json") (anatomy/page-path "index.json"))))
