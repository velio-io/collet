(ns collet.actions.common)


(defn prep-state-ful-action
  [{:keys [name params] :as action-spec}]
  (-> action-spec
      (assoc-in [:selectors 'prev-state] [:state name])
      (assoc :params [params 'prev-state])))