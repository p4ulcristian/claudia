(ns claudia.shared.order
  "The person's own order of their chats, and their groups (README decision 38), as data. The hub
  keeps one list per person (items, the same on every device): a chat is {:chat \"server/id\"}, a
  group {:group id :name :chats [\"server/id\"…] :folded bool :picks {…}}. Chats not in the list yet
  (new ones) come first, the newest first; then the list as arranged. Moving a chat never happens by
  itself: only the person's drag changes the order."
  (:require [clojure.string :as str]))

(defn ref-of "A chat row's reference in the list." [{:keys [server id]}] (str server "/" id))

(defn chat-key
  "A reference's chat key [server chat], or nil when it is not one."
  [cref]
  (when-let [[_ s c] (re-matches #"([^/]+)/(.+)" (str cref))] [s c]))

(defn find-group "Group gid in items, or nil." [items gid]
  (first (filter #(= gid (:group %)) items)))

(defn- refs-in [items]
  (set (mapcat #(if (:group %) (:chats %) [(:chat %)]) items)))

(defn arrange
  "What the list shows, in order: {:kind :chat :row} and {:kind :group :group :rows} (a group's rows
  in its order; a group none of whose chats is shown is left out). rows: the chats shown; started:
  a row's start time, for the new ones first (newest first)."
  [rows items started]
  (let [by-ref (into {} (map (juxt ref-of identity)) rows)
        placed (refs-in items)
        fresh (->> rows (remove #(placed (ref-of %))) (sort-by #(- (started %))))]
    (into (mapv (fn [r] {:kind :chat :row r}) fresh)
          (keep (fn [it]
                  (if (:group it)
                    (when-let [rs (seq (keep by-ref (:chats it)))] {:kind :group :group it :rows (vec rs)})
                    (when-let [r (by-ref (:chat it))] {:kind :chat :row r}))))
          items)))

(defn settle
  "items with the chats shown but not placed yet (rows, started as in arrange) placed on top, as the
  list shows them: once the person moves anything, every chat stays where it was seen."
  [items rows started]
  (let [placed (refs-in items)
        fresh (->> rows (remove #(placed (ref-of %))) (sort-by #(- (started %))))]
    (into (mapv (fn [r] {:chat (ref-of r)}) fresh) items)))

(defn without
  "items with chat cref taken out wherever it is; a group left with no chats goes."
  [items cref]
  (into [] (keep (fn [it]
                   (cond
                     (= cref (:chat it)) nil
                     (:group it) (let [cs (vec (remove #{cref} (:chats it)))] (when (seq cs) (assoc it :chats cs)))
                     :else it)))
        items))

(defn- index-of [items pred] (first (keep-indexed #(when (pred %2) %1) items)))

(defn- insert-at [v i x] (let [i (max 0 (min (count v) i))] (into (conj (subvec v 0 i) x) (subvec v i))))

(defn- key-of [it] (or (:group it) (:chat it)))

(defn place
  "items with chat cref moved to the top level, before the item whose key (a chat cref or a group id)
  is before (nil: at the end)."
  [items cref before]
  (let [its (without items cref)
        i (if before (or (index-of its #(= before (key-of %))) (count its)) (count its))]
    (insert-at its i {:chat cref})))

(defn into-group
  "items with chat cref moved into group gid, before its chat before (nil: last in the group)."
  [items cref gid before]
  (mapv (fn [it]
          (if (= gid (:group it))
            (let [cs (vec (remove #{cref} (:chats it)))
                  i (if before (or (index-of cs #{before}) (count cs)) (count cs))]
              (assoc it :chats (insert-at cs i cref)))
            it))
        (without items cref)))

(defn group-with
  "items with chat cref dropped onto chat onto: the two become group gid (named name), where onto was;
  onto must be placed (a fresh chat is placed first, at the top)."
  [items cref onto gid group-name]
  (let [its (without items cref)
        its (if (index-of its #(= onto (:chat %))) its (into [{:chat onto}] its))]
    (mapv #(if (= onto (:chat %)) {:group gid :name group-name :chats [onto cref] :folded false :picks {}} %) its)))

(defn update-group
  "items with group gid's fields changed by f."
  [items gid f]
  (mapv #(if (= gid (:group %)) (f %) %) items))

(defn take-out
  "items with chat cref taken out of its group to the top level, just after the group (a group left
  with no chats goes); items as they were when cref is in no group."
  [items cref]
  (if-let [g (first (filter #(and (:group %) (some #{cref} (:chats %))) items))]
    (let [its (without items cref)
          after (if (find-group its (:group g)) (:group g) nil)
          i (if after (inc (index-of its #(= after (:group %)))) (or (index-of items #(= (:group g) (:group %))) 0))]
      (insert-at its i {:chat cref}))
    items))

(defn ungroup
  "items with group gid gone and its chats in its place, in its order."
  [items gid]
  (into [] (mapcat #(if (= gid (:group %)) (mapv (fn [c] {:chat c}) (:chats %)) [%])) items))

(defn place-group
  "items with group gid moved to before the item whose key (a chat cref or a group id) is before
  (nil: at the end)."
  [items gid before]
  (if-let [g (find-group items gid)]
    (let [its (vec (remove #(= gid (:group %)) items))
          i (if before (or (index-of its #(= before (key-of %))) (count its)) (count its))]
      (insert-at its i g))
    items))

(defn top-key?
  "Whether key k (a chat cref or a group id) is one of items' own, not a chat inside a group."
  [items k]
  (boolean (index-of items #(= k (key-of %)))))

(defn- holds? [it cref] (or (= cref (:chat it)) (some #{cref} (:chats it))))

(defn new-group
  "items with chats crefs (at least one) gathered, in that order, into a new group gid named
  group-name, where the first of them was (at the top when it was not placed)."
  [items crefs gid group-name]
  (let [g {:group gid :name group-name :chats (vec (distinct crefs)) :folded false :picks {}}
        marked (into [] (mapcat #(if (holds? % (first crefs)) [::here %] [%])) items)
        its (reduce without marked crefs)]
    (if (some #{::here} its) (mapv #(if (= ::here %) g %) its) (into [g] its))))

(defn group-of "The group holding chat cref, or nil." [items cref]
  (first (filter #(and (:group %) (some #{cref} (:chats %))) items)))

(defn clean-items
  "A list from the hub or a page, kept only as far as it is well formed: chats and groups with a
  name, refs that look like server/id, at most 500 items and 500 chats a group."
  [items]
  (let [ref? #(and (string? %) (re-matches #"^[a-z][a-z0-9-]{0,30}/[A-Za-z0-9-]{1,80}$" %))]
    (vec (take 500 (keep (fn [it]
                           (cond
                             (ref? (:chat it)) {:chat (:chat it)}
                             (and (string? (:group it)) (string? (:name it)))
                             {:group (subs (:group it) 0 (min 64 (count (:group it))))
                              :name (str/trim (subs (:name it) 0 (min 60 (count (:name it)))))
                              :chats (vec (take 500 (filter ref? (:chats it))))
                              :folded (true? (:folded it))
                              :picks (if (map? (:picks it)) (select-keys (:picks it) [:plan :model :effort :folder :server :talk :bell]) {})}
                             :else nil))
                         items)))))
