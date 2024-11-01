(ns collet.actions.mapper
  (:require
   [collet.action :as action]
   [tech.v3.dataset :as ds]
   [collet.utils :as utils]
   [collet.actions.common :as common]))


(def mapper-params-spec
  [:map
   [:sequence [:or utils/dataset? [:sequential :any]]]
   [:cat? {:optional true} :boolean]])


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
  [{:keys [cat?] data-seq :sequence} prev-state]
  (let [state        (if (nil? prev-state)
                       ;; initialize action state
                       {:dataset (try (utils/make-dataset data-seq {:cat? cat?})
                                      ;; if seq cannot be converted to dataset, use it as is
                                      (catch Exception _e data-seq))
                        :idx     0}
                       ;; use previously created state
                       (update prev-state :idx inc))
        {:keys [dataset idx]} state
        ds?          (ds/dataset? dataset)
        current-item (if ds?
                       (ds/row-at dataset idx)
                       (nth dataset idx))
        rows-count   (if ds?
                       (ds/row-count dataset)
                       (count dataset))
        next-idx     (inc idx)]
    ;; return the next state
    {:current current-item
     :idx     idx
     :next    (< next-idx rows-count)
     :dataset dataset}))


(defmethod action/action-fn :mapper [_]
  map-sequence)


(defmethod action/prep :mapper [action-spec]
  (common/prep-stateful-action action-spec))


(defmethod action/expand :mapper [task action]
  ;; Unwraps the mapper bindings and replaces the iterator with the mapper keys
  (let [action-name (:name action)]
    (utils/replace-all task {:$mapper/item          [:state action-name :current]
                             :$mapper/has-next-item [:state action-name :next]})))