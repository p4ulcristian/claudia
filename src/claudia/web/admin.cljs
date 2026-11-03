(ns claudia.web.admin
  "The admins' Servers and Access pages (GET/PUT/POST/DELETE /hub/admin…): the servers the hub
  reaches (add one already on WireGuard, pair a new one with the key deploy/pair.sh printed, change a
  label or address, remove), and the access grid (person × server: a tick = their own profile there).
  A dialog over the page; every call answers the whole new state."
  (:require [clojure.string :as str]
            [claudia.web.api :as api]
            [claudia.web.format :as format]
            [re-frame.core :as rf]
            [reagent.core :as r]))

;; ---- events ----

(rf/reg-event-fx
 :admin/open
 (fn [{:keys [db]} [_ tab]]
   {:db (assoc db :admin {:tab (or tab :servers) :state nil :error nil})
    :admin/call {:method "GET" :path "/hub/admin"}}))

(rf/reg-event-db :admin/close (fn [db _] (dissoc db :admin)))

(rf/reg-event-db :admin/tab (fn [db [_ tab]] (assoc-in db [:admin :tab] tab)))

(rf/reg-event-fx
 :admin/call
 (fn [_ [_ method path body on-ok]]
   {:admin/call {:method method :path path :body body :on-ok on-ok}}))

(rf/reg-event-fx
 :admin/loaded
 (fn [{:keys [db]} [_ on-ok state]]
   (when (:admin db)
     (cond-> {:db (update db :admin assoc :state state :error nil)}
       on-ok (assoc :dispatch on-ok)))))

(rf/reg-event-db
 :admin/failed
 (fn [db [_ text]] (cond-> db (:admin db) (assoc-in [:admin :error] text))))

(rf/reg-sub :admin (fn [db _] (:admin db)))

(rf/reg-fx
 :admin/call
 (fn [{:keys [method path body on-ok]}]
   (-> (api/call-json method path body)
       (.then #(rf/dispatch [:admin/loaded on-ok %]))
       (.catch #(rf/dispatch [:admin/failed (.-message %)])))))

;; ---- what the forms send ----

(defn server-path [server-name] (str "/hub/admin/servers/" (js/encodeURIComponent server-name)))

(defn person-path [email] (str "/hub/admin/people/" (js/encodeURIComponent email)))

(defn pair-body
  "What Pair sends: the key, and a name and label when given (all trimmed)."
  [form]
  (cond-> {:key (str/trim (:key form))}
    (not (str/blank? (:name form))) (assoc :name (str/trim (:name form)))
    (not (str/blank? (:label form))) (assoc :label (str/trim (:label form)))))

(defn pairing-note
  "A server's pairing as [short state, longer tip]."
  [{:keys [pairing peerUp]}]
  (cond
    (not pairing) ["on WireGuard" "Added by its address (already a WireGuard peer)"]
    (:fetchedAt pairing) ["fetched" (str "Paired " (format/when-text (:createdAt pairing)) "; the server fetched its address "
                                         (format/when-text (:fetchedAt pairing)) " (" (:ip pairing) ")")]
    peerUp ["peer up" (str "The WireGuard peer is up (" (:ip pairing) "); waiting for the server to fetch its address")]
    :else ["waiting for peer" (str "Paired " (format/when-text (:createdAt pairing)) "; the WireGuard peer isn't added yet")]))

;; ---- views ----

(defn- call! [method path & [body on-ok]]
  (rf/dispatch [:admin/call method path body on-ok]))

(defn- value-of [e] (.. e -target -value))

(defn- remove-buttons
  "× asks first: Remove (on-remove) or Keep (on-keep)."
  [asking? title on-ask on-remove on-keep]
  (if asking?
    [:<>
     [:button.danger {:on-click on-remove} "Remove"]
     [:button {:on-click on-keep} "Keep"]]
    [:button {:title title :on-click on-ask} "×"]))

(defn- server-row [s]
  (let [label (r/atom (:label s))
        address (r/atom (:address s))
        removing (r/atom false)]
    (fn [s]
      (let [[note tip] (pairing-note s)
            changed? (or (not= (str/trim @label) (:label s)) (not= (str/trim @address) (:address s)))
            path (server-path (:name s))]
        [:tr
         [:td [:input {:value @label :aria-label (str (:name s) "'s label") :on-change #(reset! label (value-of %))}]]
         [:td.mono (:name s)]
         [:td [:input.mono {:value @address :aria-label (str (:name s) "'s address") :spell-check false
                            :on-change #(reset! address (value-of %))}]]
         [:td [:span.pair-state {:class (str/replace note " " "-") :data-tip tip} note]]
         [:td.row-actions
          (when changed?
            [:button {:on-click #(call! "PUT" path {:label (str/trim @label) :address (str/trim @address)})} "Save"])
          [remove-buttons @removing "Remove this server (its WireGuard peer too, if paired here)"
           #(reset! removing true) #(call! "DELETE" path) #(reset! removing false)]]]))))

(defn- servers-table [servers]
  [:section
   [:h3 "Servers " [:span.muted.small (count servers)]]
   (if (seq servers)
     [:table.admin-table
      [:thead [:tr [:th "Label"] [:th "Name"] [:th "Address"] [:th "Pairing"] [:th]]]
      [:tbody (for [s servers] ^{:key (:name s)} [server-row s])]]
     [:p.muted.small "None yet."])])

(defn- field
  "An input bound to field k of the form atom a."
  [tag a k attrs]
  [tag (assoc attrs :value (k @a) :on-change #(swap! a assoc k (value-of %)))])

(defn- submit [f] (fn [e] (.preventDefault e) (f)))

(defn- add-form [add]
  [:section
   [:h3 "Add a server " [:span.muted.small "already on WireGuard"]]
   [:form.admin-form
    {:on-submit (submit #(let [{server-name :name :keys [label address]} @add]
                           (call! "PUT" (server-path (str/trim server-name))
                                  {:label (str/trim label) :address (str/trim address)}
                                  [:admin/reset-form add])))}
    [field :input add :name {:placeholder "name (team)" :spell-check false}]
    [field :input add :label {:placeholder "Label (Team server)"}]
    [field :input.mono add :address {:placeholder "host:port" :spell-check false}]
    [:button {:disabled (or (str/blank? (:name @add)) (str/blank? (:address @add)))} "Add"]]])

(defn- pair-form [pair pairing]
  [:section
   [:h3 "Pair a new server " [:span.muted.small "the key its pair script printed"]]
   (if-not (:available pairing)
     [:p.muted.small "Pairing is unavailable on this hub (it has no WireGuard address pool). Add servers already on WireGuard above."]
     [:form.admin-form.pair
      {:on-submit (submit #(call! "POST" "/hub/admin/pair" (pair-body @pair) [:admin/reset-form pair]))}
      [field :input.mono.key pair :key {:placeholder "claudia-pair:…" :spell-check false :auto-complete "off"}]
      [field :input pair :name {:placeholder "name (optional)" :spell-check false}]
      [field :input pair :label {:placeholder "Label (optional)"}]
      [:button {:disabled (str/blank? (:key @pair))} "Pair"]])])

(defn- servers-page [_]
  (let [add (r/atom {:name "" :label "" :address ""})
        pair (r/atom {:key "" :name "" :label ""})]
    (fn [{:keys [servers pairing]}]
      [:<>
       [servers-table servers]
       [add-form add]
       [pair-form pair pairing]])))

;; A form's inputs are emptied once its call succeeded (the atom travels in the event).
(rf/reg-event-fx
 :admin/reset-form
 (fn [_ [_ a]] (swap! a #(zipmap (keys %) (repeat ""))) nil))

(defn- access-cell [p s on]
  [:td.cell [:input {:type "checkbox" :checked on :aria-label (str p " on " (:label s))
                     :on-change #(call! "PUT" "/hub/admin/access" {:email p :server (:name s) :granted (not on)})}]])

(defn- person-row [{p :email :as person} servers granted removing]
  [:tr
   [:td.person p]
   (for [s servers]
     ^{:key (:name s)} [access-cell p s (contains? granted [p (:name s)])])
   [:td.cell [:input {:type "checkbox" :checked (boolean (:admin person)) :aria-label (str p " is an admin")
                      :on-change #(call! "PUT" (person-path p) {:admin (not (:admin person))})}]]
   [:td.row-actions
    [remove-buttons (= @removing p) "Remove this person (and every tick)"
     #(reset! removing p)
     #(do (reset! removing nil) (call! "DELETE" (person-path p)))
     #(reset! removing nil)]]])

(defn- access-grid [{:keys [servers people access]} removing]
  (let [granted (set (map (juxt :email :server) access))]
    [:div.admin-grid-wrap
     [:table.admin-table.grid
      [:thead [:tr [:th "Person"] (for [s servers] ^{:key (:name s)} [:th {:title (:name s)} (:label s)]) [:th "Admin"] [:th]]]
      [:tbody
       (for [person people]
         ^{:key (:email person)} [person-row person servers granted removing])]]]))

(defn- person-form [email]
  [:section
   [:h3 "Add a person " [:span.muted.small "by their sign-in email"]]
   [:form.admin-form.person
    {:on-submit (submit #(call! "POST" "/hub/admin/people" {:email (str/trim @email)} [:admin/reset-email email]))}
    [:input {:type "email" :placeholder "email" :spell-check false :value @email :on-change #(reset! email (value-of %))}]
    [:button {:disabled (str/blank? @email)} "Add"]]])

(defn- access-page [_]
  (let [email (r/atom "")
        removing (r/atom nil)]
    (fn [{:keys [people] :as state}]
      [:<>
       [:section
        [:h3 "Who works where " [:span.muted.small "a tick = their own profile on that server"]]
        (if (seq people)
          [access-grid state removing]
          [:p.muted.small "Nobody yet."])]
       [person-form email]])))

(rf/reg-event-fx :admin/reset-email (fn [_ [_ a]] (reset! a "") nil))

(defn- on-key [e] (when (= "Escape" (.-key e)) (rf/dispatch [:admin/close])))

(defn- tabs [tab]
  [:header
   [:div.profile-tabs {:role "tablist"}
    (for [[t label] [[:servers "Servers"] [:access "Access"]]]
      ^{:key t}
      [:button {:role "tab" :aria-selected (= tab t) :class (when (= tab t) "on")
                :on-click #(rf/dispatch [:admin/tab t])} label])]
   [:button.icon {:title "Close (Esc)" :on-click #(rf/dispatch [:admin/close])} "×"]])

(defn dialog
  "The dialog, when open (nothing otherwise)."
  []
  (r/with-let [_ (js/window.addEventListener "keydown" on-key)]
    (when-let [{:keys [tab state error]} @(rf/subscribe [:admin])]
      [:div.settings-backdrop {:on-click #(rf/dispatch [:admin/close])}
       [:div.settings.hub-admin {:role "dialog" :aria-label (if (= tab :servers) "Servers" "Access")
                                 :on-click #(.stopPropagation %)}
        [tabs tab]
        (when error [:p.token-error error])
        (cond
          (nil? state) [:p.muted.small "Loading…"]
          (= tab :servers) [servers-page state]
          :else [access-page state])]])
    (finally (js/window.removeEventListener "keydown" on-key))))

(defn links
  "The way in, for admins: two small buttons (in the chat list's head)."
  []
  (when (:admin @(rf/subscribe [:me]))
    [:span.admin-links
     [:button {:title "Servers (admins)" :on-click #(rf/dispatch [:admin/open :servers])} "Servers"]
     [:button {:title "Access (admins)" :on-click #(rf/dispatch [:admin/open :access])} "Access"]]))
