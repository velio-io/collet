(ns collet.actions.enrich
  (:require
   [collet.utils :as utils]))


(defn enrich
  "Returns another action spec that enriches the input data according to provided parameters"
  [{:keys       [target flatten-target iterate-on action selectors params return method]
    :or         {flatten-target false selectors {}}
    action-name :name
    custom-fn   :fn}]
  (let [base-name        (name action-name)
        slicer-key       (keyword (str base-name "-slicer"))
        iteration-key    (keyword (str base-name "-iteration"))
        action-key       (keyword (str base-name "-action"))
        target-sym       (gensym "target")
        path-sym         (gensym "path")
        args-sym         (gensym "args")
        target-item-path (if (some? iterate-on)
                           [:state slicer-key :current iteration-key]
                           [:state slicer-key :current])
        selectors'       (merge selectors {'$target-item target-item-path})]
    ;; return a vector of three actions
    ;; slicer action
    [{:type      :slicer
      :name      slicer-key
      :selectors {target-sym target}
      :params    (utils/assoc-some
                   {:sequence target-sym
                    :cat?     flatten-target}
                   :flatten-by (when (some? iterate-on)
                                 {iteration-key iterate-on}))}
     ;; enrich (get additional data) action
     (utils/assoc-some
       {:type      action
        :name      action-key
        :selectors selectors'
        :params    params}
       :fn custom-fn
       :return return)
     ;; fold (collect results) action
     {:type      :fold
      :name      action-name
      :selectors {target-sym target
                  path-sym   [:state slicer-key :path]
                  args-sym   [:state action-key]}
      :params    {:value target-sym
                  :op    method
                  :in    path-sym
                  :with  args-sym}}]))


(def enrich-action
  {:prep enrich})
