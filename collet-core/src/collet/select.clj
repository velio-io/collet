(ns collet.select
  (:require
   [collet.conditions :as collet.conds]))


(def select-path
  [:schema {:registry
            {::select-element [:or
                               :keyword
                               :string
                               :int
                               [:cat [:= :$/cond] collet.conds/condition?]
                               [:cat [:= :$/op] :keyword]
                               [:cat [:= :$/cat] [:* [:schema [:ref ::select-element]]]]
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

        ;; if the step is a keyword or string, try to get the value from the map
        (or (keyword? step) (string? step) (int? step))
        (let [found (if (int? step)
                      (nth result step)
                      (get result step))]
          (if (not (seq remaining))
            ;; we got to the end of the path, return the value if found
            found
            ;; continue with the next step
            (recur found remaining)))

        ;; return a map where each key is a separate select branch
        (map? step)
        (reduce-kv
         (fn [r k v]
           (let [sub-path (if (vector? v) v [v])
                 value    (select sub-path result)]
             (assoc r k value)))
         {} step)

        ;; special case for executing operations on the result
        (vector? step)
        (let [[op & args] step]
          (case op
            :$/cat
            (when (sequential? result)
              (let [cat-result (reduce
                                (fn [r item]
                                  (let [value (if (not-empty args) (select args item) item)]
                                    (if (some? value)
                                      (conj r value)
                                      r)))
                                [] result)]
                (if (seq remaining)
                  (recur cat-result remaining)
                  cat-result)))

            :$/cond
            (let [condition (collet.conds/compile-conditions (first args))]
              (when (condition result)
                (if (seq remaining)
                  (recur result remaining)
                  result)))

            :$/op
            (when-some [op-fn (get op->fn (first args))]
              (when-some [op-result (apply op-fn result (rest args))]
                (if (seq remaining)
                  (recur op-result remaining)
                  op-result)))))))))
