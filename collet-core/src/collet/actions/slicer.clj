(ns collet.actions.slicer
  (:require
   [tech.v3.dataset :as ds]
   [tech.v3.dataset.column :as ds.col]
   [tech.v3.dataset.join :as ds.join]
   [tech.v3.datatype :as dtype]
   [tech.v3.datatype.bitmap :as dtype.bitmap]
   [tech.v3.datatype.protocols :as dtype.proto]
   [tech.v3.dataset.reductions :as ds.reduce]
   [collet.action :as action]
   [collet.conditions :as collet.conds]
   [collet.arrow :as collet.arrow]
   [collet.utils :as utils]
   [collet.select :as collet.select]))


(defn do-join
  [dataset {:keys [with cat? source target]}]
  (let [source-key     (if (or (keyword? source) (string? source))
                         source
                         :_collet_join_source)
        left-ds        (if (= source-key :_collet_join_source)
                         (ds/row-map dataset
                                     #(hash-map source-key (collet.select/select source %)))
                         dataset)
        target-key     (if (or (keyword? target) (string? target))
                         target
                         :_collet_join_target)
        join-ds        (utils/make-dataset with {:cat? cat?})
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


(defn zip-columns
  "Zip grouped dataset into a single row"
  [options dataset]
  (->> (ds/columns dataset)
       (map (partial prep-column options))
       (zipmap (ds/column-names dataset))))


(defn do-fold-by
  "Fold dataset by the provided columns"
  [dataset {:keys [by rollup rollup-except keep-columns]
            :or   {rollup false rollup-except false}}]
  (let [rollup'           (cond
                            (true? rollup) :all
                            (vector? rollup) (set rollup)
                            :otherwise :none)
        multiple-columns? (sequential? by)
        target-columns    (if multiple-columns?
                            (set by)
                            #{by})]
    (if (utils/ds-seq? dataset)
      (let [arrow-columns  (-> dataset meta :arrow-columns)
            finalizer-opts {:rollup         rollup'
                            :rollup-except  rollup-except
                            :target-columns target-columns
                            :arrow-columns  arrow-columns}
            agg-columns    (into {}
                                 (map (fn [[k v]]
                                        (let [finalizer #(prep-column (assoc finalizer-opts :column-name k) %)]
                                          ;; TODO add more reducer options
                                          [k (ds.reduce/distinct k finalizer)])))
                                 keep-columns)]
        (ds.reduce/group-by-column-agg by agg-columns dataset))

      (let [zip-options {:rollup         rollup'
                         :rollup-except  rollup-except
                         :target-columns target-columns}
            options     {:group-by-finalizer (partial zip-columns zip-options)}
            groups      (if multiple-columns?
                          (ds/group-by dataset #(select-keys % by) options)
                          (ds/group-by-column dataset by options))]
        (-> groups vals ds/->dataset)))))


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


(defn do-group-by
  [dataset {:keys [by join-groups group-col]
            :or   {join-groups true group-col :_group_by_key}}]
  (let [groups (if (sequential? by)
                 (ds/group-by dataset #(collet.select/select by %))
                 (ds/group-by-column dataset by))]
    (if join-groups
      (let [groups-seq (map (fn [[k d]] (assoc d group-col k)) groups)]
        (if (< 3 (count groups))
          (apply ds/concat-inplace groups-seq)
          (apply ds/concat-copying groups-seq)))
      groups)))


(defn do-filter
  [dataset {:keys [by predicate]}]
  (if (sequential? by)
    (let [condition-fn (collet.conds/compile-conditions by)]
      (ds/filter dataset condition-fn))
    (ds/filter-column dataset by predicate)))


(defn do-order-by
  [dataset {:keys [by comp]}]
  (if (sequential? by)
    (ds/sort-by dataset #(collet.select/select by %) comp)
    (ds/sort-by-column dataset by comp)))


(defn do-select
  [dataset {:keys [columns rows drop-cols drop-rows]}]
  (let [selecter (fn [dataset]
                   (cond-> dataset
                     (some? columns) (ds/select-columns columns)
                     (some? drop-cols) (ds/drop-columns drop-cols)
                     (some? rows) (ds/select-rows columns rows)
                     (some? drop-rows) (ds/drop-rows drop-rows)))]
    (if (utils/ds-seq? dataset)
      (map selecter dataset)
      (selecter dataset))))


(defn do-map-with
  [dataset {:keys [with as-dataset?] :or {as-dataset? false}}]
  (let [map-fn (if (list? with) (eval with) with)]
    (cond
      (utils/ds-seq? dataset) (map #(ds/row-map % map-fn) dataset)
      (ds/dataset? dataset) (ds/row-map dataset map-fn)
      :otherwise (cond-> (map map-fn dataset)
                   as-dataset? (ds/->dataset)))))


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
                  [:or utils/linked-hash-map? utils/dataset?]]}
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
