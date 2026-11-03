(ns claudia.slave.secret
  "1Password secrets that never rest on the server (plan/1passwd-plan.md): a one-time keypair per
  request, the value sealed to it by the vault server (or by the page, pasted), opened here, used by
  one command and gone. Nothing here logs or stores a value."
  (:require ["libsodium-wrappers" :as sodium]
            ["node:child_process" :as cp]
            [clojure.string :as str]))

(def ready (.-ready sodium))

;; Standard base64 with padding on the wire (PROTOCOL.md "Secrets").
(defn- b64 [^js raw] (.toString (js/Buffer.from raw) "base64"))
(defn- unb64 [s] (js/Uint8Array. (js/Buffer.from (str s) "base64")))

(defn keypair []
  (let [^js kp (sodium/crypto_box_keypair)]
    {:public (.-publicKey kp) :private (.-privateKey kp) :pubkey (b64 (.-publicKey kp))}))

(defn seal
  "The value sealed to a base64 public key, as base64 (crypto_box_seal)."
  [value pubkey]
  (b64 (sodium/crypto_box_seal value (unb64 pubkey))))

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

(defn op-error
  "Why op read failed, from its stderr (op's messages never hold the value), else the fallback."
  [stderr fallback]
  (str "op read failed: " (or (not-empty (first (str/split-lines (str/trim (str stderr))))) fallback)))

(defn op-read
  "Promise of the value at ref, read by the 1Password CLI from account (the desktop app asks for the
  fingerprint). No shell: the ref is one argument."
  [account ref]
  (js/Promise.
   (fn [done fail]
     (cp/execFile "op" #js ["read" "--account" account ref]
                  #js {:timeout 90000 :maxBuffer (* 1024 1024)}
                  (fn [^js err stdout stderr]
                    (if err
                      (fail (js/Error. (op-error stderr (if (.-killed err) "timed out" (.-message err)))))
                      (done (str/replace (str stdout) #"\r?\n$" ""))))))))
