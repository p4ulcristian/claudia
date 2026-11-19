(ns claudia.slave.secrets-list-test
  "list_secrets (claudia.slave.secrets-list), on plain data."
  (:require [cljs.test :refer [deftest is async]]
            [clojure.string :as str]
            [claudia.slave.secrets-list :as sl]
            [claudia.slave.tools :as tools]))

(def answer
  {:vault "Claudia"
   :items [{:title "GitHub" :fields [{:label "token" :ref "op://Claudia/GitHub/token"}
                                     {:label "token" :section "CI/CD" :ref "op://Claudia/GitHub/s2/f3"}]}
           {:title "Empty" :fields []}]})

(deftest the-page-answer-is-cleaned
  (is (= answer (sl/clean-list answer)))
  (is (nil? (sl/clean-list {:items "x"})))
  (is (= {:vault "V" :items [{:title "A" :fields [{:label "" :ref "op://V/A/b"}]}]}
         (sl/clean-list {:vault "V" :items [{:title "A" :fields [{:ref "op://V/A/b" :value "no"} {:label "c"}]}
                                            {:title 3}]}))
      "only strings are kept, and no field without a reference"))

(deftest one-line-per-reference
  (is (= (str "Keys you may use (vault \"Claudia\"); pass a reference as run_with_secret's ref:\n"
              "GitHub\n"
              "  op://Claudia/GitHub/token\n"
              "  op://Claudia/GitHub/s2/f3  (CI/CD / token)\n"
              "Empty  (no fields)")
         (sl/listing answer)))
  (is (str/includes? (sl/listing {:vault "Claudia" :items []}) "Settings → Keys")))

(deftest the-result-says-what-failed
  (is (not (:isError (sl/result {:answer answer}))))
  (is (= "The key list failed: No key broker on this hub (HUB_BROKER_SOCKET)."
         (get-in (sl/result {:error "No key broker on this hub (HUB_BROKER_SOCKET)."}) [:content 0 :text])))
  (is (:isError (sl/result {:answer {:nope 1}}))))

(deftest the-tool-is-offered
  (is (contains? (set tools/allowed-tools) "mcp__workspace__list_secrets"))
  (is (= {:type "object" :properties {} :additionalProperties false}
         (get-in tools/workspace-mcp [:list_secrets :inputSchema])))
  (is (str/includes? tools/system-append "list_secrets")))

(deftest the-first-page-answer-wins
  (async done
    (let [sent (atom [])
          before @sl/send-pages!]
      (reset! sl/send-pages! (fn [f] (swap! sent conj f) 2))
      (-> (sl/ask! "c1")
          (.then (fn [r]
                   (is (= "keys_ask" (:type (first @sent))))
                   (is (str/includes? (get-in r [:content 0 :text]) "op://Claudia/GitHub/token"))
                   (reset! sl/send-pages! (constantly 0))
                   (sl/ask! "c1")))
          (.then (fn [r]
                   (is (:isError r) "no page open: refused at once")
                   (reset! sl/send-pages! before)
                   (done))))
      (let [{:keys [ask]} (first @sent)]
        (sl/answer! "other-chat" ask {:answer {:vault "Claudia" :items []}})
        (sl/answer! "c1" ask {:answer answer})
        (sl/answer! "c1" ask {:error "late"})))))
