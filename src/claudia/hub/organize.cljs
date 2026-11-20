(ns claudia.hub.organize
  "Claudia's organize_chats (README decision 88), as data: a list of steps checked against the
  person's own chats (every server's list, as the op chats gives it) and their chat order. Order steps
  (new_group, rename_group, delete_group, move) change the person's list on the hub, in turn; edit
  steps become PATCH /api/chats/:id calls on the chat's server, made by claudia.hub.claudia.

  A chat is named by its reference \"<server>/<chat id>\" (claudia.shared.order/ref-of), a group by
  its id."
  (:require [clojure.string :as str]
            [claudia.shared.chat-edit :as chat-edit]
            [claudia.shared.order :as order]))

(def max-steps "The most steps one organize_chats call may have." 200)

(def max-group-name "The longest group name (claudia.shared.order/clean-items cuts there)." 60)

(def actions ["edit" "new_group" "rename_group" "delete_group" "move"])

(defn- name-problem [n]
  (cond (not (string? n)) "name: text"
        (str/blank? n) "name: empty"
        (> (count (str/trim n)) max-group-name) (str "name: over " max-group-name " characters")
        :else nil))

(defn- edit-problem [known {:keys [chat title color archived]}]
  (cond (not (known chat)) (str "no chat " chat " of yours")
        (every? nil? [title color archived]) "give title, color or archived"
        (and (some? archived) (not (boolean? archived))) "archived: true or false"
        :else (or (chat-edit/title-problem title) (chat-edit/colour-problem color))))

(defn- move-problem [known {:keys [item] target :into}]
  (cond (not (string? item)) "item: a chat reference or a group id"
        (and (some? target) (not (known item))) (str "no chat " item " of yours")
        :else nil))

(defn problem
  "What is wrong with step on its own (known: the person's chat references), or nil."
  [known {:keys [action chats group] :as step}]
  (case action
    "edit" (edit-problem known step)
    "new_group" (or (name-problem (:name step))
                    (cond (or (not (sequential? chats)) (empty? chats)) "chats: at least one"
                          (not-every? known chats) (str "no chat " (first (remove known chats)) " of yours")
                          :else nil))
    "rename_group" (or (when-not (string? group) "group: a group id") (name-problem (:name step)))
    "delete_group" (when-not (string? group) "group: a group id")
    "move" (move-problem known step)
    (str "action: one of " (str/join ", " actions))))

(defn- move-chat [items known {:keys [item before] target :into}]
  (cond (and target (not (order/find-group items target))) {:error (str "no group " target)}
        (and target before (not (some #{before} (:chats (order/find-group items target)))))
        {:error (str before " is not in group " target)}
        target {:items (order/into-group items item target before)}
        (not (known item)) {:error (str "no chat " item " of yours")}
        (and before (or (= before item) (not (order/top-key? items before)))) {:error (str "nothing to put it before: " before)}
        :else {:items (order/place items item before)}))

(defn- move [items known {:keys [item before] target :into :as step}]
  (if (and (order/find-group items item) (nil? target))
    (if (and before (or (= before item) (not (order/top-key? items before))))
      {:error (str "nothing to put it before: " before)}
      {:items (order/place-group items item before)})
    (move-chat items known step)))

(defn order-step
  "One order step on items: {:items} (and :note), or {:error}. known: the person's chat references;
  gid: the id a new group gets."
  [items known {:keys [action group] :as step} gid]
  (let [nm (some-> (:name step) str/trim)
        missing (when (and (#{"rename_group" "delete_group"} action) (not (order/find-group items group)))
                  {:error (str "no group " group)})]
    (or missing
        (case action
          "new_group" {:items (order/new-group items (vec (:chats step)) gid nm) :note (str "group " gid)}
          "rename_group" {:items (order/update-group items group #(assoc % :name nm))}
          "delete_group" {:items (order/ungroup items group)}
          "move" (move items known step)))))

(defn listed
  "The rows a list shows without arranging (not archived, not Claudia's own chat), for settling."
  [rows]
  (remove #(or (:archived %) (:claudia %) (:error %)) rows))

(defn- order-result [{:keys [items] :as acc} known step i gid]
  (let [{:keys [error note] :as out} (order-step items known step gid)]
    (if error
      (assoc-in acc [:results i] error)
      (-> acc (assoc :items (:items out) :changed true) (assoc-in [:results i] (or note "ok"))))))

(defn- edit-call [step]
  (let [[server chat] (order/chat-key (:chat step))]
    {:server server :chat chat
     :body (cond-> (select-keys step [:color :archived]) (:title step) (assoc :title (str/trim (:title step))))}))

(defn- planned [known gids acc [i step]]
  (let [p (problem known step)]
    (cond p (assoc-in acc [:results i] p)
          (= "edit" (:action step)) (update acc :edits conj (assoc (edit-call step) :i i))
          :else (order-result acc known step i (nth gids i)))))

(defn plan
  "What steps do: {:items (the person's list after the order steps) :changed (whether one did
  anything) :results (per step: \"ok\", a note or what was wrong; nil for an edit still to make)
  :edits [{:i :server :chat :body}]}. rows: every server's chats (op chats); items: the list now;
  gids: an id for each step that might make a group."
  [rows items steps gids]
  (let [known (set (map order/ref-of (remove :error rows)))
        settled (order/settle items (listed rows) #(or (:started %) (:at %) 0))]
    (reduce (partial planned known gids)
            {:items settled :changed false :results (vec (repeat (count steps) nil)) :edits []}
            (map-indexed vector steps))))

(defn steps-problem
  "What is wrong with organize_chats' steps as a whole, or nil."
  [steps]
  (cond (not (sequential? steps)) "steps: a list"
        (empty? steps) "steps: at least one"
        (> (count steps) max-steps) (str "steps: at most " max-steps)
        :else nil))

(defn edit-result
  "An edit's answer from its server ({:status :body}) as its result."
  [{:keys [status body]}]
  (if (= 200 status) "ok" (or (:error body) (str "the server answered " status))))
