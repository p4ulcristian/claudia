(ns claudia.web.admin
  "The admins' Servers and Access pages (GET/PUT/POST/DELETE /hub/admin…): the servers the hub
  reaches (add one already on WireGuard, pair a new one with the key deploy/pair.sh printed, change a
  label or address, remove), and the access grid (person × server: a tick = their own profile there).
  A dialog over the page; every call answers the whole new state."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [claudia.shared.protocol :as protocol]))

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
   (-> (js/fetch path (clj->js (cond-> {:method method :credentials "same-origin"
                                        :headers (cond-> {"accept" "application/json"}
                                                   body (assoc "content-type" "application/json"))}
                                 body (assoc :body (protocol/write-json body)))))
       (.then (fn [^js res]
                (.then (.text res)
                       (fn [t]
                         (let [v (try (protocol/read-json t) (catch :default _ nil))]
                           (if (.-ok res)
                             (rf/dispatch [:admin/loaded on-ok v])
                             (rf/dispatch [:admin/failed (or (:error v) (str "HTTP " (.-status res)))])))))))
       (.catch #(rf/dispatch [:admin/failed (.-message %)])))))

;; ---- views ----

(defn- call! [method path & [body on-ok]]
  (rf/dispatch [:admin/call method path body on-ok]))

(defn- when-text [at]
  (when at (.toLocaleString (js/Date. at) js/undefined #js {:dateStyle "medium" :timeStyle "short"})))

(defn- pairing-note [{:keys [pairing peerUp]}]
  (cond
    (not pairing) ["on WireGuard" "Added by its address (already a WireGuard peer)"]
    (:fetchedAt pairing) ["fetched" (str "Paired " (when-text (:createdAt pairing)) "; the server fetched its address "
                                         (when-text (:fetchedAt pairing)) " (" (:ip pairing) ")")]
    peerUp ["peer up" (str "The WireGuard peer is up (" (:ip pairing) "); waiting for the server to fetch its address")]
    :else ["waiting for peer" (str "Paired " (when-text (:createdAt pairing)) "; the WireGuard peer isn't added yet")]))

(defn- server-row [s]
  (let [label (r/atom (:label s))
        address (r/atom (:address s))
        removing (r/atom false)]
    (fn [s]
      (let [[note tip] (pairing-note s)
            changed? (or (not= (str/trim @label) (:label s)) (not= (str/trim @address) (:address s)))
            path (str "/hub/admin/servers/" (js/encodeURIComponent (:name s)))]
        [:tr
         [:td [:input {:value @label :aria-label (str (:name s) "'s label") :on-change #(reset! label (.. % -target -value))}]]
         [:td.mono (:name s)]
         [:td [:input.mono {:value @address :aria-label (str (:name s) "'s address") :spell-check false
                            :on-change #(reset! address (.. % -target -value))}]]
         [:td [:span.pair-state {:title tip} note]]
         [:td.row-actions
          (when changed?
            [:button {:on-click #(call! "PUT" path {:label (str/trim @label) :address (str/trim @address)})} "Save"])
          (if @removing
            [:<>
             [:button.danger {:on-click #(call! "DELETE" path)} "Remove"]
             [:button {:on-click #(reset! removing false)} "Keep"]]
            [:button {:title "Remove this server (its WireGuard peer too, if paired here)"
                      :on-click #(reset! removing true)} "×"])]]))))

(defn- servers-page [_]
  (let [add (r/atom {:name "" :label "" :address ""})
        pair (r/atom {:key "" :name "" :label ""})]
    (fn [{:keys [servers pairing]}]
      [:<>
       [:section
        [:h3 "Servers " [:span.muted.small (count servers)]]
        (if (seq servers)
          [:table.admin-table
           [:thead [:tr [:th "Label"] [:th "Name"] [:th "Address"] [:th "Pairing"] [:th]]]
           [:tbody (for [s servers] ^{:key (:name s)} [server-row s])]]
          [:p.muted.small "None yet."])]
       [:section
        [:h3 "Add a server " [:span.muted.small "already on WireGuard"]]
        [:form.admin-form
         {:on-submit (fn [e]
                       (.preventDefault e)
                       (let [{:keys [name label address]} @add]
                         (call! "PUT" (str "/hub/admin/servers/" (js/encodeURIComponent (str/trim name)))
                                {:label (str/trim label) :address (str/trim address)}
                                [:admin/reset-form add])))}
         [:input {:placeholder "name (team)" :spell-check false :value (:name @add) :on-change #(swap! add assoc :name (.. % -target -value))}]
         [:input {:placeholder "Label (Team server)" :value (:label @add) :on-change #(swap! add assoc :label (.. % -target -value))}]
         [:input.mono {:placeholder "host:port" :spell-check false :value (:address @add) :on-change #(swap! add assoc :address (.. % -target -value))}]
         [:button {:disabled (or (str/blank? (:name @add)) (str/blank? (:address @add)))} "Add"]]]
       [:section
        [:h3 "Pair a new server " [:span.muted.small "the key its pair script printed"]]
        (if-not (:available pairing)
          [:p.muted.small "Pairing is unavailable on this hub (it has no WireGuard address pool). Add servers already on WireGuard above."]
          [:form.admin-form.pair
           {:on-submit (fn [e]
                         (.preventDefault e)
                         (let [{:keys [key name label]} @pair]
                           (call! "POST" "/hub/admin/pair"
                                  (cond-> {:key (str/trim key)}
                                    (not (str/blank? name)) (assoc :name (str/trim name))
                                    (not (str/blank? label)) (assoc :label (str/trim label)))
                                  [:admin/reset-form pair])))}
           [:input.mono.key {:placeholder "claudia-pair:…" :spell-check false :auto-complete "off"
                             :value (:key @pair) :on-change #(swap! pair assoc :key (.. % -target -value))}]
           [:input {:placeholder "name (optional)" :spell-check false :value (:name @pair) :on-change #(swap! pair assoc :name (.. % -target -value))}]
           [:input {:placeholder "Label (optional)" :value (:label @pair) :on-change #(swap! pair assoc :label (.. % -target -value))}]
           [:button {:disabled (str/blank? (:key @pair))} "Pair"]])]])))

;; A form's inputs are emptied once its call succeeded (the atom travels in the event).
(rf/reg-event-fx
 :admin/reset-form
 (fn [_ [_ a]] (swap! a #(zipmap (keys %) (repeat ""))) nil))

(defn- access-page [_]
  (let [email (r/atom "")
        removing (r/atom nil)]
    (fn [{:keys [servers people access]}]
      (let [granted (set (map (juxt :email :server) access))]
        [:<>
         [:section
          [:h3 "Who works where " [:span.muted.small "a tick = their own profile on that server"]]
          (if (seq people)
            [:div.admin-grid-wrap
             [:table.admin-table.grid
              [:thead [:tr [:th "Person"] (for [s servers] ^{:key (:name s)} [:th {:title (:name s)} (:label s)]) [:th "Admin"] [:th]]]
              [:tbody
               (for [{p :email :as person} people]
                 ^{:key p}
                 [:tr
                  [:td.person p]
                  (for [s servers
                        :let [on (contains? granted [p (:name s)])]]
                    ^{:key (:name s)}
                    [:td.cell [:input {:type "checkbox" :checked on :aria-label (str p " on " (:label s))
                                       :on-change #(call! "PUT" "/hub/admin/access" {:email p :server (:name s) :granted (not on)})}]])
                  [:td.cell [:input {:type "checkbox" :checked (boolean (:admin person)) :aria-label (str p " is an admin")
                                     :on-change #(call! "PUT" (str "/hub/admin/people/" (js/encodeURIComponent p))
                                                        {:admin (not (:admin person))})}]]
                  [:td.row-actions
                   (if (= @removing p)
                     [:<>
                      [:button.danger {:on-click #(do (reset! removing nil)
                                                      (call! "DELETE" (str "/hub/admin/people/" (js/encodeURIComponent p))))} "Remove"]
                      [:button {:on-click #(reset! removing nil)} "Keep"]]
                     [:button {:title "Remove this person (and every tick)" :on-click #(reset! removing p)} "×"])]])]]]
            [:p.muted.small "Nobody yet."])]
         [:section
          [:h3 "Add a person " [:span.muted.small "by their sign-in email"]]
          [:form.admin-form.person
           {:on-submit (fn [e]
                         (.preventDefault e)
                         (call! "POST" "/hub/admin/people" {:email (str/trim @email)} [:admin/reset-email email]))}
           [:input {:type "email" :placeholder "email" :spell-check false :value @email :on-change #(reset! email (.. % -target -value))}]
           [:button {:disabled (str/blank? @email)} "Add"]]]]))))

(rf/reg-event-fx :admin/reset-email (fn [_ [_ a]] (reset! a "") nil))

(defn- on-key [e] (when (= "Escape" (.-key e)) (rf/dispatch [:admin/close])))

(defn dialog
  "The dialog, when open (nothing otherwise)."
  []
  (r/with-let [_ (js/window.addEventListener "keydown" on-key)]
    (when-let [{:keys [tab state error]} @(rf/subscribe [:admin])]
      [:div.admin-backdrop {:on-click #(rf/dispatch [:admin/close])}
       [:div.admin-dialog {:role "dialog" :aria-label (if (= tab :servers) "Servers" "Access")
                           :on-click #(.stopPropagation %)}
        [:header
         [:div.admin-tabs {:role "tablist"}
          (for [[t label] [[:servers "Servers"] [:access "Access"]]]
            ^{:key t}
            [:button {:role "tab" :aria-selected (= tab t) :class (when (= tab t) "on")
                      :on-click #(rf/dispatch [:admin/tab t])} label])]
         [:button.admin-close {:title "Close (Esc)" :on-click #(rf/dispatch [:admin/close])} "×"]]
        (when error [:p.admin-error error])
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
