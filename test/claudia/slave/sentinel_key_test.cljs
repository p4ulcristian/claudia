(ns claudia.slave.sentinel-key-test
  (:require [cljs.test :refer [deftest is]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [claudia.shared.sentinel-key :as sk]
            [claudia.slave.sentinel-key :as sentinel-key]
            [claudia.slave.worktree :as worktree]))

(def a-key (str "snt_" (apply str (repeat 42 "a")) "-"))

(deftest a-sentinel-key-is-cleaned-or-refused
  (is (= a-key (sk/clean-key (str " " (subs a-key 0 10) "\n" (subs a-key 10) " "))))
  (is (nil? (sk/clean-key (str a-key "b"))) "one character too many")
  (is (nil? (sk/clean-key (subs a-key 1))))
  (is (nil? (sk/clean-key (str "snt_" (apply str (repeat 42 "a")) "+"))) "not base64url")
  (is (nil? (sk/clean-key nil))))

(deftest the-page-sees-only-the-first-eight-characters
  (is (= "snt_aaaa" (sk/hint a-key)))
  (is (= "snt" (sk/hint "snt")))
  (is (nil? (sk/hint nil))))

(deftest the-runner-answers-a-hint-and-a-fingerprint-never-the-key
  (let [s (sentinel-key/shown a-key)]
    (is (= "snt_aaaa" (:hint s)))
    (is (re-matches #"[0-9a-f]{16}" (:fingerprint s)))
    (is (not (re-find #"aaaaaaaaaa" (pr-str s)))))
  (is (= {:hint nil :fingerprint nil} (sentinel-key/shown nil))))

(deftest a-put-writes-removes-or-refuses
  (is (= [:write a-key] (sentinel-key/put-plan {:key (str a-key "\n")})))
  (is (= [:remove] (sentinel-key/put-plan {:key ""})))
  (is (= [:remove] (sentinel-key/put-plan {:key "  "})))
  (is (= [:remove] (sentinel-key/put-plan {})))
  (is (= :error (first (sentinel-key/put-plan {:key "ghp_nope"}))))
  (is (= :error (first (sentinel-key/put-plan {:key 5})))))

(deftest a-worktree-report-signs-in-with-the-key
  (is (= {"authorization" (str "Bearer " a-key)} (worktree/auth-headers a-key)))
  (is (nil? (worktree/auth-headers nil))))

(deftest the-key-file-is-written-for-this-user-only-and-removed
  (let [home (fs/mkdtempSync (path/join (os/tmpdir) "sentinel-key-"))
        before (.. js/process -env -HOME)]
    (set! (.. js/process -env -HOME) home)
    (try
      (is (= {:hint nil :fingerprint nil} (sentinel-key/state)))
      (is (= "snt_aaaa" (:hint (sentinel-key/put! {:key a-key}))))
      (is (= "600" (.toString (bit-and (.-mode (fs/statSync (sentinel-key/key-file))) 511) 8)))
      (is (= a-key (sentinel-key/read-key)))
      (is (not (fs/existsSync (str (sentinel-key/key-file) ".new"))))
      (is (:error (sentinel-key/put! {:key "nope"})))
      (is (= a-key (sentinel-key/read-key)) "a refused key leaves the kept one")
      (is (= {:hint nil :fingerprint nil} (sentinel-key/put! {:key ""})))
      (is (not (fs/existsSync (sentinel-key/key-file))))
      (finally
        (set! (.. js/process -env -HOME) before)
        (fs/rmSync home #js {:recursive true :force true})))))
