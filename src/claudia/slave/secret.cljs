(ns claudia.slave.secret
  "1Password secrets that never rest on the server (README decision 66): a one-time keypair per
  request, the value sealed to it by the key broker on the gate (or by the page, pasted), opened
  here, used by one command and gone. Nothing here logs or stores a value."
  (:require ["libsodium-wrappers" :as sodium]
            ["node:child_process" :as cp]
            [clojure.string :as str]
            [claudia.node.sealbox :refer [b64 unb64]]))

(def ready (.-ready sodium))

(defn keypair []
  (let [^js kp (sodium/crypto_box_keypair)]
    {:public (.-publicKey kp) :private (.-privateKey kp) :pubkey (b64 (.-publicKey kp))}))

(defn open
  "The sealed value (base64) opened with the request's keypair; throws when it does not open."
  [sealed {:keys [public private]}]
  (sodium/crypto_box_seal_open (unb64 sealed) public private "text"))

(defn env-name? [s] (boolean (re-matches #"[A-Za-z_][A-Za-z0-9_]*" (str s))))

(defn run-result
  "What a command run with the secret value answers: its output with every occurrence of value as
  [secret] (stderr after stdout), \"(no output)\" when there is none, and the exit code or signal
  when it failed (code nil = it did not fail)."
  [value failed code stdout stderr]
  (let [redact #(str/replace (str %) value "[secret]")
        out (str/trim (str (redact stdout) (when (seq stderr) (str "\n" (redact stderr)))))]
    {:text (str (if (seq out) out "(no output)")
                (when failed (str "\n(exit " code ")")))
     :error (boolean failed)}))

(defn run
  "Runs command with sh -c in cwd, env-name=value added to this process's environment only.
  Promise of {:text output with every occurrence of the value as [secret], :error bool}."
  [command env-name value cwd]
  (js/Promise.
   (fn [done]
     (cp/execFile "sh" #js ["-c" command]
                  #js {:cwd cwd
                       :env (js/Object.assign #js {} (.-env js/process) (doto #js {} (aset env-name value)))
                       :timeout (* 10 60 1000)
                       :maxBuffer (* 10 1024 1024)}
                  (fn [^js err stdout stderr]
                    (done (run-result value (some? err) (when err (or (.-code err) (.-signal err)))
                                      stdout stderr)))))))
