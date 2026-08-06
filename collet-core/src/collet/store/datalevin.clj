(ns collet.store.datalevin
  (:require
   [clojure.walk :as walk]
   [collet.store :as store]
   [datalevin.core :as d]))


(def ^:private schema
  {:pipeline/name    {:db/valueType :db.type/keyword}
   :pipeline/version {:db/valueType :db.type/long}
   :pipeline/key     {:db/valueType  :db.type/tuple
                      :db/tupleAttrs [:pipeline/name :pipeline/version]
                      :db/unique     :db.unique/identity}
   :pipeline/plan    {:db/valueType         :db.type/idoc
                      :db/domain            "pipeline_plans"
                      :db.idoc/indexedPaths [:name
                                             :version
                                             [:tasks :name]
                                             [:tasks :inputs]]}

   :run/id           {:db/valueType :db.type/uuid
                      :db/unique    :db.unique/identity}
   :run/pipeline     {:db/valueType :db.type/ref}

   :task/id          {:db/valueType :db.type/uuid
                      :db/unique    :db.unique/identity}
   :task/run         {:db/valueType :db.type/ref}
   :task/name        {:db/valueType :db.type/keyword}
   :task/key         {:db/valueType  :db.type/tuple
                      :db/tupleAttrs [:task/run :task/name]
                      :db/unique     :db.unique/identity}
   :task/inputs      {:db/valueType   :db.type/ref
                      :db/cardinality :db.cardinality/many}})


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


(defn- task-entity->map
  [entity]
  (when entity
    (assoc (select-keys entity
                        [:task/id
                         :task/name
                         :task/status
                         :task/created-at
                         :task/started-at
                         :task/finished-at
                         :task/error])
      :task/run (get-in entity [:task/run :run/id])
      :task/inputs (mapv :task/id (:task/inputs entity)))))


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
                    {:task/inputs [:task/id]}]
                  [:task/id task-id])
          task-entity->map))


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
      (d/transact! conn (into [run-entity] task-entities))
      (pull-run conn run-id)))

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
           {:task/inputs [:task/id]}])
         ...]
        :in $ ?run-id
        :where
        [?run :run/id ?run-id]
        [?task :task/run ?run]]
      (d/db conn)
      run-id)))

  (update-run! [_ run-id changes]
    (update-entity! conn [:run/id run-id] changes)
    (pull-run conn run-id))

  (update-task! [_ task-id changes]
    (update-entity! conn [:task/id task-id] changes)
    (pull-task conn task-id)))


(defn store
  "Creates an open embedded Datalevin Store.
  The default database directory is `./.collet/db`."
  ([]
   (store {}))
  ([{:keys [dir]
     :or   {dir "./.collet/db"}}]
   (let [dir (str dir)]
     (->DatalevinStore dir (d/get-conn dir schema)))))
