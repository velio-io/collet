(ns collet.actions.slicer
  (:require
   [collet.select :as collet.select]
   [collet.actions.common :as common]))


(defn flatten-sequence [{:keys [flatten-by keep-keys]} data-seq]
  (let [[f-key f-path] (first flatten-by)
        flatten-fn (fn [item]
                     (let [selected-keys (->> keep-keys
                                              (map (fn [[k p]] [k (collet.select/select p item)]))
                                              (into {}))]
                       (->> (collet.select/select f-path item)
                            (map #(merge selected-keys {f-key %})))))]
    (mapcat flatten-fn data-seq)))


(defn group-sequence [group-by data-seq]
  (when-let [group-key (collet.select/select group-by (first data-seq))]
    (let [batch      (take-while #(= group-key (collet.select/select group-by %)) data-seq)
          batch-size (count batch)]
      (lazy-seq (cons batch (group-sequence group-by (drop batch-size data-seq)))))))


(defn join-sequence [{:keys [sequence cat? on] :as join} data-seq]
  (when-let [element (first data-seq)]
    (let [sequence   (if cat? (flatten sequence) sequence)
          join       (if cat?
                       ;; make sure to apply cat? only once
                       {:sequence sequence :cat? false :on on}
                       join)
          source-key (collet.select/select (:source on) element)
          ;; TODO not very efficient search algorithm
          join-value (reduce
                      (fn [_found item]
                        (when (= (collet.select/select (:target on) item)
                                 source-key)
                          (reduced item)))
                      nil sequence)]
      (lazy-seq
       (cons [element join-value]
             (join-sequence join (rest data-seq)))))))


(defn slice-sequence
  [{:keys [flatten-by keep-keys group-by join cat?] data-seq :sequence}
   prev-state]
  (let [state      (if (nil? prev-state)
                     ;; initialize action state
                     (cond->> data-seq
                       cat? (sequence cat)
                       (some? flatten-by) (flatten-sequence {:flatten-by flatten-by :keep-keys keep-keys})
                       (some? group-by) (group-sequence group-by)
                       (some? join) (join-sequence join)
                       :always (hash-map :rest))
                     ;; use previously created state
                     prev-state)
        rest-items (state :rest)]
    ;; return the next state
    {:current (first rest-items)
     :next    (second rest-items)
     :rest    (rest rest-items)}))


(def slicer-action
  {:action slice-sequence
   :prep   common/prep-state-ful-action})