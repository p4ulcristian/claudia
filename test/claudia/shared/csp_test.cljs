(ns claudia.shared.csp-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [claudia.shared.csp :as csp]))

(deftest policy-joins-directives-in-order
  (is (= "default-src 'none'; img-src 'self' data:"
         (csp/policy [["default-src" "'none'"] ["img-src" "'self'" "data:"]])))
  (is (= "" (csp/policy []))))

(deftest the-page-runs-only-its-own-scripts
  (let [page (get (csp/headers "index.html") "content-security-policy")]
    (is (str/includes? page "script-src 'self' 'wasm-unsafe-eval';"))
    (is (not (str/includes? page " 'unsafe-eval'")) "no eval beyond WebAssembly")
    (is (not (str/includes? page csp/cdn)) "no CDN on the page")
    (is (str/includes? page "frame-ancestors 'none'")))
  (testing "every file but frame.html has the page's policy and may not be framed"
    (is (= (csp/headers "index.html") (csp/headers "js/main.js") (csp/headers nil)))
    (is (= "DENY" (get (csp/headers "img/favicon.svg") "x-frame-options")))))

(deftest the-frame-is-sandboxed-and-may-use-the-cdn
  (let [h (csp/headers "frame.html")
        frame (get h "content-security-policy")]
    (is (str/starts-with? frame "sandbox allow-scripts;"))
    (is (str/includes? frame (str "script-src 'unsafe-inline' " csp/cdn ";")))
    (is (str/includes? frame (str "connect-src " csp/cdn ";")) "no Claudia API from a freeform page")
    (is (str/includes? frame "frame-ancestors 'self'"))
    (is (= "SAMEORIGIN" (get h "x-frame-options")))
    (is (= "nosniff" (get h "x-content-type-options")))))
