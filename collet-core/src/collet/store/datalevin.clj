(ns collet.store.datalevin
  (:require
   [clojure.walk :as walk]
   [collet.store :as store]
   [datalevin.core :as d]
   [datalevin.idoc :as idoc])
  (:import
   [clojure.lang ExceptionInfo]))


(def ^:private schema
  {:draft/id               {:db/valueType :db.type/uuid :db/unique :db.unique/identity}
   :draft/revision         {:db/valueType :db.type/long}
   :draft/source           {:db/valueType :db.type/string}
   :draft/title            {:db/valueType :db.type/string}
   :draft/created-at       {:db/valueType :db.type/long}
   :draft/updated-at       {:db/valueType :db.type/long}
   :draft/origin           {:db/valueType :db.type/idoc :db/domain "draft_origins"}
   :draft.edit/draft       {:db/valueType :db.type/ref}
   :draft.edit/revision    {:db/valueType :db.type/long}
   :draft.edit/key         {:db/valueType  :db.type/tuple
                            :db/tupleAttrs [:draft.edit/draft :draft.edit/revision]
                            :db/unique     :db.unique/identity}
   :draft.edit/source      {:db/valueType :db.type/string}
   :draft.edit/author      {:db/valueType :db.type/string}
   :draft.edit/summary     {:db/valueType :db.type/string}
   :draft.edit/description {:db/valueType :db.type/string}
   :draft.edit/at          {:db/valueType :db.type/long}
   :pipeline/name          {:db/valueType :db.type/keyword}
   :pipeline/version       {:db/valueType :db.type/long}
   :pipeline/key           {:db/valueType  :db.type/tuple
                            :db/tupleAttrs [:pipeline/name :pipeline/version]
                            :db/unique     :db.unique/identity}
   :pipeline/plan          {:db/valueType         :db.type/idoc
                            :db/domain            "pipeline_plans"
                            :db.idoc/indexedPaths [:name
                                                   :version
                                                   [:tasks :name]
                                                   [:tasks :inputs]]}
   :pipeline/created-at    {:db/valueType :db.type/long}

   :run/id                 {:db/valueType :db.type/uuid
                            :db/unique    :db.unique/identity}
   :run/pipeline           {:db/valueType :db.type/ref}
   :run/status             {:db/valueType :db.type/keyword}
   :run/created-at         {:db/valueType :db.type/long}
   :run/started-at         {:db/valueType :db.type/long}
   :run/finished-at        {:db/valueType :db.type/long}
   :run/error              {:db/valueType :db.type/idoc
                            :db/domain    "run_errors"}

   :task/id                {:db/valueType :db.type/uuid
                            :db/unique    :db.unique/identity}
   :task/run               {:db/valueType :db.type/ref}
   :task/name              {:db/valueType :db.type/keyword}
   :task/key               {:db/valueType  :db.type/tuple
                            :db/tupleAttrs [:task/run :task/name]
                            :db/unique     :db.unique/identity}
   :task/inputs            {:db/valueType   :db.type/ref
                            :db/cardinality :db.cardinality/many}
   :task/output            {:db/valueType :db.type/ref}
   :task/status            {:db/valueType :db.type/keyword}
   :task/outcome           {:db/valueType :db.type/keyword}
   :task/created-at        {:db/valueType :db.type/long}
   :task/started-at        {:db/valueType :db.type/long}
   :task/retry-count       {:db/valueType :db.type/long}
   :task/output-records    {:db/valueType :db.type/long}
   :task/output-bytes      {:db/valueType :db.type/long}
   :task/finished-at       {:db/valueType :db.type/long}
   :task/error             {:db/valueType :db.type/idoc
                            :db/domain    "task_errors"}

   :artifact/id            {:db/valueType :db.type/uuid
                            :db/unique    :db.unique/identity}
   :artifact/run           {:db/valueType :db.type/ref}
   :artifact/task          {:db/valueType :db.type/ref}
   :artifact/kind          {:db/valueType :db.type/keyword}
   :artifact/format        {:db/valueType :db.type/keyword}
   :artifact/version       {:db/valueType :db.type/long}
   :artifact/checksum      {:db/valueType :db.type/string}
   :artifact/bytes         {:db/valueType :db.type/long}
   :artifact/records       {:db/valueType :db.type/long}
   :artifact/max-row-bytes {:db/valueType :db.type/long}
   :artifact/parts         {:db/valueType :db.type/long}
   :artifact/schema        {:db/valueType :db.type/idoc
                            :db/domain    "artifact_schemas"}
   :artifact/created-at    {:db/valueType :db.type/long}})


(defn- to-idoc
  [value]
  (walk/postwalk
   (fn [item]
     (cond
       (= :json/null item)
       (throw (ex-info ":json/null is reserved for IDoc. Use nil instead."
                       {:value item}))

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


(defn- with-no-write-result [write]
  (let [skip! (fn skip! [value]
                ;; An empty transact! still advances the transaction ID and notifies listeners.
                (throw (ex-info "No changes to commit." {::no-write skip! :result value})))]
    (try
      (write skip!)
      (catch ExceptionInfo e
        (let [data (ex-data e)]
          (if (identical? skip! (::no-write data))
            (:result data)
            (throw e)))))))


(defn- asserted-attributes [{:keys [tx-data]} eid]
  (into {} (for [{:keys [e a v added]} tx-data :when (and added (= e eid))] [a v])))


(defn- pipeline-document [db name version]
  (:pipeline/plan (d/pull db [:pipeline/plan] [:pipeline/key [name version]])))


(def ^:private latest-active-version
  '[:find (max ?version) . :in $ ?name
    :where [?p :pipeline/name ?name] [?p :pipeline/plan] [?p :pipeline/version ?version]])


(defn- latest-pipeline-document [db name]
  (d/q '[:find ?plan . :in $ ?latest ?name
         :where
         [(q ?latest $ ?name) ?version]
         [?p :pipeline/name ?name]
         [?p :pipeline/version ?version]
         [?p :pipeline/plan ?plan]]
       db
       latest-active-version
       name))


(defn- last-pipeline-version [db name]
  ;; Deleted identities stay reserved so old version URLs cannot point at different code.
  (d/q '[:find (max ?version) . :in $ ?name
         :where
         [?p :pipeline/name ?name]
         [?p :pipeline/version ?version]]
       db
       name))


(def ^:private run-attributes
  [:run/id :run/status :run/created-at :run/started-at :run/finished-at :run/error])


(def ^:private run-pull-pattern
  (conj run-attributes '{(:run/pipeline :default {}) [:pipeline/name :pipeline/version]}))


(def ^:private run-snapshot-pattern
  (conj run-pull-pattern :db/id))


(def ^:private task-attributes
  [:task/id :task/name :task/status :task/outcome :task/created-at :task/started-at
   :task/retry-count :task/output-records :task/output-bytes :task/finished-at :task/error])


(def ^:private task-pull-pattern
  (into task-attributes
        '[{:task/run [:run/id]}
          {(:task/inputs :limit nil) [:task/id]}
          {:task/output [:artifact/id]}]))


(def ^:private artifact-attributes
  [:artifact/id :artifact/kind :artifact/format :artifact/version :artifact/checksum
   :artifact/bytes :artifact/records :artifact/max-row-bytes :artifact/parts :artifact/created-at])


(def ^:private artifact-pull-pattern
  (into artifact-attributes '[:artifact/schema {:artifact/run [:run/id]} {:artifact/task [:task/id]}]))


(defn- output-entity->ref [entity]
  (when-let [id (:artifact/id entity)]
    [:artifact/id id]))


(defn- artifact-entity->map [entity]
  (when entity
    (cond-> (dissoc entity :artifact/run :artifact/task)
      (:artifact/run entity) (assoc :artifact/run-id (get-in entity [:artifact/run :run/id]))
      (:artifact/task entity) (assoc :artifact/task-id (get-in entity [:artifact/task :task/id]))
      (:artifact/schema entity) (update :artifact/schema from-idoc))))


(defn- task-entity->map [entity]
  (when entity
    (cond-> (assoc entity
              :task/run (get-in entity [:task/run :run/id])
              :task/inputs (mapv :task/id (:task/inputs entity)))
      (:task/output entity) (update :task/output output-entity->ref))))


(defn- pull-run [conn run-id]
  (d/pull (d/db conn) run-pull-pattern [:run/id run-id]))


(defn- entity-changes [lookup-ref changes]
  (into [(into {:db/id lookup-ref} (remove (comp nil? val)) changes)]
        (keep (fn [[attribute value]] (when (nil? value) [:db/retract lookup-ref attribute])))
        changes))


(defn- entity-from-report [before id-attribute attributes report]
  (when before
    (let [eid        (:db/id before)
          attributes (set attributes)
          entity     (reduce (fn [entity {:keys [e a v added]}]
                               (if (and (= eid e) (contains? attributes a))
                                 (if added (assoc entity a v) (dissoc entity a))
                                 entity))
                             (dissoc before :db/id)
                             (:tx-data report))]
      (when (= (get before id-attribute) (get entity id-attribute)) entity))))


(defn- transact-checked!
  "Return the checked snapshot and the transaction report, if there were writes."
  [conn read-snapshot prepare-tx]
  (let [db    (d/db conn)
        check (fn check [db before read-tx skip!]
                (when-not (and (some? read-tx) (= read-tx (:max-tx db)))
                  ;; An unrelated commit can advance the transaction ID.
                  (let [current (read-snapshot db)]
                    (when (not= before current)
                      (throw (ex-info "Snapshot changed before the write."
                                      {::retry check :before current :read-tx (:max-tx db)})))))
                (when skip! (skip! nil))
                [])]
    (loop [read-tx (:max-tx db)
           before  (read-snapshot db)]
      (let [tx     (prepare-tx before)
            result (try
                     (if (seq tx)
                       (d/transact! conn (into [[:db.fn/call check before read-tx nil]] tx))
                       (with-no-write-result
                        (fn [skip!] (d/transact! conn [[:db.fn/call check before read-tx skip!]]))))
                     (catch ExceptionInfo e
                       (let [data (ex-data e)]
                         (if (identical? check (::retry data)) data (throw e)))))]
        (if (identical? check (::retry result))
          (recur (:read-tx result) (:before result))
          [before result])))))


(defn- transact-entity! [conn ref pattern attributes tx]
  (let [pattern         (conj pattern :db/id)
        [before report] (transact-checked! conn
                                           #(d/pull % pattern ref)
                                           (constantly tx))]
    (entity-from-report before (first ref) attributes report)))


(defn- update-entity! [conn ref pattern attributes changes]
  (let [unsupported (into [] (remove (set attributes)) (keys changes))]
    (when (seq unsupported)
      (throw (ex-info "Run and task updates accept only non-reference fields."
                      {:lookup-ref ref :unsupported-fields unsupported})))
    (transact-entity! conn ref pattern attributes (entity-changes ref changes))))


(defn- without-nils
  [entity]
  (into {} (remove (comp nil? val)) entity))


(defn- completion-transaction
  [task-id completion]
  (let [{:keys [artifact output]} completion
        artifact-temp             -1
        output-temp               (when output artifact-temp)
        run-id                    (:artifact/run-id artifact)
        artifact-entity           (when artifact
                                    (without-nils
                                     (merge {:db/id         artifact-temp
                                             :artifact/run  [:run/id run-id]
                                             :artifact/task [:task/id task-id]}
                                            (select-keys artifact artifact-attributes)
                                            (when-let [schema (:artifact/schema artifact)]
                                              {:artifact/schema (to-idoc schema)}))))
        task-entity               (without-nils
                                   (merge {:db/id [:task/id task-id]}
                                          (select-keys completion
                                                       [:task/status
                                                        :task/outcome
                                                        :task/retry-count
                                                        :task/output-records
                                                        :task/output-bytes
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
    (cond-> [task-entity] artifact-entity (conj artifact-entity))))


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


(defn- releasable-artifacts [db run-id retained]
  (let [query '[:find [(pull ?artifact pattern) ...]
                :in $ ?run-id ?retained pattern
                :where [?run :run/id ?run-id]
                [?artifact :artifact/run ?run]
                (not-join
                 [?artifact]
                 [?artifact :artifact/task ?task]
                 [?task :task/id ?id]
                 [(contains? ?retained ?id)])]]
    (set (d/q (cond-> query
                ;; A nil retention entry also keeps artifacts that lack a task ID.
                (contains? retained nil) (conj '[?artifact :artifact/task ?owner] '[?owner :task/id]))
              db
              run-id
              retained
              artifact-pull-pattern))))


(defn- draft-source! [source]
  (when-not (and (string? source) (<= (count source) 262144))
    (throw (ex-info "Draft source must be text smaller than 256 KiB." {:collet.error/type :collet.error/invalid-draft-source})))
  source)


(defn- pull-draft [db id]
  (some-> (d/pull db '[*] [:draft/id id])
          (dissoc :db/id)))


(def ^:private draft-edit-metadata-pattern
  [:draft.edit/revision :draft.edit/author :draft.edit/summary :draft.edit/description :draft.edit/at])


(defn- check-draft!
  ([draft expected]
   (check-draft! draft expected nil))
  ([draft expected expected-draft]
   (when-not draft
     (throw (ex-info "Draft not found." {:collet.error/type :collet.error/draft-not-found})))
   (when (or (not= expected (:draft/revision draft))
             (and (some? expected-draft) (not= expected-draft draft)))
     (throw (ex-info "Draft changed. Compare your text with the current revision."
                     {:code :draft-conflict :current draft})))))


(defn- edit-draft [db id expected source author summary metadata skip!]
  (let [draft (pull-draft db id)]
    (check-draft! draft expected (:expected-draft metadata))
    (if (= source (:draft/source draft))
      (skip! {})
      (let [revision (inc expected)
            now      (System/currentTimeMillis)
            changes  (merge {:draft/revision revision :draft/source source :draft/updated-at now}
                            (select-keys metadata [:draft/title]))
            edit     (cond-> {:db/id               "edit"
                              :draft.edit/draft    [:draft/id id]
                              :draft.edit/revision revision
                              :draft.edit/source   source
                              :draft.edit/author   (or author "browser")
                              :draft.edit/summary  (or summary "Updated source")
                              :draft.edit/at       now}
                       (:draft.edit/description metadata)
                       (assoc :draft.edit/description (:draft.edit/description metadata)))]
        [(assoc changes :db/id [:draft/id id]) edit]))))


(defn- require-unused-draft-id [db id]
  (when (d/entid db [:draft/id id])
    (throw (ex-info "Draft already exists." {:collet.error/type :collet.error/draft-already-exists})))
  [])


(defn- retract-draft [db id expected]
  (let [draft (d/pull db '[* {:draft.edit/_draft [:db/id]}] [:draft/id id])]
    (check-draft! (dissoc draft :db/id :draft.edit/_draft) expected)
    (conj (mapv #(vector :db.fn/retractEntity (:db/id %)) (:draft.edit/_draft draft))
          [:db.fn/retractEntity (:db/id draft)])))


(defn- page-options [limit]
  (if (map? limit)
    {:limit  (max 0 (min 101 (or (:limit limit) 21)))
     :before (if-let [cursor (:cursor limit)]
               (or (parse-long (str cursor))
                   (throw (ex-info "Invalid page cursor." {:code "invalid-cursor"})))
               Long/MAX_VALUE)}
    {:limit (max 0 (min 1000 limit)) :before Long/MAX_VALUE}))


(defn- pull-page
  "Select ordered IDs before pulling. A pull in the limited query projects every matching entity."
  [conn pattern {:keys [find in order-by] :as ids-query} & inputs]
  (let [query {:find     (conj find '(pull ?e pattern))
               :in       (into '[$ ?ids-query pattern] (rest in))
               :where    [[(list* 'q '?ids-query in) [find]]]
               :order-by order-by}]
    (apply d/q query (d/db conn) ids-query pattern inputs)))


(defn- entity-page [conn page where pattern timestamp legacy-sort]
  (let [{:keys [limit before]} (page-options page)
        cursor?                (map? page)
        fields                 (if cursor? '[?e] legacy-sort)
        query                  {:find     fields
                                :in       '[$ ?before ?size ?timestamp ?oldest]
                                :where    (cond-> (conj where '[(< ?e ?before)])
                                            (not cursor?) (conj '[(get-else $ ?e ?timestamp ?oldest) ?time]))
                                :order-by [(vec (interleave fields (repeat :desc)))]
                                :limit    '[?size]}]
    (if (zero? limit)
      []
      (mapv (fn [row] (cond-> (peek row) cursor? (assoc :cursor (str (first row)))))
            (pull-page conn pattern query before limit timestamp Long/MIN_VALUE)))))


(def ^:private pipeline-deletion-pattern
  '[:db/id :pipeline/version
    {:run/_pipeline [:db/id :run/id :run/status
                     {:task/_run [:db/id :task/status]}
                     {:artifact/_run [:db/id]}]}])


(defn- retract-pipeline [db name {:keys [version latest-version busy-run?]}]
  (let [versions (d/q '[:find [(pull ?p pattern) ...]
                        :in $ pattern ?name ?wanted
                        :where [?p :pipeline/name ?name] [?p :pipeline/plan] [?p :pipeline/version ?v]
                        (or [(nil? ?wanted)] [(= ?wanted ?v)])]
                      db
                      pipeline-deletion-pattern
                      name
                      version)
        runs     (mapcat :run/_pipeline versions)
        tasks    (mapcat :task/_run runs)]
    (when (empty? versions)
      (throw (ex-info "Pipeline revision not found." {:code "pipeline-not-found"})))
    (when (and (nil? version) (not= latest-version (apply max (map :pipeline/version versions))))
      (throw (ex-info "Pipeline versions changed. Refresh before deleting." {:code "pipeline-changed"})))
    (when (or (some #(not (#{:done :failed :stopped} (:run/status %))) runs)
              (some #(= :running (:task/status %)) tasks)
              (and busy-run? (some (comp busy-run? :run/id) runs)))
      (throw (ex-info "Stop affected runs and wait for their workers before deleting." {:code "pipeline-busy"})))
    (into (mapv #(vector :db.fn/retractEntity (:db/id %)) (concat (mapcat :artifact/_run runs) tasks runs))
          (map #(vector :db/retract (:db/id %) :pipeline/plan))
          versions)))


(defn- save-pipeline-tx [db {:keys [name version] :as pipeline} dev-opts skip!]
  (let [stored   (if (some? version) (pipeline-document db name version) (latest-pipeline-document db name))
        existing (from-idoc stored)]
    (cond
      (= pipeline (cond-> existing (nil? version) (dissoc :version)))
      (skip! existing)

      (and existing dev-opts)
      (let [version  (:version existing)
            document (to-idoc (assoc pipeline :version version))]
        ;; Datalevin may normalize an edited plan to the same stored IDoc.
        (when (= stored (idoc/parse-value :pipeline/plan (:pipeline/plan schema) dev-opts document))
          (skip! existing))
        [{:db/id [:pipeline/key [name version]] :pipeline/plan document}])

      :else
      (let [last-version (last-pipeline-version db name)
            new-version  (if last-version (inc last-version) (or version 1))]
        (when (and last-version (some? version) (not= version new-version))
          (let [latest-version (d/q latest-active-version db name)]
            (when-not (= version latest-version)
              (throw (ex-info "Invalid pipeline version."
                              {:collet.error/type :collet.error/pipeline-revision-conflict
                               :name              name
                               :requested-version version
                               :latest-version    latest-version})))))
        [{:db/id               "pipeline"
          :pipeline/name       name
          :pipeline/version    new-version
          :pipeline/plan       (to-idoc (assoc pipeline :version new-version))
          :pipeline/created-at (System/currentTimeMillis)}]))))


(defn- check-new-run! [db run-id pipeline-key]
  (when-not (d/q '[:find ?p . :in $ ?key :where [?p :pipeline/key ?key] [?p :pipeline/plan]]
                 db
                 pipeline-key)
    (throw (ex-info "Pipeline revision was deleted before execution started." {:code "pipeline-not-found"})))
  (when (d/entid db [:run/id run-id])
    (throw (ex-info "Run already exists." {:collet.error/type :collet.error/run-already-exists :run-id run-id})))
  [])


(defrecord DatalevinStore [dir conn]
  store/PipelineDeletion
  (delete-pipeline! [_ name options]
    (let [report  (d/transact! conn [[:db.fn/call retract-pipeline name options]])
          deleted (reduce (fn [result {:keys [a v added]}]
                            (if added
                              result
                              (case a
                                ;; The tombstone keeps :pipeline/version. The retracted plan also has it.
                                :pipeline/plan (update result :versions conj (:version v))
                                :run/id (update result :run-ids conj v)
                                result)))
                          {:deleted true :name name :versions [] :run-ids []}
                          (:tx-data report))]
      (update deleted :versions #(vec (sort %)))))

  store/DraftStore
  (create-draft! [_ draft]
    (let [now    (System/currentTimeMillis)
          id     (or (:draft/id draft) (random-uuid))
          source (draft-source! (:draft/source draft))
          entity (merge {:db/id            "draft"
                         :draft/id         id
                         :draft/revision   0
                         :draft/source     source
                         :draft/created-at now
                         :draft/updated-at now}
                        (select-keys draft [:draft/origin :draft/title]))
          edit   {:draft.edit/draft    "draft"
                  :draft.edit/revision 0
                  :draft.edit/source   source
                  :draft.edit/author   (or (:author draft) "browser")
                  :draft.edit/summary  "Created draft"
                  :draft.edit/at       now}
          report (d/transact! conn [[:db.fn/call require-unused-draft-id id] entity edit])]
      (asserted-attributes report (get-in report [:tempids "draft"]))))

  (list-drafts [_ page]
    (entity-page conn
                 page
                 '[[?e :draft/id]]
                 [:draft/id :draft/revision :draft/created-at :draft/updated-at :draft/origin :draft/title]
                 :draft/updated-at
                 '[?time ?e]))

  (get-draft [_ id]
    (pull-draft (d/db conn) id))

  (get-draft [_ id revision]
    (let [db      (d/db conn)
          pattern (conj draft-edit-metadata-pattern :draft.edit/source)]
      (or (d/q '[:find (pull ?edit-ref pattern) .
                 :in $ ?draft-ref ?revision pattern
                 :where [(datalevin.core/entid $ ?draft-ref) ?draft]
                 [(vector ?draft ?revision) ?key]
                 [(vector :draft.edit/key ?key) ?edit-ref]]
               db
               [:draft/id id]
               revision
               pattern)
          ;; Datalevin does not backfill tuples for existing edits.
          (d/q '[:find (pull ?edit pattern) .
                 :in $ ?id ?revision pattern
                 :where [?draft :draft/id ?id]
                 [?edit :draft.edit/draft ?draft]
                 [?edit :draft.edit/revision ?revision]]
               db
               id
               revision
               pattern))))

  (draft-history [_ id page]
    (let [{:keys [limit before]} (page-options page)]
      (if (zero? limit)
        []
        (mapv peek
              (pull-page conn
                         draft-edit-metadata-pattern
                         '{:find     [?revision ?e]
                           :in       [$ ?draft-ref ?before ?size]
                           :where    [[(datalevin.core/entid $ ?draft-ref) ?d]
                                      [?e :draft.edit/draft ?d] [?e :draft.edit/revision ?revision]
                                      [(< ?revision ?before)]]
                           :order-by [[?revision :desc]]
                           :limit    [?size]}
                         [:draft/id id]
                         before
                         limit)))))

  (update-draft! [this id expected source author summary]
    (store/update-draft! this id expected source author summary {}))

  (update-draft! [_ id expected source author summary metadata]
    (draft-source! source)
    (with-no-write-result
     (fn [skip!]
       (let [report (d/transact! conn [[:db.fn/call edit-draft id expected source author summary metadata skip!]])
             eid    (some (fn [{:keys [e a added]}] (when (and added (= :draft/revision a)) e)) (:tx-data report))]
         (asserted-attributes report eid)))))

  (delete-draft! [_ id expected]
    (d/transact! conn [[:db.fn/call retract-draft id expected]])
    {:deleted true})


  store/Store
  (close! [_]
    (d/close conn)
    nil)

  (save-pipeline! [_ {:keys [dev?]} pipeline]
    (let [pipeline (cond-> pipeline (nil? (:version pipeline)) (dissoc :version))
          dev-opts (when dev? (d/opts conn))]
      (with-no-write-result
       (fn [skip!]
         (let [report (d/transact! conn [[:db.fn/call save-pipeline-tx pipeline dev-opts skip!]])]
           (some (fn [{:keys [a v added]}]
                   (when (and added (= a :pipeline/plan)) (from-idoc v)))
                 (:tx-data report)))))))

  (load-pipeline [_ name]
    (from-idoc (latest-pipeline-document (d/db conn) name)))

  (load-pipeline [_ name version]
    (from-idoc (pipeline-document (d/db conn) name version)))

  (list-pipelines [_ page]
    (entity-page conn
                 page
                 '[[?e :pipeline/name] [?e :pipeline/plan] [?e :pipeline/version ?version]]
                 [:pipeline/name :pipeline/version :pipeline/created-at]
                 :pipeline/created-at
                 '[?time ?version ?e]))

  (list-runs [_ page]
    (entity-page conn
                 page
                 '[[?e :run/id]]
                 run-pull-pattern
                 :run/created-at
                 '[?time ?e]))

  (create-run! [_ run task-runs]
    (let [run-id           (:run/id run)
          pipeline-name    (get-in run [:run/pipeline :pipeline/name])
          pipeline-version (get-in run [:run/pipeline :pipeline/version])
          pipeline-key     [pipeline-name pipeline-version]
          pipeline         {:pipeline/name    pipeline-name
                            :pipeline/version (some-> pipeline-version
                                                      long)}
          task-id->temp-id (->> task-runs
                                (map-indexed
                                 (fn [index task]
                                   [(:task/id task) (- (+ index 2))]))
                                (into {}))
          run-entity       {:db/id          -1
                            :run/id         run-id
                            :run/pipeline   [:pipeline/key pipeline-key]
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
      (let [report (d/transact! conn
                     (into [[:db.fn/call check-new-run! run-id pipeline-key] run-entity]
                           task-entities))
            stored (asserted-attributes report (get-in report [:tempids -1]))]
        (assoc stored :run/pipeline pipeline))))

  (get-run [_ run-id]
    (pull-run conn run-id))

  (get-task-runs [_ run-id]
    (mapv task-entity->map
          (d/q '[:find [(pull ?task pattern) ...]
                 :in $ ?run-id pattern
                 :where [?run :run/id ?run-id] [?task :task/run ?run]]
               (d/db conn)
               run-id
               task-pull-pattern)))

  (update-run! [_ run-id changes]
    (update-entity! conn [:run/id run-id] run-pull-pattern run-attributes changes))

  (update-task! [_ task-id changes]
    (task-entity->map (update-entity! conn [:task/id task-id] task-pull-pattern task-attributes changes)))

  (complete-task! [_ task-id completion]
    (let [tx   (completion-transaction task-id completion)
          task (task-entity->map (transact-entity! conn [:task/id task-id] task-pull-pattern task-attributes tx))]
      (cond-> task
        (and task (:output completion)) (assoc :task/output (output-entity->ref (:artifact completion))))))

  (finalize-run! [_ run-id run-changes retained-task-ids]
    (let [unsupported (into [] (remove (set run-attributes)) (keys run-changes))]
      (when (seq unsupported)
        (throw (ex-info "Run finalization accepts only non-reference run fields."
                        {:run-id run-id :unsupported-fields unsupported}))))
    (let [run-ref [:run/id run-id]
          retained (set retained-task-ids)
          run-tx (when (seq run-changes) (entity-changes run-ref run-changes))
          read-snapshot (fn [db]
                          [(d/pull db run-snapshot-pattern run-ref)
                           (releasable-artifacts db run-id retained)])
          [[before released] report]
          (transact-checked!
           conn
           read-snapshot
           (fn [[_ released]]
             (into (vec run-tx)
                   (map (fn [artifact] [:db.fn/retractEntity [:artifact/id (:artifact/id artifact)]]))
                   released)))]
      {:run                (entity-from-report before :run/id run-attributes report)
       :released-artifacts (mapv artifact-entity->map released)}))

  (get-task-output [_ task-id]
    (let [output (:task/output (d/pull (d/db conn) '[{:task/output [:artifact/id :artifact/kind]}] [:task/id task-id]))]
      (when-let [ref (output-entity->ref output)]
        {:output/kind (:artifact/kind output) :output/ref ref})))

  (get-artifact [_ artifact-id]
    (artifact-entity->map (d/pull (d/db conn) artifact-pull-pattern [:artifact/id artifact-id])))

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
