(ns collet.store.datalevin-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [collet.store :as store]
   [collet.store.datalevin :as datalevin]
   [datalevin.core :as d])
  (:import
    [java.nio.file FileVisitOption Files Path]
    [java.nio.file.attribute FileAttribute]))


(defn- temporary-store-path
  ^Path
  []
  (Files/createTempDirectory
   "collet-store-"
   (make-array FileAttribute 0)))


(defn- delete-store!
  [^Path path]
  (when (Files/exists path (make-array java.nio.file.LinkOption 0))
    (with-open [paths (Files/walk path (make-array FileVisitOption 0))]
      (doseq [entry (sort-by str
                             #(compare %2 %1)
                             (iterator-seq (.iterator paths)))]
        (Files/deleteIfExists ^Path entry)))))


(defmacro with-store
  [[binding] & body]
  `(let [path#    (temporary-store-path)
         ~binding (datalevin/store {:dir (str path#)})]
     (try
       ~@body
       (finally
        (store/close! ~binding)
        (delete-store! path#)))))


(def pipeline-v1
  {:name            :orders
   :version         1
   :use-arrow       true
   :max-parallelism 10
   :tasks           [{:name    :fetch
                      :actions [{:name :fetch
                                 :type :custom
                                 :fn   'clojure.core/identity}]}
                     {:name    :load
                      :inputs  [:fetch]
                      :actions [{:name :load
                                 :type :custom
                                 :fn   'clojure.core/identity}]}]})


(def pipeline-with-edn-values
  (-> pipeline-v1
      (assoc-in [:tasks 0 :actions 0 :fn]
                '(fn [value] (identity value)))
      (assoc-in [:tasks 0 :actions 0 :selectors]
                {'value [:config :value]})
      (assoc-in [:tasks 0 :actions 0 :params]
                {:missing nil
                 :list-marker
                 {:collet.store.datalevin/list [:a :b]}
                 :entries-marker
                 {:collet.store.datalevin/entries [[:a 1]]}
                 :nested-marker
                 [{:collet.store.datalevin/list [:nested]}]})))


(deftest store-owns-one-process-lifetime-connection
  (let [path (temporary-store-path)
        db   (datalevin/store {:dir (str path)})]
    (try
      (is (d/conn? (:conn db)))
      (is (not (d/closed? (:conn db))))
      (is (nil? (store/close! db)))
      (is (nil? (store/close! db)))
      (is (d/closed? (:conn db)))
      (finally
       (store/close! db)
       (delete-store! path)))))


(deftest store-schema-declares-the-complete-durable-domain
  (with-store [db]
              (is
               (= #{:pipeline/name
                    :pipeline/version
                    :pipeline/key
                    :pipeline/plan
                    :pipeline/created-at
                    :run/id
                    :run/pipeline
                    :run/status
                    :run/created-at
                    :run/started-at
                    :run/finished-at
                    :run/error
                    :task/id
                    :task/run
                    :task/name
                    :task/key
                    :task/inputs
                    :task/output
                    :task/status
                    :task/outcome
                    :task/created-at
                    :task/started-at
                    :task/finished-at
                    :task/error
                    :artifact/id
                    :artifact/run
                    :artifact/task
                    :artifact/kind
                    :artifact/format
                    :artifact/version
                    :artifact/checksum
                    :artifact/bytes
                    :artifact/records
                    :artifact/schema
                    :artifact/created-at}
                  (->> (d/schema (:conn db))
                       keys
                       (remove #(= "db" (namespace %)))
                       set)))
              (is (= {:db/valueType         :db.type/idoc
                      :db/domain            "pipeline_plans"
                      :db.idoc/indexedPaths [:name
                                             :version
                                             [:tasks :name]
                                             [:tasks :inputs]]}
                     (select-keys
                      (get (d/schema (:conn db)) :pipeline/plan)
                      [:db/valueType :db/domain :db.idoc/indexedPaths])))
              (is (thrown? Exception
                           (d/transact! (:conn db)
                             [{:run/id       (random-uuid)
                               :run/unknown? true}])))
              (is (thrown? Exception
                           (d/transact! (:conn db)
                             [{:run/id     (random-uuid)
                               :run/status "done"}])))))


(deftest pipeline-plan-is-a-round-trippable-idoc
  (with-store
   [db]
   (store/save-pipeline! db pipeline-with-edn-values)
   (let [loaded (store/load-pipeline db :orders 1)]
     (is (= pipeline-with-edn-values loaded))
     (is (= {:collet.store.datalevin/list [:a :b]}
            (get-in loaded [:tasks 0 :actions 0 :params :list-marker])))
     (is (= {:collet.store.datalevin/entries [[:a 1]]}
            (get-in loaded [:tasks 0 :actions 0 :params :entries-marker])))
     (is (= [{:collet.store.datalevin/list [:nested]}]
            (get-in loaded [:tasks 0 :actions 0 :params :nested-marker]))))
   (is (= 1
          (d/q '[:find (count ?pipeline) .
                 :where
                 [(idoc-match $ :pipeline/plan {:tasks {:name :fetch}})
                  [[?pipeline]]]]
               (d/db (:conn db)))))
   (is
    (nil?
     (d/q '[:find (count ?pipeline) .
            :where
            [(idoc-match
              $
              :pipeline/plan
              {:tasks {:actions {:params {:missing (nil?)}}}})
             [[?pipeline]]]]
          (d/db (:conn db)))))))


(deftest literal-json-null-is-not-supported
  (with-store [db]
              (is (thrown?
                   clojure.lang.ExceptionInfo
                   (store/save-pipeline!
                    db
                    (assoc-in pipeline-v1
                     [:tasks 0 :actions 0 :params]
                     {:value :json/null}))))))


(deftest pipeline-revisions-are-immutable
  (with-store [db]
              (testing "an equal revision is idempotent"
                (is (= pipeline-v1 (store/save-pipeline! db pipeline-v1)))
                (is (= pipeline-v1 (store/save-pipeline! db pipeline-v1))))

              (testing "a conflicting plan requires a version bump"
                (let [error (try
                              (store/save-pipeline!
                               db
                               (assoc pipeline-v1 :max-parallelism 2))
                              nil
                              (catch clojure.lang.ExceptionInfo error
                                error))]
                  (is (= :collet.error/pipeline-revision-conflict
                         (:collet.error/type (ex-data error))))))

              (testing "versions coexist and latest/exact loading works"
                (let [pipeline-v2 (assoc pipeline-v1
                                    :version 2
                                    :max-parallelism 2)]
                  (store/save-pipeline! db pipeline-v2)
                  (is (= pipeline-v1 (store/load-pipeline db :orders 1)))
                  (is (= pipeline-v2 (store/load-pipeline db :orders 2)))
                  (is (= pipeline-v2 (store/load-pipeline db :orders)))
                  (is (nil? (store/load-pipeline db :missing)))))))


(deftest pipeline-runs-and-task-references-survive-reopen
  (let [path       (temporary-store-path)
        run-id     (random-uuid)
        fetch-id   (random-uuid)
        load-id    (random-uuid)
        created-at (System/currentTimeMillis)
        run        {:run/id         run-id
                    :run/pipeline   {:pipeline/name    :orders
                                     :pipeline/version 1}
                    :run/status     :running
                    :run/created-at created-at
                    :run/started-at created-at}
        task-runs  [{:task/id         fetch-id
                     :task/run        run-id
                     :task/name       :fetch
                     :task/status     :waiting
                     :task/inputs     []
                     :task/created-at created-at}
                    {:task/id         load-id
                     :task/run        run-id
                     :task/name       :load
                     :task/status     :waiting
                     :task/inputs     [fetch-id]
                     :task/created-at created-at}]
        db         (datalevin/store {:dir (str path)})]
    (try
      (store/save-pipeline! db pipeline-v1)
      (is (= run (store/create-run! db run task-runs)))
      (store/update-run! db run-id {:run/status :paused})
      (store/update-task! db
                          fetch-id
                          {:task/status      :completed
                           :task/started-at  created-at
                           :task/finished-at created-at})
      (store/close! db)

      (let [reopened (datalevin/store {:dir (str path)})
            tasks    (store/get-task-runs reopened run-id)
            by-name  (into {} (map (juxt :task/name identity)) tasks)]
        (try
          (is (= pipeline-v1 (store/load-pipeline reopened :orders 1)))
          (is (= (assoc run :run/status :paused)
                 (store/get-run reopened run-id)))
          (is (= (set [(assoc (first task-runs)
                         :task/status :completed
                         :task/started-at created-at
                         :task/finished-at created-at)
                       (second task-runs)])
                 (set tasks)))
          (is (= :completed (get-in by-name [:fetch :task/status])))
          (is (= [fetch-id] (get-in by-name [:load :task/inputs])))
          (is (= run-id (get-in by-name [:load :task/run])))
          (finally
           (store/close! reopened))))
      (finally
       (store/close! db)
       (delete-store! path)))))


(deftest task-completion-registers-direct-artifacts-and-derived-lineage
  (with-store
   [db]
   (let [run-id (random-uuid)
         source-id (random-uuid)
         target-id (random-uuid)
         artifact-id (random-uuid)
         now (System/currentTimeMillis)
         run {:run/id         run-id
              :run/pipeline   {:pipeline/name :orders :pipeline/version 1}
              :run/status     :running
              :run/created-at now
              :run/started-at now}
         task-runs [{:task/id         source-id
                     :task/run        run-id
                     :task/name       :source
                     :task/status     :waiting
                     :task/inputs     []
                     :task/created-at now}
                    {:task/id         target-id
                     :task/run        run-id
                     :task/name       :target
                     :task/status     :waiting
                     :task/inputs     [source-id]
                     :task/created-at now}]
         source-artifact
         {:artifact/id         (random-uuid)
          :artifact/run-id     run-id
          :artifact/task-id    source-id
          :artifact/kind       :scalar
          :artifact/format     :edn
          :artifact/version    1
          :artifact/checksum   "sha256:source"
          :artifact/bytes      1
          :artifact/created-at now}
         source-output
         {:output/kind :scalar
          :output/ref  [:artifact/id (:artifact/id source-artifact)]}
         artifact
         {:artifact/id         artifact-id
          :artifact/run-id     run-id
          :artifact/task-id    target-id
          :artifact/kind       :dataset
          :artifact/format     :parquet
          :artifact/version    1
          :artifact/checksum   "sha256:target"
          :artifact/bytes      2
          :artifact/records    2
          :artifact/schema     {:version 1
                                :fields  [{:key :id :name "id" :type :int64}]}
          :artifact/created-at now}
         output
         {:output/kind :dataset
          :output/ref  [:artifact/id artifact-id]}]
     (store/save-pipeline! db pipeline-v1)
     (store/create-run! db run task-runs)
     (store/complete-task! db
                           source-id
                           {:task/status      :completed
                            :task/outcome     :computed
                            :task/finished-at now
                            :artifact         source-artifact
                            :output           source-output})
     (store/complete-task! db
                           target-id
                           {:task/status      :completed
                            :task/outcome     :computed
                            :task/finished-at now
                            :artifact         artifact
                            :output           output})
     (is (= output (store/get-task-output db target-id)))
     (is (= :computed
            (:task/outcome
             (some #(when (= target-id (:task/id %)) %)
                   (store/get-task-runs db run-id)))))
     (is (= artifact (store/get-artifact db artifact-id)))
     (is (= [source-id]
            (mapv :task/id (store/get-lineage db target-id :upstream))))
     (is (= [target-id]
            (mapv :task/id (store/get-lineage db source-id :downstream))))

     (let [{:keys [run released-artifacts]}
           (store/finalize-run! db
                                run-id
                                {:run/status :done :run/finished-at now}
                                #{target-id})]
       (is (= :done (:run/status run)))
       (is (= [(:artifact/id source-artifact)]
              (mapv :artifact/id released-artifacts)))
       (is (nil? (store/get-task-output db source-id)))
       (is (nil? (store/get-artifact db (:artifact/id source-artifact))))
       (is (= output (store/get-task-output db target-id))))

     (is (= [artifact-id]
            (mapv :artifact/id
                  (:released-artifacts
                   (store/finalize-run! db run-id {} #{})))))
     (is (nil? (store/get-artifact db artifact-id)))
     (is (empty? (:released-artifacts
                  (store/finalize-run! db run-id {} #{})))))))


(deftest task-runs-use-one-query-with-an-inline-pull
  (with-store [db]
              (let [run-id    (random-uuid)
                    fetch-id  (random-uuid)
                    load-id   (random-uuid)
                    run       {:run/id         run-id
                               :run/pipeline   {:pipeline/name    :orders
                                                :pipeline/version 1}
                               :run/status     :running
                               :run/created-at 1
                               :run/started-at 1}
                    task-runs [{:task/id         fetch-id
                                :task/run        run-id
                                :task/name       :fetch
                                :task/status     :waiting
                                :task/inputs     []
                                :task/created-at 1}
                               {:task/id         load-id
                                :task/run        run-id
                                :task/name       :load
                                :task/status     :waiting
                                :task/inputs     [fetch-id]
                                :task/created-at 1}]
                    query     d/q
                    pull      d/pull
                    queries   (atom 0)
                    pulls     (atom 0)]
                (store/save-pipeline! db pipeline-v1)
                (store/create-run! db run task-runs)
                (let [tasks (with-redefs [d/q    (fn [& args]
                                                   (swap! queries inc)
                                                   (apply query args))
                                          d/pull (fn [& args]
                                                   (swap! pulls inc)
                                                   (apply pull args))]
                              (store/get-task-runs db run-id))]
                  (is (= #{fetch-id load-id} (set (map :task/id tasks))))
                  (is (= 1 @queries))
                  (is (zero? @pulls))))))
