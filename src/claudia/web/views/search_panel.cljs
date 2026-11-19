(ns claudia.web.views.search-panel
  "Search (README decisions 45 and 75): one search for every chat, open and archived (titles,
  projects, what was said), on every screen, a view of the right half: the field on top (focused),
  the project chips under it, then before a word is typed the ten archived chats last active, else
  the finds newest first under time sections, each with the lines that matched lit. A tap on a find
  opens that chat at that message, Search staying beside it; the typed words stay for the next time.
  Opened by the iPad's edge search, the rail's search (screens without the edge), the list's
  search, ⌘K / Ctrl+K, and the right half's dropdown."
  (:require [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.web.search-open :as search-open]
            [claudia.web.views.pure.sidebar :as pure]
            [claudia.web.views.sidebar :as sidebar]))

(def ^:private recent-n "Archived chats listed before a word is typed." 10)

(defn- field [text]
  (let [input (h/use-ref nil)]
    (h/use-effect (fn [] (search-open/take-focus! (.-current input)) nil) [])
    [:div.sp-field
     [:svg {:viewBox "0 0 20 20" :aria-hidden true} [:circle {:cx 9 :cy 9 :r 5}] [:path {:d "M16 16l-3.5-3.5"}]]
     [:input {:ref #(set! (.-current input) %) :type "search" :value (or text "")
              :placeholder "Search chats: titles, projects, what was said" :aria-label "Search chats"
              :on-change #(rf/dispatch-sync [:ops/search (.. % -target -value)])}]]))

(defn- context
  "What the list and the chips need: every row, the chip picked, the rows shown before a word is
  typed, and found-list's options."
  [text found]
  (let [every-row @(rf/subscribe [:all-rows])
        q (pure/search-key text)
        found (when found (pure/with-project-finds found every-row q))]
    {:every every-row :by (sidebar/by-key every-row) :found found :q q
     :chip @(rf/subscribe [:project-filter])
     :recent (pure/recent-archived every-row recent-n)
     :multi? @(rf/subscribe [:multi-server?]) :ro-servers @(rf/subscribe [:read-only-servers])
     :open @(rf/subscribe [:open])}))

(defn- body [text {:keys [found by chip recent q] :as c}]
  (let [m (assoc (select-keys c [:multi? :ro-servers :open]) :archived? false :q q :chip chip :in-scope (:every c))]
    [:<>
     [sidebar/project-chips (if found (pure/found-rows found by) recent) chip]
     [:div.sp-list
      (if found
        [sidebar/found-list found by m text]
        [:<> [:div.cs-section "Recently archived"]
         [sidebar/archived-rows (assoc m :shown (pure/in-project recent chip))]])]]))

(defn pane "Search in the right half." []
  (let [{:keys [text found]} @(rf/subscribe [:search])
        c (context text found)]
    [:div.sp-panel.rv-pane {:role "region" :aria-label "Search chats"}
     [:f> field text]
     [body text c]]))
