(ns collet.store.datalevin-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [collet.core :as collet]
   [collet.store :as store]
   [collet.store.datalevin :as datalevin]
   [datalevin.core :as d])
  (:import
   [clojure.lang ExceptionInfo]
   [java.nio.file FileVisitOption Files LinkOption Path]
   [java.nio.file.attribute FileAttribute]))


(defn- temporary-store-path
  ^Path
  []
  (Files/createTempDirectory
   "collet-store-"
   (make-array FileAttribute 0)))


(defn- delete-store!
  [^Path path]
  (when (Files/exists path (make-array LinkOption 0))
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


(deftest function-forms-and-symbols-survive-storage-and-runtime-recreation
  (let [path (temporary-store-path)
        ctx  (collet/context {:data-dir (str path)})
        spec {:name  :durable-code
              :tasks [{:name       :inline
                       :keep-state true
                       :actions    [{:name :increment :type :custom :fn '(fn [value] (inc value)) :params [41]}]}
                      {:name       :named
                       :keep-state true
                       :actions    [{:name :increment :type :custom :fn #'inc :params [41]}]}]}
        plan (collet/compile-pipeline spec)]
    (try
      (let [saved (store/save-pipeline! (:store ctx) {} plan)]
        (collet/close ctx)
        (let [reopened (collet/context {:data-dir (str path)})]
          (try
            (let [loaded (collet/load-pipeline reopened :durable-code (:version saved))
                  run    @(collet/start reopened loaded {})]
              (is (list? (get-in loaded [:tasks 0 :actions 0 :fn])))
              (is (= 'clojure.core/inc (get-in loaded [:tasks 1 :actions 0 :fn])))
              (is (= :done (:run/status run)))
              (is (= [42 42]
                     (mapv #(collet/read-output reopened (:task/output %))
                           (store/get-task-runs (:store reopened) (:run/id run))))))
            (finally (collet/close reopened)))))
      (is (= {:collet.error/type :collet.error/non-durable-value :path [:tasks 0 :actions 0 :fn]}
             (try (collet/compile-pipeline (assoc-in spec [:tasks 0 :actions 0 :fn] (fn [value] (inc value))))
                  nil
                  (catch ExceptionInfo e (select-keys (ex-data e) [:collet.error/type :path])))))
      (finally (collet/close ctx) (delete-store! path)))))


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
     (= #{:draft/id :draft/revision :draft/source :draft/title :draft/created-at :draft/updated-at :draft/origin
          :draft.edit/draft :draft.edit/revision :draft.edit/key :draft.edit/source :draft.edit/author :draft.edit/summary :draft.edit/description :draft.edit/at
          :pipeline/name
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
          :task/retry-count
          :task/output-records
          :task/output-bytes
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
          :artifact/parts :artifact/max-row-bytes :artifact/records
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
  (with-store [db]
    (store/save-pipeline! db {} pipeline-with-edn-values)
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
    (doseq [params [{:value :json/null}
                    {:json/null :key}
                    {:values [{:nested #{:json/null}}]}]]
      (is (thrown-with-msg?
           ExceptionInfo
           #"reserved for IDoc"
           (store/save-pipeline! db {} (assoc-in pipeline-v1 [:tasks 0 :actions 0 :params] params)))))
    (is (nil? (store/load-pipeline db :orders)))))


(deftest pipeline-revision-save-modes
  (with-store [db]
    (let [unversioned-v1  (dissoc pipeline-v1 :version)
          pipeline-v2     (assoc pipeline-v1
                            :version 2
                            :max-parallelism 2)
          pipeline-v3     (assoc pipeline-v2
                            :version 3
                            :max-parallelism 3)
          pipeline-v4     (assoc pipeline-v3
                            :version 4
                            :max-parallelism 4)
          dev-pipeline-v1 (assoc pipeline-v1 :max-parallelism 5)
          dev-pipeline-v4 (assoc pipeline-v4 :max-parallelism 6)]
      (testing "normal mode assigns the first omitted version"
        (is (= pipeline-v1
               (store/save-pipeline! db {} unversioned-v1))))

      (testing "equal plans are idempotent with explicit, omitted, and nil versions"
        (let [before (:max-tx (d/db (:conn db)))]
          (is (= pipeline-v1 (store/save-pipeline! db {} pipeline-v1)))
          (is (= pipeline-v1 (store/save-pipeline! db {} unversioned-v1)))
          (is (= pipeline-v1 (store/save-pipeline! db {} (assoc pipeline-v1 :version nil))))
          (is (= pipeline-v1 (store/save-pipeline! db {:dev? true} (assoc pipeline-v1 :version nil))))
          (is (= before (:max-tx (d/db (:conn db)))))))

      (testing "a changed explicit latest revision allocates the next revision"
        (is (= pipeline-v2
               (store/save-pipeline!
                db
                {}
                (assoc pipeline-v1 :max-parallelism 2))))
        (is (= pipeline-v1 (store/load-pipeline db :orders 1)))
        (is (= pipeline-v2 (store/load-pipeline db :orders 2)))
        (is (= pipeline-v2 (store/load-pipeline db :orders))))

      (testing "a changed plan with a nil version allocates the next revision"
        (is (= pipeline-v3
               (store/save-pipeline! db {} (assoc pipeline-v3 :version nil))))
        (is (= pipeline-v3 (store/load-pipeline db :orders 3)))
        (is (= pipeline-v3 (store/load-pipeline db :orders))))

      (testing "an explicit next revision is accepted"
        (is (= pipeline-v4 (store/save-pipeline! db {} pipeline-v4)))
        (is (= pipeline-v4 (store/load-pipeline db :orders 4)))
        (is (= pipeline-v4 (store/load-pipeline db :orders))))

      (testing "dev mode replaces an exact revision without changing latest"
        (is (= dev-pipeline-v1
               (store/save-pipeline! db {:dev? true} dev-pipeline-v1)))
        (is (= dev-pipeline-v1 (store/load-pipeline db :orders 1)))
        (is (= pipeline-v2 (store/load-pipeline db :orders 2)))
        (is (= pipeline-v4 (store/load-pipeline db :orders 4)))
        (is (= pipeline-v4 (store/load-pipeline db :orders)))
        (is (= 1
               (d/q '[:find (count ?pipeline) .
                      :in $ ?name ?version
                      :where
                      [?pipeline :pipeline/name ?name]
                      [?pipeline :pipeline/version ?version]]
                    (d/db (:conn db))
                    :orders
                    1))))

      (testing "dev mode uses a nil version to replace the latest revision"
        (is (= dev-pipeline-v4
               (store/save-pipeline!
                db
                {:dev? true}
                (assoc dev-pipeline-v4 :version nil))))
        (is (= dev-pipeline-v1 (store/load-pipeline db :orders 1)))
        (is (= dev-pipeline-v4 (store/load-pipeline db :orders 4)))
        (is (= dev-pipeline-v4 (store/load-pipeline db :orders))))

      (testing "normal mode rejects changed stale explicit revisions"
        (let [error (try
                      (store/save-pipeline!
                       db
                       {}
                       (assoc pipeline-v1 :max-parallelism 7))
                      nil
                      (catch ExceptionInfo error
                        error))]
          (is (= :collet.error/pipeline-revision-conflict
                 (:collet.error/type (ex-data error))))
          (is (= dev-pipeline-v1 (store/load-pipeline db :orders 1)))
          (is (= dev-pipeline-v4 (store/load-pipeline db :orders)))
          (is (nil? (store/load-pipeline db :missing))))))))


(deftest nil-plan-keys-and-values-round-trip
  (with-store [db]
    (let [plan   (assoc pipeline-v1 :data {nil :key :nullable nil})
          saved  (store/save-pipeline! db {} plan)
          before (:max-tx (d/db (:conn db)))]
      (is (= plan saved (store/load-pipeline db :orders)))
      (is (= saved (store/save-pipeline! db {:dev? true} plan)))
      (is (= before (:max-tx (d/db (:conn db))))))))


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
      (store/save-pipeline! db {} pipeline-v1)
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


(deftest lifecycle-writes-return-their-own-committed-values
  (with-store [db]
    (let [run-id        (random-uuid)
          task-id       (random-uuid)
          input-id      (random-uuid)
          artifact-id   (random-uuid)
          pipeline      {:pipeline/name :orders :pipeline/version 1}
          run           {:run/id run-id :run/pipeline pipeline :run/status :running :run/created-at 1 :run/started-at 2}
          task          {:task/id         task-id
                         :task/run        run-id
                         :task/name       :load
                         :task/inputs     [input-id]
                         :task/status     :waiting
                         :task/created-at 2}
          transact!     d/transact!
          commit-around (fn [write earlier-tx later-tx]
                          (let [attempts (atom 0)
                                result   (with-redefs [d/transact! (fn [conn tx]
                                                                     (when (and (= 1 (swap! attempts inc)) earlier-tx)
                                                                       (transact! conn earlier-tx))
                                                                     (let [report (transact! conn tx)]
                                                                       (transact! conn later-tx)
                                                                       report))]
                                           (write))]
                            [result @attempts]))]
      (store/save-pipeline! db {} pipeline-v1)
      (store/create-run! db run [{:task/id input-id :task/name :fetch :task/status :waiting :task/created-at 2} task])

      (let [[updated attempts] (commit-around #(store/update-run! db run-id {:run/status :paused :run/started-at nil})
                                              [{:db/id [:task/id input-id] :task/status :completed}]
                                              [{:db/id [:run/id run-id] :run/status :stopped :run/started-at 3}])]
        (is (= 1 attempts))
        (is (= (assoc (dissoc run :run/started-at) :run/status :paused) updated))
        (is (= :stopped (:run/status (store/get-run db run-id)))))

      (let [[updated attempts] (commit-around #(store/update-task! db task-id {:task/status :running :task/error {:details nil}})
                                              [{:db/id [:task/id task-id] :task/retry-count 7}]
                                              [{:db/id [:task/id task-id] :task/status :interrupted :task/error {:details "later"}}])]
        (is (= 2 attempts))
        (is (= (assoc task
                 :task/status :running
                 :task/retry-count 7
                 :task/error {:details :json/null})
               updated))
        (is (= :interrupted (:task/status (some #(when (= task-id (:task/id %)) %) (store/get-task-runs db run-id))))))

      (let [[completed] (commit-around #(store/complete-task! db
                                                              task-id
                                                              {:task/status :completed
                                                               :artifact    {:artifact/id     artifact-id
                                                                             :artifact/run-id run-id
                                                                             :artifact/kind   :scalar}
                                                               :output      {:output/ref (list :artifact/id artifact-id)}})
                                       nil
                                       [[:db.fn/retractEntity [:artifact/id artifact-id]]])]
        (is (= (assoc task
                 :task/status :completed
                 :task/retry-count 7
                 :task/error {:details "later"}
                 :task/output [:artifact/id artifact-id])
               completed))
        (is (nil? (store/get-task-output db task-id))))

      (let [run-before   (store/get-run db run-id)
            tasks-before (store/get-task-runs db run-id)]
        (is (= {:lookup-ref [:run/id run-id] :unsupported-fields [:run/pipeline]}
               (try (store/update-run! db run-id {:run/status :paused :run/pipeline nil})
                    (catch ExceptionInfo e (ex-data e)))))
        (is (= {:lookup-ref [:task/id task-id] :unsupported-fields [:task/inputs]}
               (try (store/update-task! db task-id {:task/status :running :task/inputs nil})
                    (catch ExceptionInfo e (ex-data e)))))
        (is (= run-before (store/get-run db run-id)))
        (is (= (set tasks-before) (set (store/get-task-runs db run-id))))))))


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
      (store/save-pipeline! db {} pipeline-v1)
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

      (let [transact! d/transact!
            writes    (atom 0)
            {finalized :run :keys [released-artifacts]}
            (with-redefs [d/transact! (fn [conn tx]
                                        (swap! writes inc)
                                        (let [report (transact! conn tx)]
                                          (transact! conn [{:db/id [:run/id run-id] :run/status :paused}])
                                          report))]
              (store/finalize-run! db
                                   run-id
                                   {:run/status      :done
                                    :run/started-at  nil
                                    :run/finished-at now
                                    :run/error       {:details nil}}
                                   #{target-id}))]
        (is (= 1 @writes))
        (is (= (assoc (dissoc run :run/started-at)
                 :run/status :done
                 :run/finished-at now
                 :run/error {:details :json/null})
               finalized))
        (is (= :paused (:run/status (store/get-run db run-id))))
        (is (= [source-artifact] released-artifacts))
        (is (nil? (store/get-task-output db source-id)))
        (is (nil? (store/get-artifact db (:artifact/id source-artifact))))
        (is (= output (store/get-task-output db target-id))))

      (is (= {:run-id run-id :unsupported-fields [:run/pipeline]}
             (try (store/finalize-run! db run-id {:run/pipeline nil} #{})
                  (catch ExceptionInfo e (ex-data e)))))
      (is (= artifact (store/get-artifact db artifact-id)))
      (is (= [artifact-id]
             (mapv :artifact/id
                   (:released-artifacts
                    (store/finalize-run! db run-id {} #{})))))
      (is (nil? (store/get-artifact db artifact-id)))
      (let [before (:max-tx (d/db (:conn db)))]
        (is (= {:run (store/get-run db run-id) :released-artifacts []}
               (store/finalize-run! db run-id {} #{})))
        (is (= before (:max-tx (d/db (:conn db)))))))))


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
      (store/save-pipeline! db {} pipeline-v1)
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


(deftest task-runs-include-all-inputs
  (with-store [db]
    (let [run-id    (random-uuid)
          target-id (random-uuid)
          inputs    (mapv (fn [index]
                            {:task/id         (random-uuid)
                             :task/name       (keyword (str "input-" index))
                             :task/status     :waiting
                             :task/created-at 1})
                          (range 1001))
          input-ids (mapv :task/id inputs)
          target    {:task/id         target-id
                     :task/name       :target
                     :task/status     :waiting
                     :task/created-at 1
                     :task/inputs     input-ids}
          run       {:run/id         run-id
                     :run/pipeline   {:pipeline/name :orders :pipeline/version 1}
                     :run/status     :running
                     :run/created-at 1
                     :run/started-at 1}]
      (store/save-pipeline! db {} pipeline-v1)
      (store/create-run! db run (conj inputs target))
      (let [stored (some #(when (= target-id (:task/id %)) %) (store/get-task-runs db run-id))]
        (is (= (set input-ids) (set (:task/inputs stored))))))))


(deftest bounded-catalogs-return-saved-revisions-and-runs
  (with-store [db]
    (let [first-plan  (store/save-pipeline! db {} (dissoc pipeline-v1 :version))
          second-plan (store/save-pipeline! db {} (assoc (dissoc pipeline-v1 :version) :max-parallelism 2))
          ids         [(random-uuid) (random-uuid)]]
      (doseq [[id created-at] (map vector ids [20 10])]
        (store/create-run! db
                           {:run/id         id
                            :run/status     :running
                            :run/created-at created-at
                            :run/started-at created-at
                            :run/pipeline   {:pipeline/name :orders :pipeline/version (:version second-plan)}}
                           []))
      (is (= [(:version second-plan) (:version first-plan)] (mapv :pipeline/version (store/list-pipelines db 10))))
      (let [pulled  (atom [])
            track   (fn [id] (swap! pulled conj id) id)
            pattern [(list :run/id :xform track)]]
        (with-redefs-fn {#'datalevin/run-pull-pattern pattern}
          #(is (= [(first ids)] (mapv :run/id (store/list-runs db 1)))))
        (is (= [(first ids)] @pulled)))
      (let [[newest] (store/list-runs db {:limit 1})
            [oldest] (store/list-runs db {:limit 1 :cursor (:cursor newest)})]
        (is (= (reverse ids) (map :run/id [newest oldest])))
        (is (empty? (store/list-runs db {:cursor (:cursor oldest)}))))
      (is (= {:code "invalid-cursor"}
             (try (store/list-runs db {:cursor "broken"}) (catch ExceptionInfo e (ex-data e)))))
      (is (= :orders (get-in (first (store/list-runs db 1)) [:run/pipeline :pipeline/name])))
      (is (= [] (store/list-runs db 0)))
      (is (= [] (store/list-pipelines db -1))))))


(deftest concurrent-draft-creation-keeps-the-winning-draft-and-edit
  (with-store [db]
    (store/create-draft! db {:draft/source "unrelated"})
    (let [id      (random-uuid)
          start   (promise)
          create  (fn [source]
                    (future
                     @start
                     (try
                       (store/create-draft! db
                                            {:draft/id     id
                                             :draft/source source
                                             :draft/title  source
                                             :draft/origin {:parent nil}
                                             :author       source})
                       (catch ExceptionInfo e (:collet.error/type (ex-data e))))))
          writers (mapv create ["first" "second"])]
      (deliver start true)
      (let [results (mapv deref writers)
            draft   (store/get-draft db id)
            source  (:draft/source draft)
            edit    (store/get-draft db id 0)]
        (is (= (frequencies [draft :collet.error/draft-already-exists]) (frequencies results)))
        (is (= 0 (:draft/revision draft)))
        (is (= source (:draft/title draft) (:draft.edit/source edit) (:draft.edit/author edit)))
        (is (= [[0 source]] (mapv (juxt :draft.edit/revision :draft.edit/author) (store/draft-history db id 10))))))))


(deftest durable-drafts-compare-revisions-and-retain-invalid-source
  (let [path (temporary-store-path)
        id   (random-uuid)
        db   (datalevin/store {:dir (str path)})]
    (try
      (is (= 0 (:draft/revision (store/create-draft! db {:draft/id id :draft/source "{:unfinished"}))))
      (is (= {:collet.error/type :collet.error/draft-already-exists}
             (try (store/create-draft! db {:draft/id id :draft/source "another draft"})
                  (catch ExceptionInfo e (ex-data e)))))
      (let [attempt #(try (store/update-draft! db id 0 % "agent" "Concurrent edit")
                          :accepted
                          (catch ExceptionInfo e (:code (ex-data e))))
            a       (future (attempt "{:one"))
            b       (future (attempt "{:two"))]
        (is (= #{:accepted :draft-conflict} #{@a @b})))
      (is (= [1 0] (mapv :draft.edit/revision (store/draft-history db id 100))))
      (is (= "{:unfinished" (:draft.edit/source (store/get-draft db id 0))))
      (is (nil? (store/get-draft db id 2)))
      (is (nil? (store/get-draft db (random-uuid) 0)))
      (is (= 1 (count (store/list-drafts db 100))))
      (store/close! db)
      (let [reopened (datalevin/store {:dir (str path)})]
        (try
          (is (= 1 (:draft/revision (store/get-draft reopened id))))
          (is (= {:code :draft-conflict :current (store/get-draft reopened id)}
                 (try (store/delete-draft! reopened id 0) (catch ExceptionInfo e (ex-data e)))))
          (store/delete-draft! reopened id 1)
          (is (nil? (store/get-draft reopened id)))
          (is (= {:collet.error/type :collet.error/draft-not-found}
                 (try (store/delete-draft! reopened id 1) (catch ExceptionInfo e (ex-data e)))))
          (is (empty? (store/draft-history reopened id 100)))
          (finally (store/close! reopened))))
      (finally (store/close! db) (delete-store! path)))))


(deftest draft-revisions-remain-readable-without-a-tuple
  (let [path   (temporary-store-path)
        id     (random-uuid)
        schema (dissoc @#'datalevin/schema :draft.edit/key)]
    (try
      (let [conn (d/get-conn (str path) schema)]
        (try
          (d/transact! conn
            [{:db/id -1 :draft/id id :draft/revision 0 :draft/source "{}"}
             {:draft.edit/draft -1 :draft.edit/revision 0 :draft.edit/source "{}"}])
          (finally (d/close conn))))
      (let [db (datalevin/store {:dir (str path)})]
        (try
          (is (= {:draft.edit/revision 0 :draft.edit/source "{}"}
                 (store/get-draft db id 0)))
          (finally (store/close! db))))
      (finally (delete-store! path)))))


(deftest finalization-rechecks-the-release-set-before-skipping-a-write
  (with-store [db]
    (let [run-id      (random-uuid)
          run         {:run/id         run-id
                       :run/pipeline   {:pipeline/name :orders :pipeline/version 1}
                       :run/status     :done
                       :run/created-at 1
                       :run/started-at 2}
          artifact-id (random-uuid)
          artifact    {:artifact/id     artifact-id
                       :artifact/kind   :scalar
                       :artifact/run    [:run/id run-id]
                       :artifact/schema {:value nil}}
          transact!   d/transact!
          attempts    (atom 0)]
      (store/save-pipeline! db {} pipeline-v1)
      (store/create-run! db run [])
      (let [result (with-redefs [d/transact! (fn [conn tx]
                                               (when (= 1 (swap! attempts inc))
                                                 (transact! conn [artifact]))
                                               (transact! conn tx))]
                     (store/finalize-run! db run-id {} #{}))]
        (is (= 2 @attempts))
        (is (= {:run                run
                :released-artifacts [{:artifact/id     artifact-id
                                      :artifact/kind   :scalar
                                      :artifact/run-id run-id
                                      :artifact/schema {:value nil}}]}
               result))
        (is (nil? (store/get-artifact db artifact-id)))))))


(deftest draft-edit-checks-revision-before-deciding-whether-to-write
  (with-store [db]
    (let [draft    (store/create-draft! db {:draft/source "original" :draft/title "Title"})
          id       (:draft/id draft)
          metadata {:draft/title "Edited" :draft.edit/description "Description"}
          conn     (:conn db)
          reports  (atom [])
          before   (:max-tx (d/db conn))]
      (d/listen! conn ::edits #(swap! reports conj %))
      (try
        (is (= {} (store/update-draft! db id 0 "original" "ignored" "ignored" metadata)))
        (is (= draft (store/get-draft db id)))
        (is (= before (:max-tx (d/db conn))))
        (is (empty? @reports))
        (let [changes (store/update-draft! db
                                           id
                                           0
                                           "changed" "agent"
                                           "Changed source"
                                           (assoc metadata :expected-draft draft))
              edited  (merge draft changes)]
          (is (= #{:draft/revision :draft/source :draft/title}
                 (disj (set (keys changes)) :draft/updated-at)))
          (is (= edited (store/get-draft db id)))
          (is (= [1 "changed" "Edited"] ((juxt :draft/revision :draft/source :draft/title) edited)))
          (is (= {:code :draft-conflict :current edited}
                 (try (store/update-draft! db id 0 "changed" nil nil)
                      (catch ExceptionInfo e (ex-data e)))))
          (is (= {:collet.error/type :collet.error/draft-not-found}
                 (try (store/update-draft! db (random-uuid) 0 "changed" nil nil)
                      (catch ExceptionInfo e (ex-data e)))))
          (is (= ["changed" "agent" "Changed source" "Description" (:draft/updated-at edited)]
                 ((juxt :draft.edit/source :draft.edit/author :draft.edit/summary :draft.edit/description :draft.edit/at)
                  (store/get-draft db id 1))))
          (is (= [1 0] (mapv :draft.edit/revision (store/draft-history db id 10))))
          (is (= 1 (count @reports))))
        (finally (d/unlisten! conn ::edits))))))


(deftest draft-checks-the-snapshot-used-to-compose-the-edit
  (with-store [db]
    (let [draft (store/create-draft! db {:draft/source "original" :draft/title "Original"})
          id    (:draft/id draft)]
      (store/delete-draft! db id 0)
      (let [replacement (store/create-draft! db {:draft/id id :draft/source "replacement" :draft/title "Replacement"})]
        (is (= {:code :draft-conflict :current replacement}
               (try (store/update-draft! db id 0 "edited" nil nil {:expected-draft draft})
                    (catch ExceptionInfo e (ex-data e)))))
        (is (= replacement (store/get-draft db id)))
        (is (= [0] (mapv :draft.edit/revision (store/draft-history db id 10))))))))


(deftest run-creation-returns-its-own-write-and-rejects-duplicates
  (with-store [db]
    (let [id        (random-uuid)
          run       {:run/id         id
                     :run/status     :running
                     :run/created-at 1
                     :run/started-at 2
                     :run/pipeline   {:pipeline/name :orders :pipeline/version 1}}
          transact! d/transact!]
      (store/save-pipeline! db {} pipeline-v1)
      (let [created (with-redefs [d/transact! (fn [conn tx]
                                                (let [report (transact! conn tx)]
                                                  (transact! conn [{:db/id [:run/id id] :run/status :paused}])
                                                  report))]
                      (store/create-run! db run []))]
        (is (= run created))
        (is (= (assoc run :run/status :paused) (store/get-run db id))))
      (is (= {:collet.error/type :collet.error/run-already-exists :run-id id}
             (try (store/create-run! db
                                     run
                                     [{:task/id         (random-uuid)
                                       :task/name       :fetch
                                       :task/status     :waiting
                                       :task/created-at 3}])
                  (catch ExceptionInfo e (ex-data e)))))
      (is (= :paused (:run/status (store/get-run db id))))
      (is (empty? (store/get-task-runs db id))))))


(deftest deleted-definitions-do-not-reuse-version-identities
  (with-store [db]
    (let [v1    (store/save-pipeline! db {} pipeline-v1)
          _ (store/save-pipeline! db
                                  {}
                                  (assoc pipeline-v1
                                    :version 2
                                    :max-parallelism 2))
          draft (store/create-draft! db {:draft/source "; untouched raw source\n{:name :orders}"})]
      (is (= [2] (:versions (store/delete-pipeline! db :orders {:version 2}))))
      (is (= v1 (store/load-pipeline db :orders)))
      (is (nil? (store/load-pipeline db :orders 2)))
      (is (= [1] (mapv :pipeline/version (store/list-pipelines db 100))))
      (is (= 3 (:version (store/save-pipeline! db {} (assoc v1 :max-parallelism 3)))))
      (is (= "pipeline-changed" (try (store/delete-pipeline! db :orders {:latest-version 1}) nil (catch Exception e (:code (ex-data e))))))
      (let [run-ids (mapv (fn [version]
                            (let [id (random-uuid)]
                              (store/create-run! db
                                                 {:run/id         id
                                                  :run/status     :done
                                                  :run/created-at 1
                                                  :run/started-at 1
                                                  :run/pipeline   {:pipeline/name :orders :pipeline/version version}}
                                                 [])
                              id))
                          [1 3])
            deleted (store/delete-pipeline! db :orders {:latest-version 3})]
        (is (= {:deleted true :name :orders :versions [1 3]} (dissoc deleted :run-ids)))
        (is (= (set run-ids) (set (:run-ids deleted)))))
      (is (nil? (store/load-pipeline db :orders)))
      (is (empty? (store/list-pipelines db 100)))
      (is (= draft (store/get-draft db (:draft/id draft))))
      (is (thrown? clojure.lang.ExceptionInfo (store/save-pipeline! db {} pipeline-v1)))
      (is (= 4 (:version (store/save-pipeline! db {} (dissoc pipeline-v1 :version)))))
      (is (= "pipeline-not-found" (try (store/create-run! db {:run/id (random-uuid) :run/pipeline {:pipeline/name :orders :pipeline/version 1} :run/status :running} []) nil (catch Exception e (:code (ex-data e)))))))))


(deftest deleting-one-revision-checks-and-retracts-all-its-dependants
  (with-store [db]
    (let [run-id      (random-uuid)
          artifact-id (random-uuid)
          tasks       (mapv (fn [i]
                              {:task/id         (random-uuid)
                               :task/name       (keyword (str "task-" i))
                               :task/status     :completed
                               :task/created-at 1})
                            (range 1001))
          last-id     (:task/id (peek tasks))
          query       d/q
          queries     (atom 0)]
      (store/save-pipeline! db {} pipeline-v1)
      (store/save-pipeline! db {} (assoc pipeline-v1 :version 2))
      (store/create-run! db
                         {:run/id         run-id
                          :run/status     :done
                          :run/created-at 1
                          :run/started-at 1
                          :run/pipeline   {:pipeline/name :orders :pipeline/version 1}}
                         tasks)
      (store/complete-task! db
                            last-id
                            {:task/status :running
                             :artifact    {:artifact/id artifact-id :artifact/run-id run-id :artifact/kind :scalar}})
      (is (= {:code "pipeline-busy"}
             (try (store/delete-pipeline! db :orders {:version 1}) (catch ExceptionInfo e (ex-data e)))))
      (is (store/get-artifact db artifact-id))
      (store/update-task! db last-id {:task/status :completed})
      (is (= {:deleted true :name :orders :versions [1] :run-ids [run-id]}
             (with-redefs [d/q (fn [& args] (swap! queries inc) (apply query args))]
               (store/delete-pipeline! db :orders {:version 1}))))
      (is (= 1 @queries))
      (is (nil? (store/get-run db run-id)))
      (is (nil? (store/get-artifact db artifact-id)))
      (is (empty? (d/q '[:find ?task :where [?task :task/id]] (d/db (:conn db)))))
      (is (= 2 (:version (store/load-pipeline db :orders)))))))
