(ns claudia.web.health-test
  "The page's word on whether this release works for it (README decision 35)."
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.health :as h]))

(def ^:private loaded {:t0 0 :commit "ccccccc" :opened-at nil :working false :error nil :hidden false :reported false})

(deftest a-working-page-says-so-once
  (is (= {:ok true} (h/verdict (assoc loaded :working true) 1 true)))
  (is (nil? (h/verdict (assoc loaded :working true :reported true) 1 true)) "once")
  (is (nil? (h/verdict (assoc loaded :working true :commit nil) 1 true)) "the hub not heard yet"))

(deftest a-broken-page-says-why
  (is (= {:ok false :reason "no-chats"} (h/verdict (assoc loaded :opened-at 5) (+ 5 h/no-chats-ms) true)))
  (is (nil? (h/verdict (assoc loaded :opened-at 5) (+ 4 h/no-chats-ms) true)) "still waiting for the list")
  (is (= {:ok false :reason "no-socket"} (h/verdict loaded h/no-socket-ms true)))
  (is (nil? (h/verdict loaded (dec h/no-socket-ms) true)))
  (is (= {:ok false :reason "script-error" :detail "boom (main.js)"}
         (h/verdict (assoc loaded :error "boom (main.js)") 1 true)) "at once"))

(deftest nothing-while-hidden-or-offline
  (testing "a hidden page's sockets and timers are not to be judged"
    (is (nil? (h/verdict (assoc loaded :hidden true :opened-at 0) h/no-chats-ms true)))
    (is (nil? (h/verdict loaded h/no-socket-ms false)) "offline")
    (is (= {:ok true} (h/verdict (assoc loaded :hidden true :working true) 1 false)) "working is still said")))

(deftest only-the-pages-own-errors
  (is (= "x is undefined (main.js)" (h/own-error "x is undefined" "https://hub.test/js/main.js" "https://hub.test")))
  (is (nil? (h/own-error "boom" "chrome-extension://abc/x.js" "https://hub.test")) "an extension")
  (is (nil? (h/own-error "boom" "https://other.test/js/main.js" "https://hub.test")) "another site")
  (is (nil? (h/own-error "boom" nil "https://hub.test"))))
