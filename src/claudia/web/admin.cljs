(ns claudia.web.admin
  "The admins' People and Servers pages (GET/PUT/POST/DELETE /hub/admin…): the people table (per
  person: which servers they use, whose chats they may write to, admin), and the servers the hub
  reaches (pair one with the key deploy/pair.sh printed, which gives it its hub key; change a label
  or address; remove). A dialog over the page; every call answers the whole new state."
  (:require [clojure.string :as str]
            [claudia.web.api :as api]
            [claudia.web.format :as format]
            [re-frame.core :as rf]
            [reagent.core :as r]))

;; ---- events ----

(rf/reg-event-fx
 :admin/open
 (fn [{:keys [db]} [_ tab]]
   {:db (assoc db :admin {:tab (or tab :people) :state nil :error nil})
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
  "What Pair sends: the key, and an address, name and label when given (all trimmed)."
  [form]
  (into {:key (str/trim (:key form))}
        (for [k [:address :name :label] :when (not (str/blank? (k form)))]
          [k (str/trim (k form))])))

(defn pairing-note
  "A server's pairing as [short state, longer tip]."
  [{:keys [pairing]}]
  (cond
    (not pairing) ["by hand" "Its hub key was put on it by hand, not by pairing"]
    (:fetchedAt pairing) ["paired" (str "Paired " (format/when-text (:createdAt pairing)) "; the server fetched its hub key "
                                        (format/when-text (:fetchedAt pairing)))]
    :else ["waiting" (str "Paired " (format/when-text (:createdAt pairing)) "; waiting for the server to fetch its hub key")]))

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
          [remove-buttons @removing "Remove this server"
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

(defn- pair-form [pair]
  [:section
   [:h3 "Pair a server " [:span.muted.small "the key its pair script printed; an existing name gets its key again"]]
   [:form.admin-form.pair
    {:on-submit (submit #(call! "POST" "/hub/admin/pair" (pair-body @pair) [:admin/reset-form pair]))}
    [field :input.mono.key pair :key {:placeholder "claudia-pair:…" :spell-check false :auto-complete "off"}]
    [field :input.mono pair :address {:placeholder "host:port (its front, on your private network)" :spell-check false}]
    [field :input pair :name {:placeholder "name (optional)" :spell-check false}]
    [field :input pair :label {:placeholder "Label (optional)"}]
    [:button {:disabled (str/blank? (:key @pair))} "Pair"]]])

(defn builder-line
  "What the Release builder section says about the builder (README decision 29)."
  [{:keys [host pairedAt seenAt]}]
  (if host
    (str host ", paired " (format/when-text pairedAt) (if seenAt (str ", last asked " (format/when-text seenAt)) ", not heard from yet"))
    "None yet: releases come from GitHub."))

(defn- builder-form [b builder]
  [:section
   [:h3 "Release builder " [:span.muted.small "the machine that builds and signs releases"]]
   [:p.muted.small (builder-line builder)]
   [:form.admin-form.pair
    {:on-submit (submit #(call! "POST" "/hub/admin/builder" {:key (str/trim (:key @b))} [:admin/reset-form b]))}
    [field :input.mono.key b :key {:placeholder "claudia-builder:…" :spell-check false :auto-complete "off"}]
    [:button {:disabled (str/blank? (:key @b))} (if (:host builder) "Replace" "Pair")]]])

(defn- servers-page [_]
  (let [pair (r/atom {:key "" :address "" :name "" :label ""})
        b (r/atom {:key ""})]
    (fn [{:keys [servers builder]}]
      [:<>
       [servers-table servers]
       [pair-form pair]
       [builder-form b builder]])))

;; A form's inputs are emptied once its call succeeded (the atom travels in the event).
(rf/reg-event-fx
 :admin/reset-form
 (fn [_ [_ a]] (swap! a #(zipmap (keys %) (repeat ""))) nil))

(defn- tick
  "A checkbox cell: on, its label, and what a click sends."
  [on label method path body]
  [:td.cell [:input {:type "checkbox" :checked (boolean on) :aria-label label
                     :on-change #(call! method path body)}]])

(defn person-label [{:keys [email] person-name :name}]
  (if (str/blank? person-name) email person-name))

(defn- server-ticks [p servers granted]
  (for [s servers
        :let [on (contains? granted [p (:name s)])]]
    ^{:key (:name s)}
    [tick on (str p " on " (:label s)) "PUT" "/hub/admin/access" {:email p :server (:name s) :granted (not on)}]))

(defn- writer-ticks [p people writes]
  (for [{owner :email :as o} people]
    (if (= owner p)
      ^{:key owner} [:td.cell.self "—"]
      (let [on (contains? writes [p owner])]
        ^{:key owner}
        [tick on (str p " may write to " (person-label o) "'s chats") "PUT" "/hub/admin/writers"
         {:email p :owner owner :granted (not on)}]))))

(defn- profile-cell
  "The person's profile name (their agent user on every server is <profile>-claudia-agent, the
  only name the hub signs SSH certificates for); Save when it was changed."
  [{profile :profile}]
  (let [v (r/atom (or profile ""))]
    (fn [{p :email profile :profile}]
      [:td.profile
       [:input.mono {:value @v :placeholder "profile" :spell-check false :size 10
                     :aria-label (str p "'s profile name") :on-change #(reset! v (value-of %))}]
       (when (not= (str/trim @v) (or profile ""))
         [:button {:on-click #(call! "PUT" (person-path p) {:profile (str/trim @v)})} "Save"])])))

(defn- person-row [{p :email :as person} removing]
  [:tr
   [:td.person {:title p} (person-label person)]
   [profile-cell person]
   [tick (:admin person) (str p " is an admin") "PUT" (person-path p) {:admin (not (:admin person))}]
   [:td.row-actions
    [remove-buttons (= @removing p) "Remove this person (and every tick)"
     #(reset! removing p)
     #(do (reset! removing nil) (call! "DELETE" (person-path p)))
     #(reset! removing nil)]]])

(defn- people-table
  "Who they are: profile name, admin, remove."
  [people removing]
  [:div.admin-grid-wrap
   [:table.admin-table.grid
    [:thead [:tr [:th "Person"] [:th "Profile"] [:th "Admin"] [:th]]]
    [:tbody (for [person people] ^{:key (:email person)} [person-row person removing])]]])

(defn- tick-table
  "One tick table: a row per person, a column per thing (servers, or people whose chats)."
  [cols col-key col-label people cells]
  [:div.admin-grid-wrap
   [:table.admin-table.grid
    [:thead [:tr [:th "Person"]
             (for [c cols] ^{:key (col-key c)} [:th {:title (col-key c)} (col-label c)])]]
    [:tbody
     (for [{p :email :as person} people]
       ^{:key p} [:tr [:td.person {:title p} (person-label person)] (cells p)])]]])

(defn- access-tables [{:keys [servers people access writers]} removing]
  (let [granted (set (map (juxt :email :server) access))
        writes (set (map (juxt :email :owner) writers))]
    [:<>
     [:section [:h3 "People"] [people-table people removing]]
     [:section [:h3 "Servers " [:span.muted.small "a tick: their own profile on that server"]]
      [tick-table servers :name :label people #(server-ticks % servers granted)]]
     [:section [:h3 "Chats " [:span.muted.small "a tick: they may write in that person's chats"]]
      [tick-table people :email person-label people #(writer-ticks % people writes)]]]))

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
       (if (seq people)
         [access-tables state removing]
         [:section [:h3 "People"] [:p.muted.small "Nobody yet."]])
       [:p.muted.small "Each tick takes effect on their next request. Servers: their own profile there; "
        "their chats run as <profile>-claudia-agent, the only user the hub gives SSH certificates. "
        "Chats: they may open, start and send in that person's chats on every server where both have a "
        "profile; Claude acts in that person's name and the activity log records who typed. Everyone "
        "may read everyone's chats without a tick."]
       [person-form email]])))

(rf/reg-event-fx :admin/reset-email (fn [_ [_ a]] (reset! a "") nil))

(defn- on-key [e] (when (= "Escape" (.-key e)) (rf/dispatch [:admin/close])))

(defn- tabs [tab]
  [:header
   [:div.profile-tabs {:role "tablist"}
    (for [[t label] [[:people "People"] [:servers "Servers"]]]
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
       [:div.settings.hub-admin {:role "dialog" :aria-label (if (= tab :servers) "Servers" "People")
                                 :on-click #(.stopPropagation %)}
        [tabs tab]
        (when error [:p.token-error error])
        (cond
          (nil? state) [:p.muted.small "Loading…"]
          (= tab :servers) [servers-page state]
          :else [access-page state])]])
    (finally (js/window.removeEventListener "keydown" on-key))))

(defn links
  "The way in, for admins: a small button (in the chat list's head)."
  []
  (when (:admin @(rf/subscribe [:me]))
    [:span.admin-links
     [:button {:title "People and servers (admins)" :on-click #(rf/dispatch [:admin/open :people])} "Admin"]]))
