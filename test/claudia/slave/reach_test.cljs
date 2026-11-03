(ns claudia.slave.reach-test
  "Where the page's reads may go: the work folder, and in a view no dot part; on plain data."
  (:require [cljs.test :refer [deftest is]]
            [claudia.slave.codeforms :as codeforms]
            [claudia.slave.git :as git]
            [claudia.slave.reach :as reach]))

(deftest within-is-the-folder-or-below-it
  (is (reach/within? "/h/u/Work" "/h/u/Work"))
  (is (reach/within? "/h/u/Work" "/h/u/Work/apps/x"))
  (is (not (reach/within? "/h/u/Work" "/h/u")))
  (is (not (reach/within? "/h/u/Work" "/h/u/Workshop/x")))
  (is (not (reach/within? "/h/u/Work" "/h/u/.config/gh/hosts.yml")))
  (is (reach/within? "/" "/etc")))

(deftest hidden-is-any-part-with-a-leading-dot
  (is (reach/hidden? ".env"))
  (is (reach/hidden? "apps/x/.git/config"))
  (is (reach/hidden? "../u/.ssh"))
  (is (reach/hidden? ".."))
  (is (not (reach/hidden? "apps/x/src/a.cljs")))
  (is (not (reach/hidden? "")))
  (is (not (reach/hidden? "notes.v2.md"))))

(deftest refusal-keeps-reads-in-the-work-folder
  (is (nil? (reach/refusal "/h/u/Work" "/h/u/Work/x/.env" false)))
  (is (= "a dot path, hidden in a read-only view" (reach/refusal "/h/u/Work" "/h/u/Work/x/.env" true)))
  (is (= "a dot path, hidden in a read-only view" (reach/refusal "/h/u/Work" "/h/u/Work/x/.git/HEAD" true)))
  (is (nil? (reach/refusal "/h/u/Work" "/h/u/Work/x/README.md" true)))
  (is (nil? (reach/refusal "/h/u/Work" "/h/u/Work" true)))
  (is (= "outside the work folder" (reach/refusal "/h/u/Work" "/h/u/.config/gh/hosts.yml" false)))
  (is (= "outside the work folder" (reach/refusal "/h/u/Work" "/h/u" true))))

(deftest reach-refuses-paths-that-are-not-absolute
  (is (= "not an absolute path" (reach/reach-refusal "/" "relative/path" false)))
  (is (= "not an absolute path" (reach/reach-refusal "/" nil false)))
  (is (not (reach/reach? "/" "" false))))

(deftest shown-entries-hide-dot-names-in-a-view
  (let [entries [{:name ".github" :dir true} {:name "src" :dir true} {:name ".env"} {:name "a.md"}]]
    (is (= entries (reach/shown-entries entries false)))
    (is (= [{:name "src" :dir true} {:name "a.md"}] (reach/shown-entries entries true)))))

(deftest root-ok-goes-up-only-to-the-work-folder
  (let [roots #{"/h/u/Work/apps/x"}]
    (is (codeforms/root-ok? roots "/h/u/Work/apps/x" "/h/u/Work"))
    (is (codeforms/root-ok? roots "/h/u/Work/apps/x/src" "/h/u/Work"))
    (is (codeforms/root-ok? roots "/h/u/Work/apps" "/h/u/Work"))
    (is (codeforms/root-ok? roots "/h/u/Work" "/h/u/Work"))
    (is (not (codeforms/root-ok? roots "/h/u" "/h/u/Work")))
    (is (not (codeforms/root-ok? roots "/h" "/h/u/Work")))
    (is (not (codeforms/root-ok? roots "/h/u/Work/apps/y" "/h/u/Work")))))

(deftest sql-ids-with-a-dot-part-are-hidden
  (is (codeforms/sql-id-hidden? "file:.secret/q.sql"))
  (is (not (codeforms/sql-id-hidden? "file:src/db/q.sql")))
  (is (not (codeforms/sql-id-hidden? "app.core/run")))
  (is (not (codeforms/sql-id-hidden? nil))))

(def ^:private two-files
  (str "diff --git .env .env\nindex 1..2 100644\n--- .env\n+++ .env\n@@ -1 +1 @@\n-A=1\n+A=2\n"
       "diff --git src/a.js src/a.js\nindex 3..4 100644\n--- src/a.js\n+++ src/a.js\n@@ -1 +1 @@\n-x\n+y\n"
       "diff --git .github/ci.yml .github/ci.yml\nnew file mode 100644\nBinary files /dev/null and .github/ci.yml differ\n"))

(deftest drop-hidden-leaves-out-dot-paths
  (let [detail {:meta {:hash "h"} :diff two-files :truncated false
                :files [{:path ".env" :additions 1 :deletions 1}
                        {:path "src/a.js" :additions 1 :deletions 1}
                        {:path ".github/ci.yml" :additions 0 :deletions 0}
                        {:path "b.js" :oldPath ".old/b.js" :additions 2 :deletions 0}]
                :stats {:files 4 :additions 4 :deletions 2}}
        out (git/drop-hidden detail)]
    (is (= ["src/a.js"] (map :path (:files out))))
    (is (= {:files 1 :additions 1 :deletions 1} (:stats out)))
    (is (= "diff --git src/a.js src/a.js\nindex 3..4 100644\n--- src/a.js\n+++ src/a.js\n@@ -1 +1 @@\n-x\n+y\n" (:diff out)))
    (is (= {:meta {:hash "h"} :truncated false} (select-keys out [:meta :truncated])))))

(deftest drop-hidden-on-one-files-diff
  (is (= {:diff ""} (git/drop-hidden {:diff "diff --git /dev/null .env\n--- /dev/null\n+++ .env\n@@ -0,0 +1 @@\n+T=1\n"})))
  (is (= {:diff "diff --git a.md a.md\n--- a.md\n+++ a.md\n"} (git/drop-hidden {:diff "diff --git a.md a.md\n--- a.md\n+++ a.md\n"}))))
