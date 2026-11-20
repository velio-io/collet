(ns collet.workbench.api
  "Compact browser/MCP read models built from shared authoring services."
  (:require [collet.core :as collet]
            [collet.store :as store]
            [collet.workbench :as wb]
            [collet.workbench.authoring :as authoring]
            [collet.workbench.data :as data]
            [collet.workbench.diagnostics :as diagnostics]
            [collet.workbench.inspection :as inspection]
            [collet.workbench.knowledge :as knowledge]
            [collet.workbench.wire :as wire])
  (:import [java.util UUID]))


(defn- query-options [opts]
  (assoc opts :limit (inc (or (:limit opts) 20))))


(defn drafts [app options]
  (let [rows (store/list-drafts (:store (:ctx app)) (query-options options))]
    (assoc (wire/page :drafts
                      (mapv (fn [d]
                              {:id        (str (:draft/id d))
                               :name      (wire/clip (:draft/title d) 200)
                               :revision  (:draft/revision d)
                               :updatedAt (:draft/updated-at d)
                               :url       (str @(:base-url app) "/#draft=" (:draft/id d))
                               :cursor    (:cursor d)})
                            rows)
                      options)
      :instanceId (:instance-id app))))


(defn pipelines [app options]
  (wire/page :pipelines
             (mapv (fn [p]
                     {:name      (data/label (:pipeline/name p))
                      :version   (:pipeline/version p)
                      :createdAt (:pipeline/created-at p)
                      :cursor    (:cursor p)})
                   (store/list-pipelines (:store (:ctx app)) (query-options options)))
             options))


(defn runs [app options]
  (wire/page :runs
             (mapv (fn [r]
                     {:id        (str (:run/id r))
                      :status    (inspection/run-state (:run/status r))
                      :pipeline  (data/label (get-in r [:run/pipeline :pipeline/name]))
                      :version   (get-in r [:run/pipeline :pipeline/version])
                      :createdAt (:run/created-at r)
                      :cursor    (:cursor r)
                      :url       (str @(:base-url app) "/#run=" (:run/id r))})
                   (store/list-runs (:store (:ctx app)) (query-options options)))
             options))


(defn history [app id options]
  (wire/page :revisions
             (mapv (fn [r]
                     {:revision    (:draft.edit/revision r)
                      :author      (:draft.edit/author r)
                      :summary     (wire/clip (:draft.edit/summary r) 500)
                      :description (some-> (:draft.edit/description r)
                                           (wire/clip 500))
                      :at          (:draft.edit/at r)
                      :cursor      (str (:draft.edit/revision r))})
                   (:revisions (authoring/history app id (query-options options))))
             options))


(defn pipeline [app name version]
  (let [plan (or (store/load-pipeline (:store (:ctx app)) (keyword name) version)
                 (wb/fail! "Pipeline revision not found." 404))]
    {:name    (data/label (:name plan))
     :version (:version plan)
     :edn     (or (:collet.workbench/source plan) (data/source (data/authoring-spec plan)))}))


(defn inspect-context [app id options]
  (let [context (authoring/inspect-context app id options)]
    (-> context
        (dissoc :preview)
        (assoc :sample (inspection/selected-sample (:preview context) options))
        (update :sources #(mapv (fn [source] (dissoc source :sample)) %)))))


(defn delete-pipeline! [app name options]
  (let [result     (collet/delete-pipeline!
                    (:ctx app)
                    (keyword name)
                    (assoc options
                      :busy-run?
                      (fn [run-id]
                        (some (fn [[_ job]]
                                (and (:settled job)
                                     (not (realized? (:settled job)))
                                     (or (= (str run-id) (:runId job))
                                         (and (nil? (:runId job))
                                              (= name (:pipeline job))
                                              (or (nil? (:version options)) (= (:version options) (:version job)))))))
                              @(:executions app)))))
        ids        (set (:run-ids result))
        run-ids    (set (map str ids))
        executions (into #{} (keep (fn [[id job]] (when (contains? run-ids (:runId job)) id))) @(:executions app))]
    (doseq [key [:handles :run-configs]]
      (swap! (get app key) #(apply dissoc % ids)))
    (swap! (:run-samples app) #(into {} (remove (fn [[[run-id _] _]] (contains? ids run-id))) %))
    (swap! (:inspect-values app) #(filterv (fn [entry] (not (contains? ids (:run-id entry)))) %))
    (swap! (:executions app) #(apply dissoc % executions))
    (doseq [entry (.entrySet ^java.util.concurrent.ConcurrentHashMap (:execution-keys app))
            :when (contains? executions (:id (.getValue entry)))]
      (.remove ^java.util.concurrent.ConcurrentHashMap (:execution-keys app) (.getKey entry) (.getValue entry)))
    (diagnostics/forget! app (concat run-ids executions))
    (swap! (:draft-runtime app)
      (fn [states]
        (into {}
              (map (fn [[id state]]
                     [id
                      (if (and (some #{(:saved-version state)} (:versions result))
                               (= (keyword name) (try (:name (data/read-edn (:saved-source state))) (catch Exception _ nil))))
                        (dissoc state :saved-version :saved-source)
                        state)]))
              states)))
    (wb/emit! app {:kind "pipeline" :pipeline name :version (:version options) :deleted true})
    (cond-> {:deleted true :name name :versionsDeleted (count (:versions result)) :runsDeleted (count ids)}
      (:cleanup-warning result) (assoc :cleanupWarning (:cleanup-warning result)))))


(defn overview [app]
  {:instanceId (:instance-id app)
   :version (:version (knowledge/build-info))
   :contractVersion 2
   :protocolVersions ["2025-06-18" "2025-11-25"]
   :workflow ["Prefer catalogue actions, then :clj/* core functions; reserve inline EDN functions for domain logic. Discover and describe exact action types."
              "Core is bundled. Declare pinned optional :coordinates even when classpath-present; :requires loads namespaces and :imports exposes Java classes. Use pinned dependencyEdn recipes."
              "Create or resume a shared draft; edits require its current revision."
              "Check without execution; inspect inputs; explicitly run missing upstream tasks."
              "Split pipelines at dataset handoffs. Use the HTTP catalogue, slicer, mapper and fold before writing wrappers."
              "Run an action, prefix or task with a saved fixture or live configuration. Use pipeline-fixture for an exact saved version or run_fixture_suite for a batch. A passive check cannot verify runtime behavior."
              "Before calling a pipeline done, read the production-checklist guide and report which checks passed, failed, or remain pending. Use an exact saved version for portability and live verification."]
   :example {:createDraft {:edn "{:name :hello :tasks [{:name :hello :actions [{:name :value :type :clj/identity :params [42]}]}]}"}
             :execute     {:scope                 "task"
                           :id                    "<created draft id>"
                           :revision              0
                           :configurationRevision 0
                           :task-name             "hello"
                           :instanceId            (:instance-id app)
                           :idempotencyKey        "<unique request key>"}
             :inspect     {:id "<execution id>" :view "result" :waitMs 10000}}
   :drafts (:drafts (drafts app {:limit 5}))
   :documentation "collet://docs/getting-started"
   :readDocumentation {:id "getting-started"}
   :completionChecklist {:id "production-checklist"}
   :limits {:defaultListItems   20
            :maximumListItems   100
            :defaultSampleRows  20
            :maximumSampleRows  100
            :defaultDetailBytes 16384
            :maximumDetailBytes 65536}
   :lifetime "Draft source/history and saved runs persist. Configuration, scratch results and detailed events end on restart."
   :effects "Run executes real effects. Display limits do not limit data read or written. Repeat a lost start with the same instance, arguments and idempotency key."})
