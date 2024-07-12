(ns collet.select
  (:require
   [collet.conditions :as collet.conds]))


(def select-path
  [:schema {:registry
            {::select-element [:or
                               :keyword
                               [:cat [:= :cond] [:vector :any]]
                               [:cat [:= :op] :keyword]
                               [:cat [:= :cat] [:* [:schema [:ref ::select-element]]]]
                               [:map-of :keyword [:or [:ref ::select-element]
                                                  [:vector [:ref ::select-element]]]]]}}
   [:sequential [:ref ::select-element]]])


(def op->fn
  {:first first
   :last  last})


(defn select
  "This function represents a small data DSL for extracting values from nested datastructures
   and collections. It also supports joins and conditions."
  {:malli/schema [:=> [:cat select-path :any]
                  :any]}
  [path data]
  (loop [result data
         path'  (seq path)]
    (let [[step & remaining] path']
      (cond
        (or (nil? result) (nil? step))
        nil
        ;; if the step is a keyword, try to get the value from the map
        (keyword? step)
        (let [found (when (map? result) (get result step))]
          (if (not (seq remaining))
            ;; we got to the end of the path, return the value if found
            found
            ;; continue with the next step
            (recur found remaining)))

        (map? step)
        (reduce-kv
         (fn [r k v]
           (let [sub-path (if (vector? v) v [v])]
             (assoc r k (select sub-path result))))
         {} step)

        (vector? step)
        (let [[op & args] step]
          (case op
            :cat
            (when (sequential? result)
              (->> result
                   (map #(select args %))
                   (filter identity)))
            :cond
            (let [condition (collet.conds/compile-conditions (first args))]
              (when (condition result)
                (if (seq remaining)
                  (recur result remaining)
                  result)))

            :op
            (when-some [op-fn (get op->fn (first args))]
              (when-some [op-result (apply op-fn result (rest args))]
                (if (seq remaining)
                  (recur op-result remaining)
                  op-result)))))))))