(ns collet.actions.mapper
  (:require
   [collet.action :as action]
   [collet.actions.common :as common]
   [collet.arrow :as collet.arrow]
   [collet.stream :as stream]
   [collet.utils :as utils]
   [tech.v3.dataset :as ds]))


(def mapper-params-spec
  [:map
   [:sequence [:or utils/dataset? [:fn sequential?]]]
   [:cat? {:optional true} :boolean]])


(def mapper-state-spec
  [:map [:current :any] [:idx :int] [:next :boolean]])


(defn- initial-state [input cat?]
  (if (utils/ds-seq? input)
    (cond-> {:remaining (stream/rows input) :arrow-columns (:arrow-columns (meta input))}
      (:rows-count (meta input)) (assoc :rows-count (:rows-count (meta input))))
    (let [dataset (if (and (sequential? input) (not (counted? input)))
                    (if cat? (sequence cat input) input)
                    (try (utils/make-dataset input {:cat? cat?}) (catch Exception _ input)))]
      (cond-> {:remaining (if (ds/dataset? dataset) (ds/rows dataset) dataset)}
        (or (ds/dataset? dataset) (counted? dataset))
        (assoc :dataset
          dataset :rows-count
          (if (ds/dataset? dataset) (ds/row-count dataset) (count dataset)))))))


(defn map-sequence
  "Advance a row cursor. Only the next row is needed to decide whether to continue."
  {:malli/schema [:=> [:cat mapper-params-spec [:maybe mapper-state-spec]] mapper-state-spec]}
  [{:keys [cat?] input :sequence} prev-state]
  (let [state (or prev-state (initial-state input cat?))
        rows  (seq (if prev-state (::remaining (meta state)) (:remaining state)))
        tail  (next rows)
        item  (first rows)]
    ;; Keep the cursor out of action observations and result serialization.
    (with-meta
      (assoc (dissoc state :remaining)
        :current (when rows
                   (cond-> (if (map? item) (into {} item) item)
                     (:arrow-columns state) (collet.arrow/prep-record (:arrow-columns state))))
        :idx (inc (get state :idx -1))
        :collet.mapper/empty? (nil? rows)
        :next (boolean tail))
      {::remaining tail})))


(defmethod action/action-fn :mapper
  [_]
  map-sequence)


(defmethod action/prep :mapper
  [action-spec]
  (common/prep-stateful-action action-spec))


(defmethod action/expand :mapper
  [task action]
  ;; Unwraps the mapper bindings and replaces the iterator with the mapper keys
  (let [action-name (:name action)]
    (utils/replace-all task
                       {:$mapper/item          [:state action-name :current]
                        :$mapper/has-next-item [:state action-name :next]})))