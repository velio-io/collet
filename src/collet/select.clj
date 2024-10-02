(ns collet.select
  (:require
   [collet.conditions :as collet.conds]))


(def select-path
  [:schema {:registry
            {::select-element [:or
                               :keyword
                               :string
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
  {:malli/schema [:=> [:cat select-path :any [:* [:cat [:= :backtrace] vector?]]]
                  :any]}
  [path data & {:keys [backtrace] :or {backtrace []}}]
  (loop [result data
         path'  (seq path)
         trace  backtrace]
    (let [[step & remaining] path']
      (cond
        (or (nil? result) (nil? step))
        {:value nil :backtrace trace}

        ;; if the step is a keyword or string, try to get the value from the map
        (or (keyword? step) (string? step))
        (let [found  (when (map? result) (get result step))
              trace' (conj trace step)]
          (if (not (seq remaining))
            ;; we got to the end of the path, return the value if found
            {:value found :backtrace trace'}
            ;; continue with the next step
            (recur found remaining trace')))

        ;; return a map where each key is a separate select branch
        (map? step)
        (let [result-map (reduce-kv
                          (fn [r k v]
                            (let [sub-path (if (vector? v) v [v])
                                  {:keys [value backtrace]} (select sub-path result :backtrace trace)]
                              (-> r
                                  (assoc-in [:value k] value)
                                  (assoc-in [:backtrace k] backtrace))))
                          {:value {} :backtrace nil}
                          step)]
          {:value     (:value result-map)
           :backtrace (:backtrace (conj trace result-map))})

        ;; special case for executing operations on the result
        (vector? step)
        (let [[op & args] step]
          (case op
            :cat
            (when (sequential? result)
              (let [result-data (reduce
                                 (fn [{:keys [idx] :as r} item]
                                   (let [{:keys [value backtrace]}
                                         (if (not-empty args)
                                           (select args item :backtrace (conj trace idx))
                                           {:value item :backtrace (conj trace idx)})]
                                     (if (some? value)
                                       (-> r
                                           (update :value conj value)
                                           (update :backtrace conj backtrace)
                                           (update :idx inc))
                                       (update r :idx inc))))
                                 {:value [] :backtrace [] :idx 0}
                                 result)]
                (dissoc result-data :idx)))

            :cond
            (let [condition (collet.conds/compile-conditions (first args))]
              (when (condition result)
                (if (seq remaining)
                  (recur result remaining trace)
                  {:value result :backtrace trace})))

            :op
            (when-some [op-fn (get op->fn (first args))]
              (when-some [op-result (apply op-fn result (rest args))]
                (let [idx    (if (= op :first) 0 (dec (count result)))
                      trace' (conj trace idx)]
                  (if (seq remaining)
                    (recur op-result remaining trace')
                    {:value op-result :backtrace trace'}))))))))))
