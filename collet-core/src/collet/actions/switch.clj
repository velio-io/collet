(ns collet.actions.switch
  (:require
   [clojure.walk :as walk]
   [collet.action :as action]
   [collet.conditions :as collet.conds]))


(defn expand-selectors [ctx selectors condition]
  (if (some? selectors)
    (walk/postwalk
     (fn [x]
       (if (and (symbol? x) (contains? selectors x))
         (let [selector-path (get selectors x)]
           (collet.select/select selector-path ctx))
         ;; return as is
         x))
     condition)
    ;; do nothing
    condition))


(defn compile-condition [condition]
  (when (collet.conds/valid-condition? condition)
    (collet.conds/compile-conditions condition)))


(defn execute-actions [ctx actions]
  (reduce
   (fn [c action-fn]
     (action-fn c))
   ctx actions))


(def switch-params-spec
  [:map
   [:selectors {:optional true}
    [:map-of :symbol collet.select/select-path]]
   [:case [:vector
           [:map
            [:condition [:or [:= :default] collet.conds/condition?]]
            [:actions [:vector fn?]]]]]])


(defn switch-action
  "Defines a number of pairs (condition -> set of actions).
   Will probe every condition until one is returned as true.
   Then will execute the respective actions."
  {:malli/schema [:=> [:cat switch-params-spec map?]
                  :any]}
  [{:keys [selectors case]} context]
  (reduce
   (fn [ctx {:keys [condition actions]}]
     (if (= condition :default)
       (reduced
        (execute-actions ctx actions))
       ;; non default condition
       (let [cond-fn (->> condition
                          (expand-selectors ctx selectors)
                          (compile-condition))]
         (if (cond-fn ctx)
           (reduced
            (execute-actions ctx actions))
           ctx))))
   context
   case))


(defmethod action/action-fn :switch [action-spec]
  (partial switch-action (select-keys action-spec [:selectors :case])))