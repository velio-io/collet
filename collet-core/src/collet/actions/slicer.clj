(ns collet.actions.slicer
  (:require
   [tech.v3.dataset :as ds]
   [tech.v3.dataset.column :as ds.col]
   [tech.v3.dataset.join :as ds.join]
   [tech.v3.datatype :as dtype]
   [tech.v3.datatype.bitmap :as dtype.bitmap]
   [tech.v3.datatype.protocols :as dtype.proto]
   [tech.v3.dataset.reductions :as ds.reduce]
   [ham-fisted.api :as hamf]
   [collet.action :as action]
   [collet.conditions :as collet.conds]
   [collet.arrow :as collet.arrow]
   [collet.utils :as utils]
   [collet.select :as collet.select]))


(defn concat-dataset-seq
  [dataset-seq]
  (let [arrow-columns (-> dataset-seq meta :arrow-columns)]
    (cond->> dataset-seq
      (some? arrow-columns)
      (map (fn [dataset]
             (ds/row-map dataset #(collet.arrow/prep-record % arrow-columns))))
      :always
      (apply utils/parallel-concat))))


(defn do-join
  [dataset {:keys [with cat? source target]}]
  (let [dataset        (if (utils/ds-seq? dataset)
                         (concat-dataset-seq dataset)
                         dataset)
        join-ds        (if (utils/ds-seq? with)
                         (concat-dataset-seq with)
                         (utils/make-dataset with {:cat? cat?}))
        source-key     (if (or (keyword? source) (string? source))
                         source
                         :_collet_join_source)
        left-ds        (if (= source-key :_collet_join_source)
                         (ds/row-map dataset
                                     #(hash-map source-key (collet.select/select source %)))
                         dataset)
        target-key     (if (or (keyword? target) (string? target))
                         target
                         :_collet_join_target)
        right-ds       (if (= target-key :_collet_join_target)
                         (ds/row-map join-ds
                                     #(hash-map target-key (collet.select/select target %)))
                         join-ds)
        helper-columns (cond-> []
                         (= source-key :_collet_join_source)
                         (conj source-key)
                         (= target-key :_collet_join_target)
                         (conj target-key))
        joined-ds      (ds.join/left-join
                        [source-key target-key] left-ds right-ds)]
    (apply dissoc joined-ds helper-columns)))


(defn prep-column
  [{:keys [rollup rollup-except column-name target-columns arrow-columns]} column]
  (let [column-name  (or column-name (ds.col/column-name column))
        length       (dtype/ecount column)
        missing      (ds.col/missing column)
        column'      (if (and (pos? length) (seq missing))
                       (let [not-missing (-> (range length)
                                             (dtype.bitmap/->bitmap)
                                             (dtype.proto/set-and-not missing))]
                         (ds.col/select column not-missing))
                       ;; return the original column if there are no missing values
                       column)
        unique       (ds.col/unique column')
        take-one?    (or (contains? target-columns column-name)
                         (and (= (count unique) 1)
                              (or (= rollup :all)
                                  (and (set? rollup)
                                       (if rollup-except
                                         (not (contains? rollup column-name))
                                         (contains? rollup column-name))))))
        arrow-column (when (some? arrow-columns)
                       (collet.arrow/find-column arrow-columns column-name))]
    (cond
      (and take-one? (some? arrow-column))
      (collet.arrow/prep-value (first unique) arrow-column)

      take-one?
      (first unique)

      (some? arrow-columns)
      (mapv #(collet.arrow/prep-value % arrow-column) column')

      :otherwise
      (vec column'))))


(defn vector-reducer
  "Create a reducer that adds all values into a vector."
  [column-name]
  (ds.reduce/reducer
   column-name
   (fn [] [])
   (fn [acc val] (conj acc val))
   (fn [acc1 acc2] (into acc1 acc2))
   identity))


(defn ->agg-columns
  ([columns]
   (->agg-columns columns {}))

  ([columns options]
   (into {}
         (map (fn [[k v]]
                (let [[rf-func rf-col] (if (sequential? v) v [v k])
                      reducer (case rf-func
                                :values (vector-reducer rf-col)
                                :distinct (ds.reduce/distinct
                                           rf-col #(prep-column (assoc options :column-name rf-col) %))
                                :count-distinct (ds.reduce/count-distinct rf-col)
                                :first-value (ds.reduce/first-value rf-col)
                                :row-count (ds.reduce/row-count)
                                :mean (ds.reduce/mean rf-col)
                                :sum (ds.reduce/sum rf-col))]
                  [k reducer])))
         columns)))


(defn do-fold-by
  "Fold dataset by the provided columns"
  [dataset {:keys [by rollup rollup-except columns]
            :or   {rollup false rollup-except false}}]
  (let [rollup'           (cond
                            (true? rollup) :all
                            (vector? rollup) (set rollup)
                            :otherwise :none)
        multiple-columns? (sequential? by)
        target-columns    (if multiple-columns?
                            (set by)
                            #{by})
        keep-columns      (merge (into {} (map #(vector % :distinct)) target-columns)
                                 columns)]
    (let [arrow-columns  (-> dataset meta :arrow-columns)
          finalizer-opts {:rollup         rollup'
                          :rollup-except  rollup-except
                          :target-columns target-columns
                          :arrow-columns  arrow-columns}
          agg-columns    (->agg-columns keep-columns finalizer-opts)]
      (ds.reduce/group-by-column-agg by agg-columns dataset))))


(defn flatten-mapper
  ([dataset f-key f-path]
   (flatten-mapper dataset f-key f-path nil))

  ([dataset f-key f-path arrow-columns]
   (ds/row-mapcat
    dataset
    (fn [row]
      (let [row   (if (some? arrow-columns)
                    (collet.arrow/prep-record row arrow-columns)
                    row)
            value (collet.select/select f-path row)]
        (cond
          (and (sequential? value) (not-empty value))
          (map #(hash-map f-key %) value)

          (not-empty value)
          [{f-key value}]

          :otherwise
          [{f-key nil}]))))))


(defn do-flatten
  [dataset {:keys [by]}]
  ;; TODO allow for multiple flatten-by keys
  (let [[f-key f-path] (first by)]
    (if (utils/ds-seq? dataset)
      (let [arrow-columns (-> dataset meta :arrow-columns)]
        (-> (map #(flatten-mapper % f-key f-path arrow-columns) dataset)
            (with-meta (meta dataset))))
      (flatten-mapper dataset f-key f-path))))


(defn grouper
  [dataset by]
  (if (sequential? by)
    (ds/group-by dataset #(collet.select/select by %))
    (ds/group-by-column dataset by)))


(defn do-group-by
  [dataset {:keys [by join-groups group-col]
            :or   {join-groups true group-col :_group_by_key}}]
  (let [groups (if (utils/ds-seq? dataset)
                 (let [arrow-columns (-> dataset meta :arrow-columns)
                       groups-seq    (cond->> dataset
                                       (some? arrow-columns)
                                       (map (fn [dataset]
                                              (ds/row-map dataset #(collet.arrow/prep-record % arrow-columns))))
                                       :always
                                       (map (fn [dataset]
                                              (grouper dataset by))))]
                   (apply hamf/merge-with ds/concat-inplace groups-seq))
                 ;; single dataset path
                 (grouper dataset by))]
    (if join-groups
      (let [groups-seq (map (fn [[k d]] (assoc d group-col k)) groups)]
        (apply utils/parallel-concat groups-seq))
      groups)))


(defn filterer
  [dataset by predicate]
  (if (sequential? by)
    (ds/filter dataset (collet.conds/compile-conditions by))
    (ds/filter-column dataset by predicate)))


(defn do-filter
  [dataset {:keys [by predicate]}]
  (if (utils/ds-seq? dataset)
    (let [{:keys [arrow-columns] :as ds-meta} (-> dataset meta)
          dataset-seq (cond->> dataset
                        (some? arrow-columns)
                        (map (fn [dataset]
                               (ds/row-map dataset #(collet.arrow/prep-record % arrow-columns))))
                        :always
                        (map (fn [dataset]
                               (filterer dataset by predicate))))]
      ;; arrow-columns are not needed because we already parsed all rows
      (with-meta dataset-seq (dissoc ds-meta :arrow-columns)))
    (filterer dataset by predicate)))


(defn do-order-by
  [dataset {:keys [by comp]}]
  (let [dataset (if (utils/ds-seq? dataset)
                  (concat-dataset-seq dataset)
                  dataset)]
    (if (sequential? by)
      (ds/sort-by dataset #(collet.select/select by %) comp)
      (ds/sort-by-column dataset by comp))))


(defn do-select
  [dataset {:keys [columns rows drop-cols drop-rows]}]
  (let [selecter (fn [dataset]
                   (cond-> dataset
                     (some? columns) (ds/select-columns columns)
                     (some? drop-cols) (ds/drop-columns drop-cols)
                     (some? rows) (ds/select-rows rows)
                     (some? drop-rows) (ds/drop-rows drop-rows)))]
    (if (utils/ds-seq? dataset)
      (-> (map selecter dataset)
          (with-meta (meta dataset)))
      (selecter dataset))))


(defn do-map-with
  [dataset {:keys [with args as-dataset?] :or {as-dataset? false}}]
  (cond
    (utils/ds-seq? dataset)
    (let [{:keys [arrow-columns] :as ds-meta} (-> dataset meta)
          map-fn (if (some? arrow-columns)
                   (fn [row]
                     (apply with (collet.arrow/prep-record row arrow-columns) args))
                   (fn [row]
                     (apply with row args)))]
      (-> (map #(ds/row-map % map-fn) dataset)
          (with-meta (dissoc ds-meta :arrow-columns))))
    (ds/dataset? dataset)
    (ds/row-map dataset with)
    :otherwise
    (cond-> (map with dataset)
      as-dataset? (ds/->dataset))))


(def slicer-params-spec
  [:schema
   {:registry
    {::simple-value   [:or :keyword :string :int]
     ::simple-or-path [:or ::simple-value collet.select/select-path]
     ::flatten        [:tuple [:= :flatten]
                       [:map
                        [:by [:map-of ::simple-value collet.select/select-path]]]]
     ::group          [:tuple [:= :group]
                       [:map
                        [:by ::simple-or-path]
                        [:join-groups {:optional true} :boolean]
                        [:group-col {:optional true} ::simple-value]]]
     ::join           [:tuple [:= :join]
                       [:map
                        [:with [:or utils/dataset? [:sequential map?]]]
                        [:source ::simple-or-path]
                        [:target ::simple-or-path]
                        [:cat? {:optional true} :boolean]]]
     ::fold           [:tuple [:= :fold]
                       [:map
                        [:by [:or ::simple-value [:sequential ::simple-value]]]
                        [:rollup {:optional true} [:or :boolean [:sequential ::simple-value]]]
                        [:rollup-except {:optional true} :boolean]]]
     ::filter         [:tuple [:= :filter]
                       [:map
                        [:by [:or ::simple-value collet.conds/condition?]]
                        [:predicate [:or fn? set?]]]]
     ::order          [:tuple [:= :order]
                       [:map
                        [:by ::simple-or-path]
                        [:comp fn?]]]
     ::select         [:tuple [:= :select]
                       [:map
                        [:columns {:optional true} [:sequential ::simple-value]]
                        [:drop-cols {:optional true} [:sequential ::simple-value]]
                        [:rows {:optional true} [:sequential :int]]
                        [:drop-rows {:optional true} [:sequential :int]]]]
     ::map            [:tuple [:= :map]
                       [:map
                        [:with [:or fn? list?]]
                        [:as-dataset? {:optional true} :boolean]]]}}
   [:map
    [:sequence [:or utils/dataset? [:sequential utils/dataset?] [:sequential :any]]]
    [:cat? {:optional true} :boolean]
    [:apply {:optional true}
     [:+ [:or ::flatten ::group ::join ::fold ::filter ::order ::select ::map]]]]])


(defn prep-dataset
  "Creates a dataset from the provided sequence.
   Can modify the dataset shape by applying flatten-by, group-by and join-with options."
  {:malli/schema [:=> [:cat slicer-params-spec]
                  [:or utils/linked-hash-map? utils/dataset? [:sequential utils/dataset?]]]}
  [{:keys [apply cat? parse] data :sequence}]
  (let [dataset (utils/make-dataset data {:cat? cat? :parse parse})]
    (reduce
     (fn [d [op args]]
       (case op
         :flatten (do-flatten d args)
         :group (do-group-by d args)
         :join (do-join d args)
         :fold (do-fold-by d args)
         :filter (do-filter d args)
         :order (do-order-by d args)
         :select (do-select d args)
         :map (do-map-with d args)
         d))
     dataset apply)))


(defmethod action/action-fn :slicer [_]
  prep-dataset)
