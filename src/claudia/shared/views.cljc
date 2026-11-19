(ns claudia.shared.views
  "The views the right half shows, picked in its dropdown (README decision 75), as data: the same
  list on the page and the hub, which keeps each person's pick (GET/PUT /hub/right-view).")

(def all
  "Every view, in the dropdown's order: [id label]."
  [[:picture "Drawing board"]
   [:files "File Browser"]
   [:search "Search"]
   [:settings "Settings"]
   [:words "My words"]
   [:machines "Machines"]
   [:prompts "Prompts"]
   [:forms "Forms"]])

(def ^:private ids (set (map first all)))

(defn clean
  "A view id from what was stored or sent (a keyword or its name), :picture for anything else."
  [v]
  (let [k (keyword (if (keyword? v) (name v) (str v)))]
    (if (ids k) k :picture)))

(defn label "A view's name in the dropdown." [v]
  (some (fn [[id l]] (when (= id v) l)) all))
