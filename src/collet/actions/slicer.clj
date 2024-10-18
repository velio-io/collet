(ns collet.actions.slicer
  (:require
   [collet.utils :as utils]
   [collet.select :as collet.select]
   [tech.v3.dataset :as ds]
   [tech.v3.dataset.column :as ds.col]
   [tech.v3.dataset.join :as ds.join]
   [tech.v3.datatype :as dtype]
   [tech.v3.datatype.bitmap :as dtype.bitmap]
   [tech.v3.datatype.protocols :as dtype.proto]))


(defn do-join
  [dataset {:keys [sequence cat? source target]}]
  (let [source-key     (if (or (keyword? source) (string? source))
                         source
                         :_collet_join_source)
        left-ds        (if (= source-key :_collet_join_source)
                         (ds/row-map dataset
                                     #(hash-map source-key (-> (collet.select/select source %) :value)))
                         dataset)
        target-key     (if (or (keyword? target) (string? target))
                         target
                         :_collet_join_target)
        join-ds        (utils/make-dataset sequence {:cat? cat?})
        right-ds       (if (= target-key :_collet_join_target)
                         (ds/row-map join-ds
                                     #(hash-map target-key (-> (collet.select/select target %) :value)))
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
  [{:keys [rollup rollup-except target-columns]} column]
  (let [column-name (ds.col/column-name column)
        length      (dtype/ecount column)
        missing     (ds.col/missing column)
        column'     (if (and (pos? length) (seq missing))
                      (let [not-missing (-> (range length)
                                            (dtype.bitmap/->bitmap)
                                            (dtype.proto/set-and-not missing))]
                        (ds.col/select column not-missing))
                      ;; return the original column if there are no missing values
                      column)
        unique      (ds.col/unique column')]
    (if (or (contains? target-columns column-name)
            (and (= (count unique) 1)
                 (or (= rollup :all)
                     (and (set? rollup)
                          (if rollup-except
                            (not (contains? rollup column-name))
                            (contains? rollup column-name))))))
      (first unique)
      (vec column'))))


(defn zip-columns
  "Zip grouped dataset into a single row"
  [options dataset]
  (->> (ds/columns dataset)
       (map (partial prep-column options))
       (zipmap (ds/column-names dataset))))


(defn do-fold-by
  "Fold dataset by the provided columns"
  [dataset {:keys [columns rollup rollup-except] :or {rollup false rollup-except false}}]
  (let [multiple-columns? (sequential? columns)
        fold-by-selector  (if multiple-columns?
                            (fn [row]
                              (select-keys row columns))
                            columns)
        rollup'           (cond
                            (true? rollup) :all
                            (vector? rollup) (set rollup)
                            :otherwise :none)
        options           {:rollup         rollup'
                           :rollup-except  rollup-except
                           :target-columns (if multiple-columns?
                                             (set columns)
                                             #{columns})}]
    (-> dataset
        (ds/group-by fold-by-selector
                     {:group-by-finalizer (partial zip-columns options)})
        vals
        ds/->dataset)))


(def slicer-params-spec
  [:map
   [:sequence [:or utils/dataset? [:sequential map?]]]
   [:cat? {:optional true} :boolean]
   [:flatten-by {:optional true} [:map-of :keyword collet.select/select-path]]
   [:group-by {:optional true} collet.select/select-path]
   [:join-with {:optional true}
    [:map
     [:sequence [:or utils/dataset? [:sequential map?]]]
     [:source [:or :string :keyword collet.select/select-path]]
     [:target [:or :string :keyword collet.select/select-path]]
     [:cat? {:optional true} :boolean]]]
   [:fold-by {:optional true}
    [:map
     [:columns [:or :keyword [:sequential :keyword]]]
     [:rollup {:optional true} [:or :boolean [:sequential :keyword]]]
     [:rollup-except {:optional true} :boolean]]]])


(defn prep-dataset
  "Creates a dataset from the provided sequence.
   Can modify the dataset shape by applying flatten-by, group-by and join-with options."
  {:malli/schema [:=> [:cat slicer-params-spec]
                  [:or utils/linked-hash-map? utils/dataset?]]}
  [{:keys [flatten-by group-by fold-by join-with cat?] data :sequence}]
  (let [dataset (utils/make-dataset data {:cat? cat?})
        [f-key f-path] (first flatten-by)]
    (cond-> dataset
      (some? flatten-by)
      (ds/row-mapcat
       (fn [row]
         (let [value (-> (collet.select/select f-path row) :value)]
           (cond
             (and (sequential? value) (not-empty value))
             (map #(hash-map f-key %) value)

             (not-empty value)
             [{f-key value}]

             :otherwise
             [{f-key nil}]))))

      (some? join-with)
      (do-join join-with)

      (some? fold-by)
      (do-fold-by fold-by)

      (some? group-by)
      (ds/group-by
       (fn [row]
         (-> (collet.select/select group-by row) :value))))))


(def slicer-action
  {:action prep-dataset})
