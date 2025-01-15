(ns collet.actions.mapper
  (:require
   [collet.action :as action]
   [tech.v3.dataset :as ds]
   [collet.utils :as utils]
   [collet.arrow :as collet.arrow]
   [collet.actions.common :as common]))


(def mapper-params-spec
  [:map
   [:sequence [:or utils/dataset? [:sequential utils/dataset?] [:sequential :any]]]
   [:cat? {:optional true} :boolean]])


(def mapper-state-spec
  [:map
   [:current :any]
   [:idx :int]
   [:next :boolean]
   [:dataset [:or utils/dataset? [:sequential utils/dataset?] [:sequential :any]]]])


(defn map-sequence
  "Will read values from collection one by one and put them into the action state.
   Useful for iterating over collections."
  {:malli/schema [:=> [:cat mapper-params-spec
                       [:maybe mapper-state-spec]]
                  mapper-state-spec]}
  [{:keys [cat?] data-seq :sequence} prev-state]
  (let [state    (if (nil? prev-state)
                   ;; initialize action state
                   (let [dataset (try (utils/make-dataset data-seq {:cat? cat?})
                                      ;; if seq cannot be converted to dataset, use it as is
                                      (catch Exception _e data-seq))
                         ds?     (ds/dataset? dataset)
                         ds-seq? (utils/ds-seq? dataset)]
                     {:dataset       dataset
                      :rows-count    (cond ds? (ds/row-count dataset)
                                           ds-seq? (apply + (map ds/row-count dataset))
                                           :otherwise (count dataset))
                      :arrow-columns (-> dataset meta :arrow-columns)
                      :ds?           ds?
                      :ds-seq?       ds-seq?
                      :ds-seq-idx    0
                      :ds-seq-offset 0
                      :idx           0})
                   ;; use previously created state
                   (update prev-state :idx inc))

        {:keys [dataset arrow-columns rows-count idx
                ds? ds-seq? ds-seq-idx ds-seq-offset]}
        state

        next-idx (inc idx)
        next?    (< next-idx rows-count)]
    ;; return the next state
    (if ds-seq?
      (let [current-dataset      (nth dataset ds-seq-idx)
            current-dataset-size (ds/row-count current-dataset)
            [ds-seq-idx ds-seq-offset current-dataset]
            (if (< idx (+ current-dataset-size ds-seq-offset))
              [ds-seq-idx ds-seq-offset current-dataset]
              [(inc ds-seq-idx)
               (+ ds-seq-offset current-dataset-size)
               (nth dataset (inc ds-seq-idx))])
            current-dataset-idx  (- idx ds-seq-offset)
            current-item         (cond-> (ds/row-at current-dataset current-dataset-idx)
                                   (some? arrow-columns)
                                   (collet.arrow/prep-record arrow-columns))]
        (assoc state
          :current current-item
          :ds-seq-idx ds-seq-idx
          :ds-seq-offset ds-seq-offset
          :idx idx
          :next next?))

      (assoc state
        :current (if ds?
                   (ds/row-at dataset idx)
                   (nth dataset idx))
        :idx idx
        :next next?))))


(defmethod action/action-fn :mapper [_]
  map-sequence)


(defmethod action/prep :mapper [action-spec]
  (common/prep-stateful-action action-spec))


(defmethod action/expand :mapper [task action]
  ;; Unwraps the mapper bindings and replaces the iterator with the mapper keys
  (let [action-name (:name action)]
    (utils/replace-all task {:$mapper/item          [:state action-name :current]
                             :$mapper/has-next-item [:state action-name :next]})))