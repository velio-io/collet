(ns collet.workbench.authoring
  "Shared browser/MCP services. Durable text is independent of prepared execution contexts."
  (:require [clojure.string :as str]
            [collet.core :as collet]
            [collet.store :as store]
            [collet.workbench :as wb]
            [collet.workbench.data :as data]
            [collet.workbench.diagnostics :as diagnostics]
            [collet.workbench.example :as example]
            [collet.workbench.fixtures :as fixtures]
            [collet.workbench.inspection :as inspection]
            [collet.workbench.knowledge :as knowledge]
            [collet.workbench.wire :as wire])
  (:import [java.net URLEncoder]
           [java.nio.charset StandardCharsets]
           [java.util UUID]
           [java.util.concurrent ConcurrentHashMap ExecutorService Semaphore]
           [java.util.function Function]))


(defn- id! [id]
  (try (UUID/fromString (str id)) (catch Exception _ (wb/fail! "Invalid draft identifier." 400))))


(defn- db [app]
  (:store (:ctx app)))


(defn- raw! [app id]
  (or (store/get-draft (db app) (id! id))
      (get-in @(:draft-runtime app) [(id! id) :preview])
      (wb/fail! "Draft not found." 404)))


(defn- checked! [app id revision]
  (let [draft (raw! app id)]
    (when-not (= revision (:draft/revision draft))
      (throw (ex-info "Draft changed. Compare your text with the current revision."
                      {:status 409 :code "draft-conflict" :current draft})))
    draft))


(defn- runtime [app id]
  (let [id (id! id)]
    (get (swap! (:draft-runtime app)
           #(if (contains? % id)
              %
              (assoc % id {:config {} :config-edn "{}\n" :config-revision 0 :configured? false :gate (Semaphore. 1)})))
         id)))


(defn- persist-preview! [app id author]
  (when-let [draft (get-in @(:draft-runtime app) [(id! id) :preview])]
    (let [stored (store/create-draft! (db app) (assoc draft :author (or author "browser")))]
      (swap! (:draft-runtime app) update (id! id) dissoc :preview)
      stored)))


(defn- source-title [source]
  (try (some-> (data/read-edn source)
               :name
               data/label)
       (catch Exception _ nil)))


(defn- link [app id]
  (str @(:base-url app) "/#draft=" id))


(defn- execution-link [app id snapshot {:keys [scope task-name index pipeline version]}]
  (let [encode #(URLEncoder/encode (str %) StandardCharsets/UTF_8)]
    (if (#{"pipeline" "pipeline-fixture"} scope)
      (str @(:base-url app) "/#pipeline=" (encode pipeline) "&version=" version)
      (let [action (when (and (#{"action" "prefix"} scope) (some? index))
                     (try
                       (let [task (some #(when (= (keyword task-name) (:name %)) %)
                                        (:tasks (data/read-edn (:source snapshot))))]
                         (some-> (nth (vec (flatten (:actions task))) index nil)
                                 :name
                                 data/label))
                       (catch Exception _ nil)))]
        (str (link app id)
             "&view="
             (if action "action" "canvas")
             "&task="
             (encode task-name)
             (when action (str "&action=" (encode action))))))))


(declare configuration-status)


(defn view
  ([app id]
   (view app id true))
  ([app id include-config?]
   (view app id include-config? (raw! app id)))
  ([app id include-config? draft]
   (view app id include-config? draft {}))
  ([app id include-config? draft {:keys [overview?] :as options}]
   (let [r        (runtime app id)
         session  (when (contains? @(:sessions app) (id! id)) (wb/session-view app id options))
         raw      (:draft/source draft)
         document (try (let [document (data/document (data/read-edn raw) {:overview? true})]
                         (swap! (:draft-runtime app) update (id! id) assoc :last-document document)
                         document)
                       (catch Exception _ (or (:last-document r) (:document session) {:name "untitled" :tasks []})))
         fresh?   (and (= (:draft/revision draft) (:applied-source r))
                       (= (:config-revision r) (:applied-config r)))
         change   (dissoc (store/get-draft (db app) (id! id) (:draft/revision draft)) :draft.edit/source)]
     (cond-> (merge (configuration-status raw r)
                    {:id                           (str id)
                     :preview                      (boolean (:preview r))
                     :revision                     (:draft/revision draft)
                     :sourceRevision               (:draft/revision draft)
                     :configurationRevision        (:config-revision r)
                     :scratchRevision              (:revision session)
                     :instanceId                   (:instance-id app)
                     :appliedRevision              (:applied-source r)
                     :appliedConfigurationRevision (:applied-config r)
                     :currentResults               fresh?
                     :document                     (cond-> (assoc document
                                                             :version (or (when (= raw (:saved-source r)) (:saved-version r))
                                                                          (when (:preview r) (get-in draft [:draft/origin :version]))))
                                                     (not overview?) (assoc :edn raw))
                     :origin                       (:draft/origin draft)
                     :updatedAt                    (:draft/updated-at draft)
                     :tasks                        (if fresh?
                                                     (or (:tasks session) {})
                                                     (into {} (map (fn [[name entry]] [name (assoc entry :status "stale")])) (:tasks session)))
                     :events                       (or (:events session) [])
                     :lastChange                   change
                     :url                          (link app id)
                     :configuration                (mapv (fn [[key value]]
                                                           {:key       (str key)
                                                            :type      (some-> value
                                                                               class
                                                                               .getSimpleName)
                                                            :available (some? value)})
                                                         (:config r))})
       include-config? (assoc :configEdn (:config-edn r))))))


(defn browser-view [app id {:keys [overview?] :as options}]
  (view app id (not overview?) (raw! app id) options))


(defn browser-text [app id area]
  (let [draft (raw! app id)
        r     (runtime app id)]
    (case area
      "source" {:revision (:draft/revision draft) :edn (:draft/source draft)}
      "configuration" {:configurationRevision (:config-revision r)
                       :configEdn             (:config-edn r)})))


(defn summary
  "Small shared mutation receipt. Does not construct graphs or inspect results."
  ([app id]
   (summary app id (raw! app id)))
  ([app id draft]
   (let [r      (runtime app id)
         change (store/get-draft (db app) (id! id) (:draft/revision draft))]
     (merge (configuration-status (:draft/source draft) r)
            {:id                    (str id)
             :revision              (:draft/revision draft)
             :sourceRevision        (:draft/revision draft)
             :configurationRevision (:config-revision r)
             :scratchRevision       (some-> (get @(:sessions app) (id! id))
                                            deref
                                            :revision)
             :instanceId            (:instance-id app)
             :url                   (link app id)
             :name                  (:draft/title draft)
             :updatedAt             (:draft/updated-at draft)
             :currentResults        (and (= (:draft/revision draft) (:applied-source r))
                                         (= (:config-revision r) (:applied-config r)))
             :summary               (:draft.edit/summary change)}))))


(defn- reply
  ([app id request]
   (reply app id request (raw! app id)))
  ([app id request draft]
   (cond
     (= :summary (:response request)) (summary app id draft)
     (= "overview" (:view request)) (view app id false draft {:overview? true :task-name (:task-name request)})
     :else (view app id true draft))))


(defn read-draft [app id {:keys [revision view task-name index fromRevision]}]
  (let [draft   (raw! app id)
        rev     (or revision (:draft/revision draft))
        edit    (when (not= rev (:draft/revision draft))
                  (or (store/get-draft (db app) (id! id) rev) (wb/fail! "Draft revision not found." 404)))
        source  (if edit (:draft.edit/source edit) (:draft/source draft))
        receipt (assoc (summary app id)
                  :revision rev
                  :sourceRevision rev
                  :currentRevision (:draft/revision draft))]
    (case (or view "source")
      "summary" receipt
      "source" (assoc receipt :edn source)
      "diff" (let [before (or fromRevision (max 0 (dec rev)))
                   old    (store/get-draft (db app) (id! id) before)]
               (when-not old (wb/fail! "Draft revision not found." 404))
               (assoc receipt
                 :fromRevision before
                 :beforeEdn (:draft.edit/source old)
                 :edn source))
      (let [spec     (data/read-edn source)
            task     (some #(when (= (keyword task-name) (:name %)) %) (:tasks spec))
            fragment (case view
                       "task" task
                       "action" (get (vec (flatten (:actions task))) index))]
        (when-not fragment (wb/fail! "Selected task or action not found." 404))
        (assoc receipt :edn (data/source fragment))))))


(defn list-drafts [app limit]
  {:instanceId (:instance-id app)
   :drafts     (mapv (fn [draft]
                       {:id        (str (:draft/id draft))
                        :name      (:draft/title draft)
                        :revision  (:draft/revision draft)
                        :updatedAt (:draft/updated-at draft)
                        :origin    (:draft/origin draft)
                        :url       (link app (:draft/id draft))})
                     (store/list-drafts (db app) (or limit 100)))})


(defn create! [app {:keys [edn configEdn origin author preview] :as request}]
  (let [edn        (or edn
                       (when (and preview (:name origin) (:version origin))
                         (wb/definition-source app (:name origin) (:version origin))))
        config     (if configEdn (data/read-edn configEdn) (if edn {} example/config))
        _ (when-not (map? config) (wb/fail! "Configuration must be an EDN map." 400))
        source     (or edn (data/source example/pipeline))
        _ (when (and preview (not (and (string? source) (<= (count source) 262144))))
            (wb/fail! "Draft source must be text smaller than 256 KiB." 400))
        definition (cond-> {:draft/source source
                            :draft/title  (or (source-title source) "Untitled draft")
                            :author       (or author "browser")}
                     origin (assoc :draft/origin origin))
        draft      (if preview
                     (assoc definition
                       :draft/id (random-uuid)
                       :draft/revision 0
                       :draft/updated-at (System/currentTimeMillis))
                     (store/create-draft! (db app) definition))
        id         (:draft/id draft)]
    (runtime app id)
    (swap! (:draft-runtime app) update
      id
      merge
      (cond-> {:config config :config-edn (or configEdn (data/source config)) :configured? true}
        preview (assoc :preview draft)))
    (wb/emit! app {:kind "draft" :session-id (str id)})
    (reply app id request draft)))


(defn configuration! [app id {:keys [configEdn configurationRevision] :as request}]
  (raw! app id)
  (let [config (data/read-edn configEdn)
        before (runtime app id)]
    (when-not (map? config) (wb/fail! "Configuration must be an EDN map." 400))
    (swap! (:draft-runtime app)
      (fn [states]
        (let [r (get states (id! id))]
          (when-not (= configurationRevision (:config-revision r))
            (throw (ex-info "Configuration changed. Reload before replacing it."
                            {:status 409 :code "configuration-conflict" :configurationRevision (:config-revision r)})))
          (assoc states
            (id! id) (assoc r
                       :config config
                       :config-edn configEdn
                       :configured? true
                       :config-revision (if (= configEdn (:config-edn r))
                                          (:config-revision r)
                                          (inc (:config-revision r))))))))
    (when (not= configEdn (:config-edn before)) (persist-preview! app id "browser"))
    (wb/emit! app {:kind "draft" :session-id (str id)})
    (reply app id request)))


(defn update! [app id {:keys [revision edn op author summary] :as request}]
  (when (= (some? edn) (some? op)) (wb/fail! "Provide either source EDN or one structural operation." 400))
  (let [draft       (checked! app id revision)
        source      (if op (data/source (data/edit (data/read-edn (:draft/source draft)) request)) edn)
        description (cond
                      (:restoredFrom request) (str "Restored source revision " (:restoredFrom request))
                      op (str op
                              " "
                              (or (:task request)
                                  (when (:taskEdn request) (:name (data/read-edn (:taskEdn request))))
                                  (str (:source request) " → " (:target request))))
                      (= source (:draft/source draft)) "Source unchanged"
                      :else (str "Replaced source (" (count (:draft/source draft)) " → " (count source) " characters)"))
        accepted    (if (and (= source (:draft/source draft)) (:preview (runtime app id)))
                      draft
                      (let [before  (or (persist-preview! app id author) draft)
                            title   (source-title source)
                            changes (store/update-draft! (db app)
                                                         (id! id)
                                                         revision
                                                         source
                                                         (or author "browser")
                                                         description
                                                         (cond-> {:draft.edit/description summary :expected-draft before}
                                                           title (assoc :draft/title title)))]
                        (merge before changes)))]
    (wb/emit! app {:kind "draft" :session-id (str id) :author (or author "browser")})
    (reply app id request accepted)))


(defn history [app id limit]
  (raw! app id)
  {:revisions (store/draft-history (db app) (id! id) (or limit 100))})


(defn revision [app id revision]
  (raw! app id)
  (or (store/get-draft (db app) (id! id) revision) (wb/fail! "Draft revision not found." 404)))


(defn restore! [app id {:keys [fromRevision] :as request}]
  (update! app
           id
           (assoc request
             :edn (:draft.edit/source (revision app id fromRevision))
             :restoredFrom fromRevision
             :summary (str "Restored source revision " fromRevision))))


(defn delete! [app id expected]
  (let [gate ^Semaphore (:gate (runtime app id))]
    (when-not (.tryAcquire gate) (wb/fail! "Draft execution is active." 409))
    (try
      (store/delete-draft! (db app) (id! id) expected)
      (swap! (:draft-runtime app) dissoc (id! id))
      (when (contains? @(:sessions app) (id! id)) (wb/delete-session! app id))
      (wb/emit! app {:kind "draft" :session-id (str id) :deleted true})
      {:deleted true}
      (finally (.release gate)))))


(defn- snapshot! [app id revision config-revision]
  (let [draft (checked! app id revision)
        r     (runtime app id)]
    (when-not (= config-revision (:config-revision r))
      (wb/fail! "Configuration changed. Reload before execution." 409))
    (when-not (:configured? r) (wb/fail! "Supply configuration before execution, including an empty map when no configuration is needed." 409))
    {:source                (:draft/source draft)
     :sourceRevision        revision
     :configurationRevision config-revision
     :config                (:config r)}))


(defn- prepare-snapshot! [app id snapshot]
  (wb/prepare-session! app
                       id
                       (:source snapshot)
                       (:config snapshot)
                       {:source-revision (:sourceRevision snapshot) :config-revision (:configurationRevision snapshot)})
  (swap! (:draft-runtime app) update
    (id! id)
    assoc
    :applied-source (:sourceRevision snapshot)
    :applied-config (:configurationRevision snapshot)))


(defn prepare! [app id {:keys [revision configurationRevision] :as request}]
  (let [snapshot (snapshot! app id revision configurationRevision)
        gate     ^Semaphore (:gate (runtime app id))]
    (when-not (.tryAcquire gate) (wb/fail! "Draft execution is active." 409))
    (try (prepare-snapshot! app id snapshot)
         (reply app id request)
         (finally (.release gate)))))


(defn save! [app id {:keys [revision] :as request}]
  (let [draft  (checked! app id revision)
        source (:draft/source draft)
        gate   ^Semaphore (:gate (runtime app id))]
    (when-not (.tryAcquire gate) (wb/fail! "Draft execution is active." 409))
    (try
      (let [r       (runtime app id)
            spec    (data/read-edn source)
            origin  (:draft/origin draft)
            version (or (when (= source (:saved-source r)) (:saved-version r))
                        (:version spec)
                        (when (= (data/label (:name spec)) (:name origin)) (:version origin)))
            saved   (wb/save-plan! app (data/compile-plan (cond-> spec version (assoc :version version)) source) true)]
        (swap! (:draft-runtime app) update (id! id) assoc :saved-source source :saved-version (:version saved))
        (wb/emit! app {:kind "draft" :session-id (str id)})
        (assoc (reply app id request draft)
          :savedVersion (:version saved)
          :savedPipeline {:name (data/label (:name saved)) :version (:version saved)}
          :savedRevision revision
          :configurationSource (when (:configured? (runtime app id))
                                 {:source "draft" :id (str id) :revision (:config-revision (runtime app id))})))
      (finally (.release gate)))))


(defn- configuration-snapshot! [app configuration]
  (case (:source configuration)
    "empty" {:config {} :configurationRevision 0 :configurationSource {:source "empty"}}
    "draft" (let [id (:id configuration)
                  _ (raw! app id)
                  r  (runtime app id)]
              (when-not (and (:configured? r) (= (:revision configuration) (:config-revision r)))
                (throw (ex-info "Supply the current draft configuration revision before starting this version."
                                {:status 409 :code "configuration-conflict" :configurationRevision (:config-revision r)})))
              {:config (:config r) :configurationRevision (:config-revision r) :configurationSource configuration})
    (throw (ex-info "Select a draft configuration at an exact revision, or explicitly choose empty configuration."
                    {:status 400
                     :code   "configuration-required"
                     :hint   "Use configuration {source: draft, id: DRAFT_ID, revision: N} or {source: empty}."}))))


(defn- check-configuration! [spec config {:keys [scope task-name index]}]
  (let [config      (collet/prepare-config! spec config)
        descriptors (into {} (map (juxt :type identity)) (knowledge/actions))
        missing     (for [[ti task]   (map-indexed vector (:tasks spec))
                          :when       (or (= scope "pipeline") (= (keyword task-name) (:name task)))
                          group       [:setup :actions]
                          [ai action] (map-indexed vector (flatten (get task group)))
                          ;; Conditions may depend on earlier actions; only execution can decide whether inputs are needed.
                          :when       (nil? (:when action))
                          :when       (case scope
                                        ("pipeline" "task") true
                                        "prefix" (or (= group :setup) (<= ai index))
                                        "action" (if (= group :setup) (zero? index) (= ai index)))
                          :let        [schema (:params-schema (get descriptors (:type action)))
                                       schema (if (= :schema (first schema)) (last schema) schema)]
                          :when       (= :map (first schema))
                          field       (rest schema)
                          :when       (and (vector? field) (not (:optional (second field))))
                          :let        [parameter (get (:params action) (first field))
                                       selector  (when (symbol? parameter) (get (:selectors action) parameter))]
                          :when       (and (vector? selector)
                                           (= :config (first selector))
                                           (every? #(or (keyword? %) (string? %) (nat-int? %)) (rest selector))
                                           (= ::missing (get-in config (rest selector) ::missing)))]
                      {:code    "missing-configuration"
                       :path    [:tasks ti group ai :params (first field)]
                       :message (str "Configuration selector " (pr-str selector) " is unavailable.")})]
    (when (seq missing)
      (throw (ex-info "Required configuration inputs are missing. Supply configuration before executing."
                      {:code   "missing-configuration"
                       :status 409
                       :errors (vec missing)
                       :hint   "Use collet_set_configuration or select the intended draft configuration."})))))


(defn- configuration-status [source {:keys [configured? config]}]
  (let [base {:configurationProvided (boolean configured?) :configurationReady false}]
    (if-not configured?
      (assoc base
        :configurationDiagnostics [{:code    "configuration-required"
                                    :path    [:config]
                                    :message "Supply the configuration map."}])
      (try
        (let [spec (data/read-edn source)]
          (when-not (and (map? spec) (vector? (:tasks spec)))
            (throw (ex-info "Invalid pipeline source." {})))
          (check-configuration! spec config {:scope "pipeline"})
          (assoc base
            :configurationReady true
            :configurationDiagnostics []))
        (catch Exception e
          (assoc base
            :configurationDiagnostics
            (if-let [errors (:errors (ex-data e))]
              (mapv #(select-keys % [:code :path :message]) (take 8 errors))
              [{:code    "configuration-check-unavailable"
                :path    (or (:path (ex-data e)) [:config])
                :message "Check the pipeline source and configuration schema."}])))))))


(defn- execution-summary [app job]
  (let [run    (when (:runId job) (inspection/read-run app (:runId job) {}))
        status (or (:status run) (:status job))
        result (select-keys job
                            [:id :scope :draftId :sourceRevision :configurationRevision :configurationSource
                             :pipeline :version :url :task :phase :error :createdAt :startedAt :finishedAt :preparationMs :runId
                             :executionMode :fixtureId :fixtureRevision :mockedActions :assertionsPassed
                             :taskCounts :cases :caseCount :passedCount])]
    (cond-> (assoc result
              :status status
              :phase (if (#{"completed" "failed" "stopped"} status) "finished" (:phase job))
              :sequence (diagnostics/sequence-number app (remove nil? [(:id job) (:runId job)]))
              :elapsedMs (- (or (:finishedAt run) (:finishedAt job) (System/currentTimeMillis)) (:createdAt job)))
      (and (:fixtureId job) (some? (:sourceRevision job)))
      (assoc :fresh
        (try
          (and (= (:sourceRevision job) (:draft/revision (raw! app (:draftId job))))
               (= (:fixtureRevision job)
                  (:fixtureRevision (fixtures/get-case app (:draftId job) (:fixtureId job)))))
          (catch Exception _ false)))
      run (merge (select-keys run [:status :pipeline :version :url :error :failedTask :taskCounts :finishedAt])))))


(defn get-execution
  ([app id]
   (get-execution app id {}))
  ([app id {:keys [view waitMs afterSequence task-name action-name] :as options}]
   (let [deadline (+ (System/currentTimeMillis) (or waitMs 0))
         summary  (loop []
                    (let [signal    @(:diagnostic-signal app)
                          job       (or (get @(:executions app) (str id))
                                        (throw (ex-info "Execution is unavailable in this app instance. Inspect durable runs before explicitly starting again."
                                                        {:status 404 :code "execution-unavailable"})))
                          value     (execution-summary app job)
                          remaining (- deadline (System/currentTimeMillis))]
                      (if (and (pos? remaining)
                               (not (#{"completed" "failed" "stopped"} (:status value)))
                               (or (nil? afterSequence) (<= (:sequence value) afterSequence)))
                        (do (deref signal remaining nil) (if (> deadline (System/currentTimeMillis)) (recur) (execution-summary app (get @(:executions app) (str id)))))
                        value)))
         job      (get @(:executions app) (str id))
         groups   (remove nil? [id (:runId job)])]
     (case (or view "summary")
       "summary" summary
       "events" (merge summary (diagnostics/events app groups options))
       "metrics" (assoc summary
                   :metrics
                   (merge (if (:runId job)
                            (:metrics (inspection/read-run app (:runId job) (assoc options :view "metrics")))
                            (diagnostics/metrics app groups options))
                          {:preparationMs (:preparationMs job) :elapsedMs (:elapsedMs summary)}))
       "result" (cond
                  (= "fixture-suite" (:scope job)) summary
                  (= "pipeline-fixture" (:scope job))
                  (let [result (:result job)
                        entry  (get-in result
                                       [:taskResults
                                        (some-> task-name
                                                keyword)])
                        sample (:preview entry)]
                    (merge summary
                           (select-keys result [:outputDirectory :files :assertions])
                           (when task-name
                             {:task           task-name
                              :available      (boolean sample)
                              :sample         (inspection/selected-sample sample options)
                              :representation (:representation sample)})))
                  (:runId job)
                  (merge summary (dissoc (inspection/read-run app (:runId job) options) :id :sequence))
                  :else
                  (let [task    (or task-name (:task job))
                        _ (when (and task-name (not= task-name (:task job))) (wb/fail! "This execution belongs to another task." 404))
                        entry   (get-in job [:result :result])
                        action  (when action-name
                                  (or (get-in entry [:actions (keyword action-name)])
                                      (inspection/action-observation app nil id task action-name)))
                        preview (if action-name (:preview action) (or (:handoff-preview entry) (:preview entry)))]
                    (assoc (merge summary (select-keys (:result job) [:outputDirectory :files :assertions]))
                      :task task
                      :action action-name
                      :available (boolean preview)
                      :sample (inspection/selected-sample preview options)
                      :representation (:representation preview)
                      :producingSourceRevision (or (:sourceRevision action) (:source-revision entry) (:sourceRevision job))
                      :producingConfigurationRevision (or (:configurationRevision action) (:config-revision entry) (:configurationRevision job))
                      :reason (when-not preview "Result is not available yet, or this action sample was not retained."))))))))


(defn- execution-event! [app id phase]
  (diagnostics/observe! app {:execution-id id :event "execution-phase" :phase phase :level "info"}))


(defn- fixture-snapshot [app {:keys [id fixtureId fixtureRevision scope pipeline version revision]}]
  (let [fixture (fixtures/get-case app id fixtureId)]
    (when-not (= fixtureRevision (:fixtureRevision fixture))
      (throw (ex-info "Fixture changed. Reload it before running." {:status 409 :code "fixture-conflict"})))
    (if (= scope "pipeline-fixture")
      {:fixture fixture
       :plan    (or (store/load-pipeline (db app) (keyword pipeline) version)
                    (wb/fail! "Pipeline revision not found." 404))}
      {:fixture fixture :source (:draft/source (checked! app id revision))})))


(defn execute! [app id {:keys [instanceId idempotencyKey revision configurationRevision scope version pipeline configuration fixtureId fixtureRevision] :as command}]
  (when-not (= instanceId (:instance-id app))
    (throw (ex-info "Application instance changed. Inspect runs before explicitly starting again." {:status 409 :code "stale-instance"})))
  (when-not (and (string? idempotencyKey) (<= 1 (count idempotencyKey) 200)) (wb/fail! "Provide an idempotencyKey of 1–200 characters." 400))
  (when @(:closed? app) (wb/fail! "Application is shutting down." 503))
  (when-not (contains? #{"action" "prefix" "task" "pipeline" "pipeline-fixture"} scope) (wb/fail! "Unknown execution scope." 400))
  (when (and (or fixtureId fixtureRevision)
             (not (and (not= scope "pipeline") (string? fixtureId) (nat-int? fixtureRevision))))
    (wb/fail! "Fixture execution requires an exact fixture ID and revision." 400))
  (when-not (#{"pipeline" "pipeline-fixture"} scope)
    (when-not (and (string? id)
                   (nat-int? revision)
                   (or fixtureId (nat-int? configurationRevision))
                   (string? (:task-name command))
                   (or (= scope "task") (nat-int? (:index command))))
      (wb/fail! "Authoring execution requires a draft, revisions, task and action index for action/prefix." 400)))
  (when (and (#{"pipeline" "pipeline-fixture"} scope) (not (and (string? pipeline) (pos-int? version))))
    (wb/fail! "Choose a pipeline name and saved version." 400))
  (when (and (= scope "pipeline-fixture") (not (and id fixtureId (nat-int? fixtureRevision))))
    (wb/fail! "Supply the fixture's draft ID and exact fixture revision." 400))
  (let [signature (assoc (dissoc command ::fixture-snapshot) :draft id)
        reservation
        (.computeIfAbsent
         ^ConcurrentHashMap (:execution-keys app)
         idempotencyKey
         (reify
          Function
          (apply [_ _]
            (let [plan         (when (= scope "pipeline")
                                 (or (store/load-pipeline (db app) (keyword pipeline) version) (wb/fail! "Pipeline revision not found." 404)))
                  fixture-run  (when fixtureId (or (::fixture-snapshot command) (fixture-snapshot app (assoc command :id id))))
                  fixture      (:fixture fixture-run)
                  snapshot     (cond plan (configuration-snapshot! app configuration)
                                     fixture fixture-run
                                     :else (snapshot! app id revision configurationRevision))
                  gate         ^Semaphore (when-not (or plan fixture) (:gate (runtime app id)))
                  execution-id (str (random-uuid))
                  settled      (promise)
                  now          (System/currentTimeMillis)]
              (when (and gate (not (.tryAcquire gate)))
                (throw (ex-info "Draft execution is active." {:status 409 :code "execution-active"})))
              (swap! (:executions app) assoc
                execution-id
                {:id                    execution-id
                 :settled               settled
                 :url                   (execution-link app id snapshot command)
                 :status                "preparing"
                 :phase                 "preparing"
                 :createdAt             now
                 :startedAt             now
                 :draftId               id
                 :sourceRevision        revision
                 :configurationRevision (:configurationRevision snapshot)
                 :configurationSource   (when-not fixture (or (:configurationSource snapshot) {:source "draft" :id id :revision configurationRevision}))
                 :executionMode         (if fixture "fixture" "live")
                 :fixtureId             fixtureId
                 :fixtureRevision       fixtureRevision
                 :scope                 scope
                 :pipeline              pipeline
                 :version               version
                 :task                  (:task-name command)})
              (execution-event! app execution-id "preparing")
              (.submit
               ^ExecutorService (:authoring-executor app)
               ^Runnable
               (fn []
                 (try
                   (if fixture
                     (let [result (fixtures/execute app
                                                    (or (:plan snapshot) (data/validate (data/read-edn (:source snapshot))))
                                                    fixture
                                                    (assoc command
                                                      :execution-id execution-id
                                                      :return-failure? true))]
                       (swap! (:executions app) update
                         execution-id
                         merge
                         (merge (select-keys result [:status :error :taskCounts :assertionsPassed :mockedActions])
                                {:phase "finished" :finishedAt (System/currentTimeMillis) :result result})))
                     (do
                       (check-configuration! (if plan (data/authoring-spec plan) (data/read-edn (:source snapshot))) (:config snapshot) command)
                       (let [run (if plan
                                   (wb/start-saved-run! app pipeline version (:config snapshot))
                                   (do (prepare-snapshot! app id snapshot) nil))]
                         (swap! (:executions app) update
                           execution-id
                           merge
                           (cond-> {:status "running" :phase "executing" :preparationMs (- (System/currentTimeMillis) now)}
                             run (assoc :runId (:id run)))))
                       (execution-event! app execution-id "executing")
                       (when-not plan
                         (let [result  (wb/execute! app
                                                    id
                                                    (assoc command
                                                      :mode scope
                                                      :revision nil
                                                      :execution-id execution-id
                                                      :response :snapshot))
                               failed? (= :failed (get-in result [:result :status]))]
                           (swap! (:executions app) update
                             execution-id
                             merge
                             (cond-> {:status     (if failed? "failed" "completed")
                                      :phase      "finished"
                                      :finishedAt (System/currentTimeMillis)
                                      :result     result}
                               failed? (assoc :error
                                         (let [error   (get-in result [:result :error])
                                               details (update-keys (:details error) keyword)
                                               code    (some-> (:collet.error/type details)
                                                               (str/replace-first #"^:" ""))
                                               arrow?  (= code "collet.error/arrow-invalid-value")
                                               safe    (if arrow? (select-keys details [:path :expected :actual-class :reason :hint]) {})]
                                           (assoc (wire/run-error
                                                   (merge safe
                                                          {:message (or (last (:causes error)) (:message error) "Task execution failed.")
                                                           :task    (:task-name command)
                                                           :action  (some-> (:action details)
                                                                            (str/replace-first #"^:" ""))}
                                                          (when arrow? {:code code}))
                                                   "execution-failed")
                                             :causes (mapv #(wire/clip % 200) (take 6 (:causes error))))))))))))
                   (catch Throwable e
                     (swap! (:executions app) update
                       execution-id
                       merge
                       {:status     "failed"
                        :phase      "finished"
                        :finishedAt (System/currentTimeMillis)
                        :error      (:error (wire/error e))}))
                   (finally
                    (try
                      (when gate (.release gate))
                      (execution-event! app execution-id "updated")
                      (wb/emit! app {:kind "execution" :session-id id :execution-id execution-id})
                      (finally (deliver settled true)))))))
              {:signature signature :id execution-id}))))]
    (when-not (= signature (:signature reservation))
      (throw (ex-info "Idempotency key was already used for different arguments." {:status 409 :code "idempotency-conflict"})))
    (get-execution app (:id reservation))))


(defn run-fixture-suite! [app {:keys [instanceId idempotencyKey cases] :as request}]
  (when-not (= instanceId (:instance-id app))
    (throw (ex-info "Application instance changed. Read the current instance ID." {:status 409 :code "stale-instance"})))
  (when @(:closed? app) (wb/fail! "Application is shutting down." 503))
  (let [{:keys [id signature]}
        (.computeIfAbsent
         ^ConcurrentHashMap (:execution-keys app)
         idempotencyKey
         (reify
          Function
          (apply [_ _]
            (let [snapshots (mapv #(fixture-snapshot app %) cases)
                  id        (str (random-uuid))
                  now       (System/currentTimeMillis)]
              (swap! (:executions app) assoc
                id
                {:id            id
                 :scope         "fixture-suite"
                 :executionMode "fixture"
                 :status        "running"
                 :phase         "executing"
                 :createdAt     now
                 :startedAt     now
                 :caseCount     (count cases)
                 :passedCount   0
                 :cases         []})
              (.submit ^ExecutorService (:authoring-executor app)
                       ^Runnable
                       (fn []
                         (try
                           (doseq [[index command snapshot] (map vector (range) cases snapshots)]
                             (let [child  (execute! app
                                                    (:id command)
                                                    (assoc command
                                                      :instanceId instanceId
                                                      :idempotencyKey (str (random-uuid))
                                                      ::fixture-snapshot snapshot))
                                   _ @(get-in @(:executions app) [(:id child) :settled])
                                   done   (get-execution app (:id child))
                                   passed (true? (:assertionsPassed done))]
                               (swap! (:executions app) update
                                 id
                                 (fn [job]
                                   (cond-> (update job
                                                   :cases
                                                   conj
                                                   {:index index :executionId (:id child) :status (:status done) :passed passed})
                                     passed (update :passedCount inc))))
                               (execution-event! app id "fixture-case-finished")))
                           (swap! (:executions app) update
                             id
                             (fn [job] (assoc job :status (if (= (:caseCount job) (:passedCount job)) "completed" "failed"))))
                           (catch Exception e
                             (swap! (:executions app) update id merge {:status "failed" :error (:error (wire/error e))}))
                           (finally
                            (swap! (:executions app) update id assoc :phase "finished" :finishedAt (System/currentTimeMillis))
                            (execution-event! app id "updated")))))
              {:id id :signature request}))))]
    (when-not (= signature request)
      (throw (ex-info "Idempotency key was already used for different arguments." {:status 409 :code "idempotency-conflict"})))
    (get-execution app id)))


(defn- observed-fields [area name preview fresh?]
  (let [base         [area name]
        display-base (mapv data/label base)
        row-context? (= "mapper-row" (:selectorContext preview))]
    (into [{:path       display-base
            :selector   (pr-str base)
            :executable (boolean fresh?)
            :fresh      (boolean fresh?)
            :type       (:representation preview)}]
          (map (fn [{:keys [path selector]}]
                 (cond-> {:path       (into display-base path)
                          :fresh      (boolean fresh?)
                          :type       (some-> (get-in (:value preview) path)
                                              class
                                              .getSimpleName)
                          :executable (boolean (and fresh? (not row-context?)))}
                   (not row-context?) (assoc :selector (str "[" (str/join " " (map pr-str base)) " " (subs selector 1)))
                   row-context? (assoc :rowSelector
                                  selector :rowContext
                                  "Inside a mapper action; the task input itself is a dataset or dataset batch sequence."))))
          (:selectors preview))))


(defn- cached-value [preview limit]
  (when preview
    (let [value (try (data/read-edn (:edn preview)) (catch Exception _ (:value preview)))]
      (if (vector? value) (vec (take limit value)) value))))


(defn inspect-context [app id {:keys [task-name index limit] :or {index 0 limit 20}}]
  (let [v            (summary app id)
        r            (runtime app id)
        s            (some-> (get @(:sessions app) (id! id))
                             deref)
        spec         (or (try (data/read-edn (:draft/source (raw! app id))) (catch Exception _ nil)) (:spec s))
        task         (some #(when (= (keyword task-name) (:name %)) %) (:tasks spec))
        _ (when-not task (wb/fail! "Task not found." 404))
        prepared?    (:currentResults v)
        entry        (get-in s [:tasks (:name task)])
        usable?      (and prepared? (not= :stale (:status entry)))
        setup-fresh? (and usable? (some? (:setup-context entry)))
        upstream     (mapv (fn [name]
                             (let [entry (get-in s [:tasks name])]
                               {:task                  (data/label name)
                                :sourceRevision        (:source-revision entry)
                                :configurationRevision (:config-revision entry)
                                :fresh                 (and prepared? (= :completed (:status entry)))
                                :representation        (get-in entry [:handoff-preview :representation])
                                :sample                (wire/sample (or (:handoff-preview entry) (:preview entry)) {:limit limit})}))
                           (:inputs task))
        previous     (take index (flatten (:actions task)))
        state        (into {}
                           (keep (fn [action]
                                   (when-let [preview (get-in s [:tasks (:name task) :actions (:name action) :preview])]
                                     [(:name action) (cached-value preview limit)])))
                           previous)
        setup        (get-in s [:tasks (:name task) :setup-preview])
        context      {:inputs (into {}
                                    (keep (fn [source]
                                            (let [name    (keyword (:task source))
                                                  entry   (get-in s [:tasks name])
                                                  preview (or (:handoff-preview entry) (:preview entry))]
                                              (when preview [name (cached-value preview limit)]))))
                                    upstream)
                      :state  (merge (cached-value setup limit) state)}
        preview      (data/observation-preview context)
        fields       (vec
                      (concat
                       (mapcat (fn [source]
                                 (let [entry (get-in s [:tasks (keyword (:task source))])]
                                   (observed-fields :inputs
                                                    (keyword (:task source))
                                                    (or (:handoff-preview entry) (:preview entry))
                                                    (:fresh source))))
                        upstream)
                       (map (fn [{:keys [path selector]}]
                              {:path       (into ["state"] path)
                               :selector   (str "[:state " (subs selector 1))
                               :fresh      (boolean setup-fresh?)
                               :executable (boolean setup-fresh?)})
                            (:selectors setup))
                       (mapcat (fn [action]
                                 (let [observation (get-in entry [:actions (:name action)])]
                                   (when-let [p (:preview observation)]
                                     (observed-fields :state
                                                      (:name action)
                                                      p
                                                      (and usable? (some? (get (:steps entry) (:index observation))))))))
                        previous)))
        fresh?       (and prepared? (every? :fresh upstream) (every? :fresh fields))
        preview      (assoc preview :selectors (filterv :executable fields))
        missing      (mapv #(str "Run upstream task " (:task %) " first; its input is missing or stale.") (remove :fresh upstream))]
    {:id                       (str id)
     :instanceId               (:instance-id app)
     :revision                 (:revision v)
     :sourceRevision           (:revision v)
     :scratchRevision          (:revision s)
     :appliedRevision          (:applied-source r)
     :configurationRevision    (:config-revision r)
     :fresh                    fresh?
     :preview                  preview
     :fields                   fields
     :sources                  upstream
     :notices                  (cond-> missing
                                 (and (seq (:setup task)) (not setup-fresh?)) (conj "Task setup is missing or stale; Run the first action or Run to here.")
                                 (not fresh?) (conj "Previous context: execution prepares the selected revision before reusing any values.")
                                 (> index (count (get-in s [:tasks (:name task) :steps]))) (conj "Run earlier actions first, or use Run to here.")
                                 (or (:iterator task) (:parallel task)) (conj "Run this iterator/parallel task as a whole task."))
     :configurationProvided    (:configurationProvided v)
     :configurationReady       (:configurationReady v)
     :configurationDiagnostics (:configurationDiagnostics v)
     :configuration            (mapv (fn [[key value]] {:key (str key) :type (if (nil? value) "nil" (.getSimpleName (class value))) :available true}) (:config r))}))


(defn close-scratch! [app id]
  (let [gate ^Semaphore (:gate (runtime app id))]
    (when (.tryAcquire gate)
      (try
        (when (contains? @(:sessions app) (id! id)) (wb/delete-session! app id))
        (swap! (:draft-runtime app)
          #(if (get-in % [(id! id) :preview])
             (dissoc % (id! id))
             (update % (id! id) dissoc :applied-source :applied-config)))
        (finally (.release gate))))
    {:closed true}))


(defn inspect! [app {:keys [session-id task-name action-name view] :as request}]
  (if (and session-id action-name (= view "Inputs"))
    (let [s     (some-> (get @(:sessions app) (id! session-id))
                        deref)
          raw   (try (data/read-edn (:draft/source (raw! app session-id))) (catch Exception _ (:spec s)))
          task  (some #(when (= (keyword task-name) (:name %)) %) (:tasks raw))
          index (first (keep-indexed #(when (= (keyword action-name) (:name %2)) %1) (flatten (:actions task))))]
      (if (and s task index (not (:iterator task)) (not (:parallel task)))
        (wb/inspect! app (assoc request :value (:context (wb/action-input-context s task index))))
        (wb/inspect! app request)))
    (wb/inspect! app request)))
