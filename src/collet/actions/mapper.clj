(ns collet.actions.mapper
  (:require
   [collet.utils :as utils]
   [tech.v3.dataset :as ds]
   [collet.actions.common :as common]))


;; TODO not all collections can be represented as a dataset
;; TODO iterate over maps (regular and produced by the ds/group-by)

(def mapper-params-spec
  [:map
   [:sequence [:sequential :any]]])


(def mapper-state-spec
  [:map
   [:current :any]
   [:idx :int]
   [:next :boolean]
   [:dataset [:or [:sequential :any] utils/dataset?]]])


(defn map-sequence
  "Will read values from collection one by one and put them into the action state.
   Useful for iterating over collections."
  {:malli/schema [:=> [:cat mapper-params-spec
                       [:maybe mapper-state-spec]]
                  mapper-state-spec]}
  [{data-seq :sequence} prev-state]
  (let [state        (if (nil? prev-state)
                       ;; initialize action state
                       {:dataset (if (ds/dataset? data-seq)
                                   data-seq
                                   (ds/->dataset data-seq))
                        :idx     0}
                       ;; use previously created state
                       (update prev-state :idx inc))
        {:keys [dataset idx]} state
        current-item (ds/row-at dataset idx)
        next-idx     (inc idx)]
    ;; return the next state
    {:current current-item
     :idx     idx
     :next    (< next-idx (ds/row-count dataset))
     :dataset dataset}))


(def mapper-action
  {:action map-sequence
   :prep   common/prep-stateful-action})