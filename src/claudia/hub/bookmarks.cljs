(ns claudia.hub.bookmarks
  "A person's bookmarked folders in the hub's store (README decisions 61 and 67): read and kept for
  the page (GET/PUT /hub/bookmarks, claudia.hub.ops) and for an agent's bookmark tools (POST
  /hub/server/claudia ops bookmarks and bookmark, claudia.hub.claudia), always cleaned by
  claudia.shared.bookmarks."
  (:require [claudia.hub.store :as store]
            [claudia.shared.bookmarks :as bm]
            [claudia.shared.protocol :as protocol]))

(defn of
  "A person's bookmarked folders as the page gets them: {:items :at} ({:items [] :at 0} before any)."
  [email]
  (if-let [{:keys [items at]} (store/bookmarks email)]
    {:items (bm/clean-items (protocol/read-json items)) :at at}
    {:items [] :at 0}))

(defn save!
  "Keeps items as the person's whole list, as far as they are well formed; the list as kept ({:items :at})."
  [email items]
  (store/save-bookmarks! email (protocol/write-json (bm/clean-items items)))
  (of email))

(defn change
  "What an agent's bookmark op does to items ({:server :folder :label :remove}): the new items, or
  {:error} when the folder is no absolute path."
  [items {:keys [server folder label] drop? :remove}]
  (if-let [f (bm/clean-folder folder)]
    (if drop?
      (bm/unpinned items server f)
      (bm/pinned items server f (bm/clean-label label)))
    {:error (str "not an absolute folder path: " folder)}))
