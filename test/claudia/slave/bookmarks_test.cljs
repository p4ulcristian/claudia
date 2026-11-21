(ns claudia.slave.bookmarks-test
  "An agent's bookmark tools (claudia.slave.bookmarks), on plain data."
  (:require [cljs.test :refer [deftest is]]
            [claudia.slave.bookmarks :as b]))

(def dirs #{"/home/ana/Work" "/home/ana/Work/app" "/home/ana/notes"})

(defn- t [args] (b/target args "home" "/home/ana/Work" "/home/ana" dirs))

(deftest bookmarks-are-on-only-with-all-four-settings
  (is (= {:url "u" :server "s" :key-file "k" :email "e"}
         (b/config #js {:CLAUDIA_HUB_URL "u" :CLAUDIA_SERVER_NAME "s" :CLAUDIA_HUB_KEY_FILE "k" :CLAUDIA_OWNER_EMAIL "e"})))
  (is (nil? (b/config #js {:CLAUDIA_HUB_URL "u" :CLAUDIA_SERVER_NAME "s" :CLAUDIA_HUB_KEY_FILE "k"}))))

(deftest target-resolves-on-this-server
  (is (= {:server "home" :folder "/home/ana/Work"} (t {})))
  (is (= {:server "home" :folder "/home/ana/Work/app"} (t {:folder "app"})))
  (is (= {:server "home" :folder "/home/ana/notes"} (t {:folder "~/notes"})))
  (is (= {:server "home" :folder "/home/ana/Work/app"} (t {:folder "/home/ana/Work/app/" :server "home"})))
  (is (:error (t {:folder "gone"}))))

(deftest target-wants-absolute-paths-elsewhere
  (is (= {:server "box" :folder "/srv/app"} (t {:server "box" :folder "/srv/app"})))
  (is (:error (t {:server "box" :folder "app"})))
  (is (:error (t {:server "box"}))))

(deftest target-needs-a-folder
  (is (:error (b/target {} "home" nil "/home/ana" dirs))))

(deftest listing-and-answer
  (is (= "No bookmarks yet." (b/listing [])))
  (is (= "home /w/app (app)\nbox /x (X)" (b/listing [{:server "home" :folder "/w/app"} {:server "box" :folder "/x" :label "X"}])))
  (is (= {:items [{:server "home" :folder "/a"}]} (b/answer {:ok true :text "{\"items\":[{\"server\":\"home\",\"folder\":\"/a\"}]}"})))
  (is (= {:error "not one of your servers"} (b/answer {:ok false :text "{\"error\":\"not one of your servers\"}"})))
  (is (= {:error "The hub said: boom"} (b/answer {:ok false :text "boom"}))))
