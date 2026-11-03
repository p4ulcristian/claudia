(ns claudia.web.views.files-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.views.files :as files]))

(deftest names-and-paths
  (is (= "cljs" (files/ext-of-name "a.CLJS")))
  (is (= "" (files/ext-of-name ".env")))
  (is (= "var(--muted)" (files/ext-colour "md")))
  (is (= "var(--c-blue, var(--blue))" (files/ext-colour "ts")))
  (is (= [{:label "~" :path "/home/u"} {:label "w" :path "/home/u/w"}] (files/crumb-parts "/home/u/w")))
  (is (= ["a" "a/b"] (files/ancestors-of "a/b/c.txt")))
  (is (= "src/a" (files/clean "./src/a/"))))

(deftest rebase-moves-paths-under-the-root
  (is (= "x/y" (files/rebase "/r" "/r" "x/y")))
  (is (= "y" (files/rebase "/r/x" "/r" "x/y")))
  (is (nil? (files/rebase "/r/z" "/r" "x/y"))))

(deftest plan-reads-the-form
  (let [p (files/plan {:sketch [{:path "src/new.clj"}] :marks [{:path "./src/a.clj" :note "here"}]} "/r" "/r" false)]
    (is (= "src/a.clj" (:opened0 p)))
    (is (= [{:path "src/new.clj"}] (:made p)))
    (is (= {"src/a.clj" {:path "./src/a.clj" :note "here"}} (:marks p))))
  (is (= [] (:made (files/plan {:sketch [{:path "a"}]} "/r" "/r" true))) "real: only what is on disk"))

(deftest dir-entries-merges-the-plan
  (let [tree {:live true :sketch [1] :made [{:path "src/new.clj" :code "a\nb\n"}]
              :dirs {"" [{:name "src" :dir true} {:name "README.md"}] "src" [{:name "old.clj"}]}}]
    (is (= ["src" "README.md"] (map :name (files/dir-entries tree ""))))
    (is (= [{:name "new.clj" :dir false :planned true :lines 2} {:name "old.clj"}] (files/dir-entries tree "src")))
    (is (nil? (files/dir-entries (assoc tree :dirs {}) "src")) "not read yet")
    (is (= {:error "x"} (files/dir-entries (assoc tree :made [] :dirs {"" {:error "x"}}) "")))))

(deftest load-way-picks-how-a-file-opens
  (let [tree {:live true :made [{:path "p.clj" :code "(x)"}] :dirs {"" [{:name "q.clj"}]}}]
    (is (= :image (files/load-way tree "a.png")))
    (is (= {:text "(x)"} (files/load-way tree "p.clj")))
    (is (= :read (files/load-way tree "q.clj")))
    (is (= {:error "not given"} (files/load-way (assoc tree :live false) "q.clj")))))

(deftest tree-rows-walk-open-folders
  (let [tree {:live true :made [] :dirs {"" [{:name "src" :dir true} {:name ".env"}] "src" [{:name "a.clj"}]}
              :open #{"" "src"} :files {"src/a.clj" {:path "src/a.clj" :text "x"}} :marks {} :hide-dot true :pointed #{}}]
    (is (= [[:entry "src"] [:entry "src/a.clj"] [:file "src/a.clj"]] (map (juxt :row :p) (files/tree-rows tree "" 0))))
    (is (= [1 2] (map :depth (rest (files/tree-rows tree "" 0)))))
    (is (= 4 (count (files/tree-rows (assoc tree :hide-dot false) "" 0))))
    (is (= [{:row :loading :d "" :depth 0}] (files/tree-rows (assoc tree :dirs {}) "" 0)))))

(deftest note-when-not-readable
  (is (= "Shown to the user live from disk (not readable here): /r; open: a.clj, lines 3, 1 notes"
         (files/not-readable-note "/r" "a.clj" {:lines [3] :notes [{:line 3}]}))))
