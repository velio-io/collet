(ns collet.actions.slicer
  (:require
   [collet.select :as collet.select]
   [collet.actions.common :as common]))


(defn flatten-sequence
  "Flatten a nested collection by a given key and path.
   The flatten-by argument is a map, the map key will end up being the key of the flattened collection,
   the map value is the select path to the value which needs to be flattened.
   The keep-keys argument is a map, the map key will be the key of the original collection, the map value
   is the select path to the value which needs to be kept.

   For example, given the following data:
   [{:id 1 :name \"John\" :addresses [{:street \"Main St.\" :city \"Springfield\"} {:street \"NorthG St.\" :city \"Springfield\"}]}
    {:id 2 :name \"Jane\" :addresses [{:street \"Elm St.\" :city \"Springfield\"}]}]

   The following call:
   (flatten-sequence {:flatten-by {:address [:addresses [:cat :street]]}
                      :keep-keys  {:person-id [:id]}}
                     data)

   Will return:
   ({:person-id 1, :address \"Main St.\"}
    {:person-id 1, :address \"NorthG St.\"}
    {:person-id 2, :address \"Elm St.\"})"
  [{:keys [flatten-by keep-keys]} data-seq]
  (let [[f-key f-path] (first flatten-by)
        flatten-fn (fn [root-idx item]
                     (let [selected-keys (some->> keep-keys
                                                  (map (fn [[k p]] [k (-> (collet.select/select p item) :value)]))
                                                  (into {}))
                           {:keys [value backtrace]} (collet.select/select f-path item)]
                       (map-indexed
                        (fn [idx item]
                          {:value     (merge selected-keys {f-key item})
                           :backtrace (vec (cons root-idx (nth backtrace idx)))})
                        value)))]
    (flatten (map-indexed flatten-fn data-seq))))


(defn unwrap-value [x]
  (if (and (map? x) (contains? x :value))
    (:value x)
    x))


(defn group-sequence
  "Group a sequence of maps by a given select path.
   Works in a different way than standard Clojure's group-by.
   First it takes the value under the select path for the first element in the sequence.
   Then it takes all contiguous elements with the same value under the select path.
   And groups them together in the list. This process is repeated until the end of the sequence.
   The result is a lazy sequence where each element is a list of grouped elements.

   For example, given the following data:
   [{:id 1 :name \"John\" :city \"Springfield\"}
    {:id 3 :name \"Jack\" :city \"Springfield\"}
    {:id 2 :name \"Jane\" :city \"Lakeside\"}
    {:id 4 :name \"Jill\" :city \"Lakeside\"}
    {:id 5 :name \"Joe\" :city \"Lakeside\"}
    {:id 3 :name \"Jack\" :city \"Springfield\"}
    {:id 5 :name \"Joe\" :city \"Lakeside\"}]

   The following call:
   (group-sequence [:city] data)

   Will return:
   (({:id 1, :name \"John\", :city \"Springfield\"} {:id 3, :name \"Jack\", :city \"Springfield\"})
    ({:id 2, :name \"Jane\", :city \"Lakeside\"} {:id 4, :name \"Jill\", :city \"Lakeside\"} {:id 5, :name \"Joe\", :city \"Lakeside\"})
    ({:id 3, :name \"Jack\", :city \"Springfield\"})
    ({:id 5, :name \"Joe\", :city \"Lakeside\"}))"
  [group-by data-seq]
  (let [first-item (first data-seq)
        sample     (unwrap-value first-item)]
    (when-let [group-key (-> (collet.select/select group-by sample) :value)]
      (let [batch      (take-while #(= group-key (->> (unwrap-value %)
                                                      (collet.select/select group-by)
                                                      :value))
                                   data-seq)
            batch-size (count batch)]
        (lazy-seq (cons batch (group-sequence group-by (drop batch-size data-seq))))))))


(defn join-sequence
  "Join two sequences of maps by a given select path.
   Works almost like a SQL join.
   :sequence argument is the second sequence to join with.
   :cat? argument is a boolean, if true the sequence will be flattened before joining.
   :on argument is a map of :source and :target keys which define the join condition.

   For example, given the following data:
   [{:id 1 :name \"John\" :city \"Springfield\"}
    {:id 2 :name \"Jane\" :city \"Lakeside\"}
    {:id 3 :name \"Jack\" :city \"Springfield\"}]

   And the following call:
   (join-sequence {:sequence [{:id 1 :city \"Springfield\"}
                              {:id 2 :city \"Lakeside\"}
                              {:id 3 :city \"Springfield\"}]
                   :cat? true
                   :on {:source [:id] :target [:id]}}
                  data)

   Will return:
   ([{:id 1, :name \"John\", :city \"Springfield\"}   {:id 1, :city \"Springfield\"}]
    [{:id 2, :name \"Jane\", :city \"Lakeside\"}      {:id 2, :city \"Lakeside\"}]
    [{:id 3, :name \"Jack\", :city \"Springfield\"}   {:id 3, :city \"Springfield\"}])"
  [{:keys [sequence cat? on] :as join} data-seq]
  (when-let [element (unwrap-value (first data-seq))]
    (let [sequence   (if cat? (flatten sequence) sequence)
          join       (if cat?
                       ;; make sure to apply cat? only once
                       {:sequence sequence :cat? false :on on}
                       join)
          source-key (-> (collet.select/select (:source on) element) :value)
          ;; TODO not very efficient search algorithm
          join-value (reduce
                      (fn [_found item]
                        (when (= (-> (collet.select/select (:target on) item) :value)
                                 source-key)
                          (reduced item)))
                      nil sequence)]
      (lazy-seq
       (cons [element join-value]
             (join-sequence join (rest data-seq)))))))


(defn slice-sequence
  "This is a stateful action which operates on sequences of data.
   In the simplest use case you can use this action to iterate over a sequence of any type.
   Also, it can flatten the given sequence by a nested key or group elements of the sequence by values or join elements between two sequences.
   All operations return a lazy sequence."
  [{:keys [flatten-by keep-keys group-by join cat?] data-seq :sequence}
   prev-state]
  (let [state      (if (nil? prev-state)
                     ;; initialize action state
                     (cond->> data-seq
                       cat? (sequence cat)
                       (some? flatten-by) (flatten-sequence {:flatten-by flatten-by :keep-keys keep-keys})
                       (some? group-by) (group-sequence group-by)
                       (some? join) (join-sequence join)
                       :always (hash-map :rest)
                       :always (merge {:idx 0}))
                     ;; use previously created state
                     (update prev-state :idx inc))
        rest-items (state :rest)
        first-item (first rest-items)]
    ;; return the next state
    {:current (unwrap-value first-item)
     :path    (if (and (map? first-item) (contains? first-item :backtrace))
                (:backtrace first-item)
                ;; mimic backtrace for non-selected items
                [(:idx state)])
     :idx     (:idx state)
     :next    (second rest-items)
     :rest    (rest rest-items)}))


(def slicer-action
  {:action slice-sequence
   :prep   common/prep-stateful-action})