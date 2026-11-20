(ns claudia.web.back-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.back :as back]))

(def ^:private layer {:id "a" :path "/s/x/c" :close :close-a})

(deftest back-from-a-layer-is-the-layers
  (is (= {:st {:layers [] :swallow 0} :took? true :close :close-a}
         (back/pop-outcome {:layers [layer] :swallow 0} nil "/s/x/c"))))

(deftest a-pop-elsewhere-closes-the-layer-but-is-the-pages
  (is (= {:st {:layers [] :swallow 0} :took? false :close :close-a}
         (back/pop-outcome {:layers [layer] :swallow 0} nil "/s/x/other"))))

(deftest the-pages-own-back-is-swallowed
  (is (= {:st {:layers [layer] :swallow 0} :took? true}
         (back/pop-outcome {:layers [layer] :swallow 1} "a" "/s/x/c"))))

(deftest no-layer-no-take
  (is (= {:st {:layers [] :swallow 0} :took? false}
         (back/pop-outcome {:layers [] :swallow 0} nil "/")))
  (is (false? (:took? (back/pop-outcome {:layers [layer] :swallow 0} "a" "/s/x/c")))
      "still on the layer's entry (a forward, or a pop above it)"))
