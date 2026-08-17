(ns collet.store.datalevin
  (:require
   [clojure.walk :as walk]
   [collet.store :as store]
   [datalevin.core :as d]))


(def ^:private schema
  {:pipeline/name       {:db/valueType :db.type/keyword}
   :pipeline/version    {:db/valueType :db.type/long}
   :pipeline/key        {:db/valueType  :db.type/tuple
                         :db/tupleAttrs [:pipeline/name :pipeline/version]
                         :db/unique     :db.unique/identity}
   :pipeline/plan       {:db/valueType         :db.type/idoc
                         :db/domain            "pipeline_plans"
                         :db.idoc/indexedPaths [:name
                                                :version
                                                [:tasks :name]
                                                [:tasks :inputs]]}
   :pipeline/created-at {:db/valueType :db.type/long}

   :run/id              {:db/valueType :db.type/uuid
                         :db/unique    :db.unique/identity}
   :run/pipeline        {:db/valueType :db.type/ref}
   :run/status          {:db/valueType :db.type/keyword}
   :run/created-at      {:db/valueType :db.type/long}
   :run/started-at      {:db/valueType :db.type/long}
   :run/finished-at     {:db/valueType :db.type/long}
   :run/error           {:db/valueType :db.type/idoc
                         :db/domain    "run_errors"}

   :task/id             {:db/valueType :db.type/uuid
                         :db/unique    :db.unique/identity}
   :task/run            {:db/valueType :db.type/ref}
   :task/name           {:db/valueType :db.type/keyword}
   :task/key            {:db/valueType  :db.type/tuple
                         :db/tupleAttrs [:task/run :task/name]
                         :db/unique     :db.unique/identity}
   :task/inputs         {:db/valueType   :db.type/ref
                         :db/cardinality :db.cardinality/many}
   :task/output         {:db/valueType :db.type/ref}
   :task/status         {:db/valueType :db.type/keyword}
   :task/outcome        {:db/valueType :db.type/keyword}
   :task/created-at     {:db/valueType :db.type/long}
   :task/started-at     {:db/valueType :db.type/long}
   :task/finished-at    {:db/valueType :db.type/long}
   :task/error          {:db/valueType :db.type/idoc
                         :db/domain    "task_errors"}

   :artifact/id         {:db/valueType :db.type/uuid
                         :db/unique    :db.unique/identity}
   :artifact/run        {:db/valueType :db.type/ref}
   :artifact/task       {:db/valueType :db.type/ref}
   :artifact/kind       {:db/valueType :db.type/keyword}
   :artifact/format     {:db/valueType :db.type/keyword}
   :artifact/version    {:db/valueType :db.type/long}
   :artifact/checksum   {:db/valueType :db.type/string}
   :artifact/bytes      {:db/valueType :db.type/long}
   :artifact/records    {:db/valueType :db.type/long}
   :artifact/schema     {:db/valueType :db.type/idoc
                         :db/domain    "artifact_schemas"}
   :artifact/created-at {:db/valueType :db.type/long}})


(defn- to-idoc
  [value]
  (walk/postwalk
   (fn [item]
     (cond
       (list? item)
       {::list (vec item)}

       (and (map? item)
            (or (contains? item ::list)
                (contains? item ::entries)
                (not-every? #(or (keyword? %) (string? %)) (keys item))))
       {::entries (mapv vec item)}

       :else
       item))
   value))


(defn- from-idoc
  [value]
  (walk/postwalk
   (fn [item]
     (cond
       (= :json/null item)
       nil

       (and (map? item) (= #{::list} (set (keys item))))
       (apply list (::list item))

       (and (map? item) (= #{::entries} (set (keys item))))
       (into {} (::entries item))

       :else
       item))
   value))


(defn- pipeline-plan
  [conn name version]
  (some-> (d/q '[:find ?plan .
                 :in $ ?name ?version
                 :where
                 [?pipeline :pipeline/name ?name]
                 [?pipeline :pipeline/version ?version]
                 [?pipeline :pipeline/plan ?plan]]
               (d/db conn)
               name
               version)
          from-idoc))


(defn- latest-pipeline-version
  [conn name]
  (d/q '[:find (max ?version) .
         :in $ ?name
         :where
         [?pipeline :pipeline/name ?name]
         [?pipeline :pipeline/version ?version]]
       (d/db conn)
       name))


(defn- run-entity->map
  [entity]
  (when entity
    (assoc (select-keys entity
                        [:run/id
                         :run/status
                         :run/created-at
                         :run/started-at
                         :run/finished-at
                         :run/error])
      :run/pipeline
      (select-keys (:run/pipeline entity)
                   [:pipeline/name :pipeline/version]))))


(defn- output-entity->ref
  [entity]
  (when-let [artifact-id (:artifact/id entity)]
    [:artifact/id artifact-id]))


(defn- output-entity->map
  [entity]
  (when-let [output-ref (output-entity->ref entity)]
    {:output/kind (:artifact/kind entity)
     :output/ref  output-ref}))


(defn- artifact-entity->map
  [entity]
  (when entity
    (cond->
      (select-keys entity
                   [:artifact/id
                    :artifact/kind
                    :artifact/format
                    :artifact/version
                    :artifact/checksum
                    :artifact/bytes
                    :artifact/records
                    :artifact/created-at])
      (:artifact/run entity)
      (assoc :artifact/run-id (get-in entity [:artifact/run :run/id]))

      (:artifact/task entity)
      (assoc :artifact/task-id (get-in entity [:artifact/task :task/id]))

      (:artifact/schema entity)
      (assoc :artifact/schema (from-idoc (:artifact/schema entity))))))


(defn- task-entity->map
  [entity]
  (when entity
    (cond->
      (assoc (select-keys entity
                          [:task/id
                           :task/name
                           :task/status
                           :task/outcome
                           :task/created-at
                           :task/started-at
                           :task/finished-at
                           :task/error])
        :task/run (get-in entity [:task/run :run/id])
        :task/inputs (mapv :task/id (:task/inputs entity)))
      (:task/output entity)
      (assoc :task/output (output-entity->ref (:task/output entity))))))


(defn- pull-run
  [conn run-id]
  (some-> (d/pull (d/db conn)
                  '[* {:run/pipeline [:pipeline/name :pipeline/version]}]
                  [:run/id run-id])
          run-entity->map))


(defn- pull-task
  [conn task-id]
  (some-> (d/pull (d/db conn)
                  '[* {:task/run [:run/id]}
                    {:task/inputs [:task/id]}
                    {:task/output [:artifact/id :artifact/kind]}]
                  [:task/id task-id])
          task-entity->map))


(defn- pull-artifact
  [conn artifact-id]
  (some-> (d/pull (d/db conn)
                  '[* {:artifact/run [:run/id]}
                    {:artifact/task [:task/id]}]
                  [:artifact/id artifact-id])
          artifact-entity->map))


(def ^:private task-pull-pattern
  '[* {:task/run [:run/id]}
    {:task/inputs [:task/id]}
    {:task/output [:artifact/id :artifact/kind]}])


(def ^:private artifact-pull-pattern
  '[* {:artifact/run [:run/id]}
    {:artifact/task [:task/id]}])


(defn- update-entity!
  [conn lookup-ref changes]
  (let [assertions  (into {:db/id lookup-ref}
                          (remove (comp nil? val))
                          changes)
        retractions (keep (fn [[attribute value]]
                            (when (nil? value)
                              [:db/retract lookup-ref attribute]))
                          changes)]
    (d/transact! conn
      (cond-> [assertions]
        (seq retractions) (into retractions)))))


(defn- without-nils
  [entity]
  (into {} (remove (comp nil? val)) entity))


(defn- completion-transaction
  [task-id completion]
  (let [{:keys [artifact output]} completion
        artifact-temp -1
        output-temp (when output artifact-temp)
        run-id (:artifact/run-id artifact)
        artifact-entity
        (when artifact
          (without-nils
           (merge {:db/id         artifact-temp
                   :artifact/id   (:artifact/id artifact)
                   :artifact/run  [:run/id run-id]
                   :artifact/task [:task/id task-id]}
                  (select-keys artifact
                               [:artifact/kind
                                :artifact/format
                                :artifact/version
                                :artifact/checksum
                                :artifact/bytes
                                :artifact/records
                                :artifact/created-at])
                  (when-let [schema (:artifact/schema artifact)]
                    {:artifact/schema (to-idoc schema)}))))
        task-entity
        (without-nils
         (merge {:db/id [:task/id task-id]}
                (select-keys completion
                             [:task/status
                              :task/outcome
                              :task/finished-at
                              :task/error])
                (when output-temp {:task/output output-temp})))]
    (when (and output (nil? artifact))
      (throw (ex-info "Task completion output must have an artifact."
                      {:completion completion})))
    (when (and output
               (not= (:output/ref output)
                     [:artifact/id (:artifact/id artifact)]))
      (throw (ex-info "Task completion output does not match its artifact metadata."
                      {:completion completion})))
    (vec (concat [task-entity]
                 (when artifact-entity [artifact-entity])))))


(defn- task-lineage
  [conn task-id direction]
  (let [query (case direction
                :upstream
                '[:find [(pull ?input pattern) ...]
                  :in $ ?task-id pattern
                  :where
                  [?task :task/id ?task-id]
                  [?task :task/inputs ?input]]

                :downstream
                '[:find [(pull ?dependant pattern) ...]
                  :in $ ?task-id pattern
                  :where
                  [?task :task/id ?task-id]
                  [?dependant :task/inputs ?task]]

                (throw (ex-info "Lineage direction must be :upstream or :downstream."
                                {:direction direction})))]
    (mapv task-entity->map
          (d/q query (d/db conn) task-id task-pull-pattern))))


(defn- artifacts-for-run
  [conn run-id]
  (mapv artifact-entity->map
        (d/q '[:find [(pull ?artifact pattern) ...]
               :in $ ?run-id pattern
               :where
               [?run :run/id ?run-id]
               [?artifact :artifact/run ?run]]
             (d/db conn)
             run-id
             artifact-pull-pattern)))


(defn- finalize-run-transaction
  [conn run-id run-changes retained-task-ids]
  (let [retained-task-ids (set retained-task-ids)
        released          (into []
                                (remove #(contains? retained-task-ids
                                                    (:artifact/task-id %)))
                                (artifacts-for-run conn run-id))
        release-tx        (mapcat
                           (fn [{:artifact/keys [id task-id]}]
                             [[:db/retract
                               [:task/id task-id]
                               :task/output
                               [:artifact/id id]]
                              [:db.fn/retractEntity [:artifact/id id]]])
                           released)]
    (when (seq run-changes)
      (update-entity! conn [:run/id run-id] run-changes))
    (when (seq release-tx)
      (d/transact! conn (vec release-tx)))
    {:run                (pull-run conn run-id)
     :released-artifacts released}))


(defrecord DatalevinStore [dir conn]
  store/Store
  (close! [_]
    (d/close conn)
    nil)

  (save-pipeline! [_ pipeline]
    (let [{:keys [name version]} pipeline]
      (d/with-transaction [transaction conn]
        (let [existing (pipeline-plan transaction name version)]
          (cond
            (= existing pipeline)
            pipeline

            existing
            (throw
             (ex-info
              "A different pipeline plan already exists for this name and version."
              {:collet.error/type :collet.error/pipeline-revision-conflict
               :name              name
               :version           version}))

            :else
            (do
              (d/transact! transaction
                [{:pipeline/name       name
                  :pipeline/version    version
                  :pipeline/plan       (to-idoc pipeline)
                  :pipeline/created-at (System/currentTimeMillis)}])
              pipeline))))))

  (load-pipeline [_ name]
    (when-let [version (latest-pipeline-version conn name)]
      (pipeline-plan conn name version)))

  (load-pipeline [_ name version]
    (pipeline-plan conn name version))

  (create-run! [_ run task-runs]
    (d/with-transaction [transaction conn]
      (let [run-id           (:run/id run)
            pipeline-name    (get-in run [:run/pipeline :pipeline/name])
            pipeline-version (get-in run [:run/pipeline :pipeline/version])
            task-id->temp-id (into {}
                                   (map-indexed
                                    (fn [index task]
                                      [(:task/id task) (- (+ index 2))])
                                    task-runs))
            run-entity       {:db/id          -1
                              :run/id         run-id
                              :run/pipeline   [:pipeline/key [pipeline-name pipeline-version]]
                              :run/status     (:run/status run)
                              :run/created-at (:run/created-at run)
                              :run/started-at (:run/started-at run)}
            task-entities    (mapv
                              (fn [task]
                                (cond-> {:db/id           (get task-id->temp-id (:task/id task))
                                         :task/id         (:task/id task)
                                         :task/run        -1
                                         :task/name       (:task/name task)
                                         :task/status     (:task/status task)
                                         :task/created-at (:task/created-at task)}
                                  (seq (:task/inputs task))
                                  (assoc :task/inputs
                                    (mapv task-id->temp-id (:task/inputs task)))))
                              task-runs)]
        (d/transact! transaction (into [run-entity] task-entities))
        (pull-run transaction run-id))))

  (get-run [_ run-id]
    (pull-run conn run-id))

  (get-task-runs [_ run-id]
    (mapv
     task-entity->map
     (d/q
      '[:find
        [(pull
          ?task
          [* {:task/run [:run/id]}
           {:task/inputs [:task/id]}
           {:task/output [:artifact/id :artifact/kind]}])
         ...]
        :in $ ?run-id
        :where
        [?run :run/id ?run-id]
        [?task :task/run ?run]]
      (d/db conn)
      run-id)))

  (update-run! [_ run-id changes]
    (d/with-transaction [transaction conn]
      (update-entity! transaction [:run/id run-id] changes)
      (pull-run transaction run-id)))

  (update-task! [_ task-id changes]
    (d/with-transaction [transaction conn]
      (update-entity! transaction [:task/id task-id] changes)
      (pull-task transaction task-id)))

  (complete-task! [_ task-id completion]
    (d/with-transaction [transaction conn]
      (d/transact! transaction (completion-transaction task-id completion))
      (pull-task transaction task-id)))

  (finalize-run! [_ run-id run-changes retained-task-ids]
    (d/with-transaction [transaction conn]
      (finalize-run-transaction transaction
                                run-id
                                run-changes
                                retained-task-ids)))

  (get-task-output [_ task-id]
    (some-> (d/pull (d/db conn)
                    '[{:task/output [:artifact/id :artifact/kind]}]
                    [:task/id task-id])
            :task/output
            output-entity->map))

  (get-artifact [_ artifact-id]
    (pull-artifact conn artifact-id))

  (get-lineage [_ task-id direction]
    (task-lineage conn task-id direction)))


(defn store
  "Creates an open embedded Datalevin Store.
  The default database directory is `./.collet/db`."
  ([]
   (store {}))
  ([{:keys [dir]
     :or   {dir "./.collet/db"}}]
   (let [dir  (str dir)
         conn (d/get-conn dir
                          schema
                          {:closed-schema? true
                           :validate-data? true})]
     (->DatalevinStore dir conn))))
