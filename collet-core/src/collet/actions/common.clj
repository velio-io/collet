(ns collet.actions.common)


(defn prep-stateful-action
  "Sometimes it's useful to have the previous state available to an action.
   This function will add the previous state to the action's params."
  [{:keys [name params] :as action-spec}]
  (-> action-spec
      (assoc-in [:selectors 'prev-state] [:state name])
      (assoc :params [params 'prev-state])))