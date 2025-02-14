(ns collet.actions.stats
  (:require
   [collet.action :as action]
   [collet.utils :as utils]
   [tech.v3.datatype.statistics :as stats]))


(def stats-params-spec
  [:map
   [:sequence [:or utils/dataset? [:sequential utils/dataset?] [:sequential :any]]]
   [:cat? {:optional true} :boolean]])


(defn calc-stats
  "Calculates statistics for the given dataset based on the provided metrics.
   `:metrics` is a map of metric names to operations and columns. The operations
   are :sum :min :max :mean :median :quartiles."
  {:malli/schema [:=> [:cat stats-params-spec]
                  :any]}
  [{:keys [metrics cat? parse] data :sequence}]
  (let [dataset (utils/make-dataset data {:cat? cat? :parse parse})]
    (reduce-kv
     (fn [result k [op column]]
       (let [column  (get dataset column)
             stat-fn (case op
                       :sum stats/sum
                       :min stats/min
                       :max stats/max
                       :mean stats/mean
                       :median stats/median
                       :quartiles stats/quartiles
                       nil)]
         (assoc result k (stat-fn column))))
     {} metrics)))


(defmethod action/action-fn :stats [_]
  calc-stats)