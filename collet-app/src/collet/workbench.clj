(ns collet.workbench
  (:refer-clojure :exclude [descendants])
  (:require [clojure.set :as set]
            [collet.core :as collet]
            [collet.runtime :as runtime]
            [collet.store :as store]
            [collet.utils :as utils]
            [collet.workbench.data :as data]
            [collet.workbench.diagnostics :as diagnostics]
            [collet.workbench.events :as events]
            [collet.workbench.example :as example]
            [collet.workbench.execution :as execution]
            [com.brunobonacci.mulog :as ml]
            [portal.api :as portal]
            [portal.runtime :as portal-runtime])
  (:import [java.util UUID]
           [java.util.concurrent Executors ExecutorService ConcurrentHashMap]))


(defn fail! [message status]
  (throw (ex-info message {:status status})))


(defn- uuid [value]
  (try (UUID/fromString (str value))
       (catch Exception _ (fail! "Invalid identifier." 400))))


(defn emit! [app event]
  (when-not @(:closed? app)
    (swap! (:events app)
      (fn [{:keys [sequence items]}]
        (let [n (inc sequence)]
          {:sequence n
           :items    (vec (take-last 500
                                     (conj items
                                           (assoc event
                                             :sequence n
                                             :timestamp (System/currentTimeMillis)))))})))))


(defn- release-outputs! [before after]
  (doseq [[name entry] (:tasks before)
          :let         [output (:output-handle entry)]
          :when        (and output (not (identical? output (get-in after [:tasks name :output-handle]))))]
    (runtime/close-scratch-output! output)))


(defn- change-session! [a f & args]
  (let [[before after] (apply swap-vals! a f args)]
    (release-outputs! before after)
    after))


(defn- capture! [app {:keys [result params] :as event}]
  (when-not @(:closed? app)
    (let [preview (data/observation-preview result)
          inputs  (when (contains? event :params) (data/observation-preview params))
          sample  (cond-> (assoc (select-keys event [:session-id :run-id :task :action :execution-id])
                            :result (data/observed-value result)
                            :preview preview)
                    inputs (assoc :params
                             (data/observed-value params) :input-preview
                             inputs))]
      (swap! (:inspect-values app) #(vec (take-last 64 (conj % sample))))
      (emit! app
             (cond-> (assoc (data/safe-value (dissoc event :params :result)) "preview" preview)
               inputs (assoc "inputs" inputs))))))


(defn- publish! [app {:keys [session-id run-id execution-id] :as event}]
  (when (and (= (:instance-id app) (:workbench/instance event)) (not @(:closed? app)))
    (locking (:diagnostics app)
      (let [owned-run?     (contains? @(:runs (:ctx app)) run-id)
            owned-session? (contains? @(:sessions app) session-id)
            fixture?       (and (nil? session-id) (not owned-run?) (contains? @(:executions app) (str execution-id)))]
        (when (or owned-run? owned-session? fixture?)
          (when-let [observation (events/observation event)]
            (diagnostics/observe! app (cond-> observation fixture? (dissoc :run-id)))
            (when (and owned-run? (= :collet/run-state (:mulog/event-name event)))
              (emit! app {:kind "run" :run-id (str run-id) :status (data/label (:status event))})))
          (when-not fixture?
            (emit! app (data/safe-value (dissoc event :workbench/instance :exception)))))))))


(defn open! [options]
  (let [app-ref  (atom nil)
        callback (fn [task]
                   (when-let [app @app-ref]
                     (when (= :completed (:task/status task))
                       (let [value (if (:task/output task)
                                     (collet/read-output (:ctx app) (:task/output task))
                                     (some-> (get @(:runs (:ctx app)) (:task/run task))
                                             :results
                                             deref
                                             (get (:task/name task))
                                             :output/value))]
                         (try
                           (let [sample (if (:task/output task) (data/preview value) (data/observation-preview value))]
                             (swap! (:run-samples app)
                               (fn [samples]
                                 (let [samples (assoc samples [(:task/run task) (:task/name task)] sample)]
                                   ;; Samples are an in-memory convenience; durable run metrics do not depend on them.
                                   (if (> (count samples) 128) (dissoc samples (first (keys samples))) samples)))))
                           (finally (when-let [close (:collet/close (meta value))] (close))))))
                     (diagnostics/observe! app
                                           {:run-id (:task/run task)
                                            :task   (:task/name task)
                                            :event  "task-state"
                                            :level  "info"
                                            :status (data/label (:task/status task))})
                     (emit! app
                            {:kind    "task"
                             :run-id  (str (:task/run task))
                             :task    (data/label (:task/name task))
                             :task-id (str (:task/id task))
                             :status  (data/label (:task/status task))
                             :error   (data/safe-value (:task/error task))})))
        ctx      (collet/context (merge options
                                        {:on-task-start    callback
                                         :on-task-complete callback
                                         :on-task-error    callback
                                         :on-task-skipped  callback}))
        app      {:ctx                ctx
                  :instance-id        (str (random-uuid))
                  :base-url           (atom nil)
                  :draft-runtime      (atom {})
                  :executions         (atom {})
                  :execution-keys     (ConcurrentHashMap.)
                  :authoring-executor (Executors/newVirtualThreadPerTaskExecutor)
                  :sessions           (atom {})
                  :scratch-dir        (java.nio.file.Files/createTempDirectory "collet-authoring-" (make-array java.nio.file.attribute.FileAttribute 0))
                  :handles            (atom {})
                  :run-configs        (atom {})
                  :run-samples        (atom {})
                  :events             (atom {:sequence 0 :items []})
                  :diagnostics        (diagnostics/state)
                  :diagnostic-signal  (atom (promise))
                  :closed?            (atom false)
                  :portal             (atom nil)
                  :inspect-values     (atom [])}]
    (reset! app-ref app)
    (assoc app
      :stop-publisher (ml/start-publisher!
                       {:type         :custom
                        :fqn-function "collet.workbench.events/publisher"
                        :on-events    #(doseq [event %] (publish! app event))}))))


(defn close! [app]
  (when (compare-and-set! (:closed? app) false true)
    ((:stop-publisher app))
    (when-let [p @(:portal app)]
      ;; portal/close waits for a browser connection, including after its tab closes.
      (portal-runtime/close-session (:session-id p))
      (portal/stop)
      (reset! (:portal app) nil))
    (.shutdownNow ^ExecutorService (:authoring-executor app))
    (.awaitTermination ^ExecutorService (:authoring-executor app) 5 java.util.concurrent.TimeUnit/SECONDS)
    (doseq [a (vals @(:sessions app))] (release-outputs! @a {}))
    (reset! (:sessions app) {})
    (doseq [file (reverse (file-seq (.toFile ^java.nio.file.Path (:scratch-dir app))))] (.delete ^java.io.File file))
    (reset! (:inspect-values app) [])
    (reset! (:run-configs app) {})
    (reset! (:run-samples app) {})
    (collet/close (:ctx app))))


(defn- session [app id]
  (or (get @(:sessions app) (uuid id)) (fail! "This working session has expired. Create a new session." 404)))


(defn- task [spec name]
  (or (some #(when (= name (:name %)) %) (:tasks spec)) (fail! "Task not found." 404)))


(defn- descendants [spec roots]
  (loop [affected (set roots)]
    (let [next (into affected (keep #(when (some affected (:inputs %)) (:name %))) (:tasks spec))]
      (if (= affected next) affected (recur next)))))


(defn- stale [entry]
  (when entry
    (-> entry
        (dissoc :context :setup-context :steps :value :output-handle)
        (assoc :status :stale))))


(defn- invalidate [s task-name from]
  (let [dependants (disj (descendants (:spec s) #{task-name}) task-name)]
    (-> s
        (update :tasks (fn [tasks] (reduce #(update %1 %2 stale) tasks dependants)))
        (update-in [:tasks task-name]
                   (fn [entry]
                     (-> entry
                         (dissoc :value :error :preview :inputs :setup-context :setup-preview :output-handle)
                         (assoc :status :stale
                                :context (last (take from (:steps entry)))
                                :steps (vec (take from (:steps entry)))
                                :actions (into {} (filter (fn [[_ x]] (< (:index x) from))) (:actions entry)))))))))


(defn- action-prefix-length [before after old-plan new-plan]
  (if (and (= (dissoc before :actions) (dissoc after :actions))
           (= (dissoc old-plan :actions) (dissoc new-plan :actions))
           (not (:iterator new-plan))
           (not (:parallel new-plan)))
    (count (take-while true? (map = (flatten (:actions old-plan)) (flatten (:actions new-plan)))))
    0))


(defn- retain-action-prefix [entry length]
  (let [steps (vec (take length (:steps entry)))]
    (if (seq steps)
      (-> entry
          ;; Old suffix events must not restore invalidated samples in the UI.
          (dissoc :value :preview :inputs :error :execution-id :output-handle)
          (assoc :status :partial
                 :steps steps
                 :context (peek steps)
                 :actions (into {} (filter (fn [[_ action]] (< (:index action) (count steps)))) (:actions entry))))
      (stale entry))))


(defn- retained-task-names [spec]
  (into (into #{} (comp (filter :keep-state) (map :name)) (:tasks spec))
        (mapcat :inputs)
        (:tasks spec)))


(defn- update-definition [s spec config]
  (let [global-change?    (or (not= config (:config s))
                              (not= (:deps spec) (get-in s [:spec :deps]))
                              (not= (get spec :use-arrow true) (get-in s [:spec :use-arrow] true)))
        before            (into {} (map (juxt :name identity)) (get-in s [:spec :tasks]))
        after             (into {} (map (juxt :name identity)) (:tasks spec))
        retained-before   (retained-task-names (:spec s))
        retained-after    (retained-task-names spec)
        retention-changed (set/union (set/difference retained-before retained-after)
                                     (set/difference retained-after retained-before))
        changed           (set/union retention-changed
                                     (if global-change?
                                       (set (keys before))
                                       (set (for [[name task] before :when (not= task (get after name))] name))))
        affected          (set/union (descendants (:spec s) changed) (descendants spec changed))
        plans             (when (and (seq affected) (not global-change?))
                            ;; An old file-backed action may have been removed while correcting the definition.
                            [(try (collet/compile-pipeline (:spec s)) (catch Exception _ nil))
                             (collet/compile-pipeline spec)])
        old-plans         (into {} (map (juxt :name identity)) (:tasks (first plans)))
        new-plans         (into {} (map (juxt :name identity)) (:tasks (second plans)))]
    (-> s
        (assoc :spec (if (= spec (:spec s)) spec (dissoc spec :version))
               :config config)
        (update :revision inc)
        (update :tasks
                #(reduce (fn [tasks name]
                           (let [old (get before name)
                                 new (get after name)]
                             (if (or global-change? (retention-changed name) (some affected (concat (:inputs old) (:inputs new))))
                               (update tasks name stale)
                               (update tasks name retain-action-prefix (action-prefix-length old new (get old-plans name) (get new-plans name))))))
                         %
                         affected)))))


(defn session-view
  ([app id]
   (session-view app id {}))
  ([app id {:keys [overview? task-name]}]
   (let [s @(session app id)]
     (cond-> {:id       (str (:id s))
              :revision (:revision s)
              :tasks    (into {}
                              (map
                               (fn [[name entry]]
                                 (let [name    (data/label name)
                                       detail? (or (not overview?) (= name task-name))]
                                   [name
                                    (cond-> {:sourceRevision        (:source-revision entry)
                                             :configurationRevision (:config-revision entry)
                                             :status                (some-> (:status entry)
                                                                            data/label)
                                             :through               (dec (count (:steps entry)))
                                             :executionId           (some-> (:execution-id entry)
                                                                            str)
                                             :actions               (into {}
                                                                          (map (fn [[k v]]
                                                                                 [(data/label k)
                                                                                  (if detail? v (select-keys v [:index :status]))]))
                                                                          (:actions entry))}
                                      detail? (assoc :preview
                                                (:preview entry) :inputs
                                                (:inputs entry) :error
                                                (:error entry)))])))
                              (:tasks s))
              :events   (filterv #(and (= (str (:id s)) (get % "session-id"))
                                       (or (not overview?)
                                           (and task-name (= task-name (data/label (get % "task"))))))
                                 (:items @(:events app)))}
       (not overview?) (assoc :document
                         (data/document (:spec s)) :configEdn
                         (data/source (:config s)))))))


(defn create-session! [app {:keys [edn configEdn]}]
  (when (>= (count @(:sessions app)) 20)
    (fail! "Close a working session before opening another (maximum 20)." 409))
  (let [id     (random-uuid)
        spec   (data/validate (if edn (data/read-edn edn) example/pipeline))
        config (if configEdn (data/read-edn configEdn) example/config)]
    (when-not (map? config) (fail! "Configuration must be an EDN map." 400))
    (swap! (:sessions app) assoc id (atom {:id id :revision 0 :spec spec :config config :tasks {}}))
    (session-view app id)))


(defn apply-definition! [app id {:keys [edn configEdn revision op] :as request}]
  (let [a (session app id)]
    (locking a
      (when (and revision (not= revision (:revision @a))) (fail! "Draft changed. Reload before applying this edit." 409))
      (let [base   (if edn (data/read-edn edn) (:spec @a))
            spec   (if (and edn (not (contains? request :op))) (data/validate base) (data/edit base request))
            config (if configEdn (data/read-edn configEdn) (:config @a))]
        (when-not (map? config) (fail! "Configuration must be an EDN map." 400))
        (change-session! a update-definition spec config))))
  (emit! app {:kind "session" :session-id (str id)})
  (session-view app id))


(defn reset-session! [app id]
  (let [a (session app id)]
    (locking a (change-session! a #(-> % (assoc :tasks {}) (update :revision inc)))))
  (swap! (:inspect-values app) #(filterv (fn [v] (not= (uuid id) (:session-id v))) %))
  (swap! (:events app) update :items #(filterv (fn [v] (not= (str id) (or (get v "session-id") (:session-id v)))) %))
  (emit! app {:kind "session" :session-id (str id)})
  (session-view app id))


(defn delete-session! [app id]
  (let [a (session app id)]
    (locking a (release-outputs! @a {}) (swap! (:sessions app) dissoc (uuid id))))
  (swap! (:inspect-values app) #(filterv (fn [v] (not= (uuid id) (:session-id v))) %))
  {:closed true})


(defn- inputs [s t]
  (into {}
        (map (fn [name]
               (let [entry (get-in s [:tasks name])]
                 (when-not (= :completed (:status entry))
                   (fail! (str "Run upstream task " name " first; its result is missing or stale.") 409))
                 [name
                  (if-let [output (:output-handle entry)]
                    (runtime/read-scratch-output output)
                    (:value entry))])))
        (:inputs t)))


(defn action-input-context [s t index]
  (let [actions (vec (flatten (:actions t)))]
    (when-not (and (integer? index) (<= 0 index (dec (count actions))))
      (fail! "Action no longer exists." 409))
    (when (or (:iterator t) (:parallel t))
      (fail! "Run this iterator/parallel task as a whole-task preview." 400))
    (let [entry     (get-in s [:tasks (:name t)])
          entry     (when-not (= :stale (:status entry)) entry)
          steps     (:steps entry)
          through   (min index (count steps))
          context   (if (pos? through) (nth steps (dec through)) (:setup-context entry))
          available (into {}
                          (keep (fn [name]
                                  (let [upstream (get-in s [:tasks name])]
                                    (when (= :completed (:status upstream)) [name (:value upstream)]))))
                          (:inputs t))]
      {:context (merge {:config (:config s) :inputs available :state {}}
                       (select-keys context [:config :state]))
       :notices (cond-> (mapv #(str "Run upstream task " % " first; its result is missing or stale.")
                              (remove #(contains? available %) (:inputs t)))
                  (> index (count steps))
                  (conj (str "Run earlier action " (:name (nth actions (count steps))) " first, or use Run to here."))
                  (and (seq (:setup t)) (nil? (:setup-context entry)))
                  (conj "Task setup has not run. Run the first action or Run to here to make its results available.")
                  (and (zero? index) (seq (:setup t)) (:setup-context entry))
                  (conj "Run action executes task setup again before this action."))})))


(defn action-inputs [app id task-name index]
  (let [s @(session app id)
        {:keys [context notices]} (action-input-context s (task (:spec s) (keyword task-name)) index)]
    {:revision (:revision s)
     :preview  (data/observation-preview context)
     :notices  notices}))


(defn- task-output! [app a task result]
  (let [output (runtime/scratch-output! (:scratch-dir app) (:spec @a) task result)]
    (try
      (let [value   (runtime/read-scratch-output output)
            preview (assoc (data/preview value) :representation (:representation output))]
        (change-session! a
                         update-in
                         [:tasks (:name task)]
                         merge
                         {:value value :output-handle output :handoff-preview preview})
        preview)
      (catch Throwable e (runtime/close-scratch-output! output) (throw e)))))


(defn execute! [app id {:keys [task-name index mode revision execution-id response]}]
  (let [a (session app id)]
    (locking a
      (when (and revision (not= revision (:revision @a))) (fail! "Draft changed. Reload before executing." 409))
      (let [s            @a
            config       (collet/prepare-config! (:spec s) (:config s))
            t            (task (:spec s) (keyword task-name))
            name         (:name t)
            deps         (get-in s [:spec :deps])
            _ (collet/check-dependencies deps [t])
            eval-ctx     (utils/eval-ctx (:requires deps) (:imports deps))
            prepared     (collet/prepare-task-plan t)
            sample!      #(capture! app %)
            actions      (vec (flatten (:actions prepared)))
            index        (or index (dec (count actions)))
            input-values (inputs s t)
            entry        (get-in s [:tasks name])
            execution-id (or execution-id (random-uuid))
            base         {:config config
                          :state  {}
                          :inputs input-values
                          :store  (:store (:ctx app))}
            trace        {:workbench/instance (:instance-id app) :session-id (:id s) :task name :task-id name :execution-id execution-id}
            started      (System/nanoTime)]
        (when-not (contains? #{"action" "prefix" "task"} mode) (fail! "Unknown execution scope." 400))
        (when (and (not= mode "task") (or (:iterator prepared) (:parallel prepared)))
          (fail! "Run this iterator/parallel task as a whole-task preview." 400))
        (when-not (or (= mode "task") (<= 0 index (dec (count actions))))
          (fail! "Action no longer exists." 400))
        (when (and (= mode "action") (> index (count (:steps entry))))
          (fail! "Execute the earlier actions first, or use Run to here." 409))
        (change-session! a #(-> % (invalidate name (if (= mode "action") index 0)) (update :revision inc)))
        (swap! a update-in
          [:tasks name]
          merge
          {:execution-id    execution-id
           :status          :running
           :source-revision (:source-revision s)
           :config-revision (:config-revision s)})
        (emit! app {:kind "session" :session-id (str id) :task (data/label name) :status "running"})
        (try
          (ml/with-context trace
                           (if (= mode "task")
                             (let [compiled (execution/task eval-ctx prepared identity sample!)
                                   result   ((:task-fn compiled) base)
                                   preview  (task-output! app a compiled result)]
                               (swap! a update-in
                                 [:tasks name]
                                 merge
                                 {:status :completed :preview preview :inputs (data/observation-preview input-values) :actions {}}))
                             (let [setup   (if (and (= mode "action") (pos? index))
                                             (:setup-context entry)
                                             (collet/execute-actions
                                              (execution/steps eval-ctx (:setup prepared) identity sample!)
                                              base))
                                   initial (if (and (= mode "action") (pos? index))
                                             (get-in entry [:steps (dec index)])
                                             setup)
                                   indexes (if (= mode "action") [index] (range (inc index)))]
                               (swap! a update-in [:tasks name] merge {:setup-context setup :setup-preview (data/observation-preview (:state setup))})
                               (loop [context   initial
                                      remaining (seq indexes)]
                                 (when-let [i (first remaining)]
                                   (let [action   (nth actions i)
                                         context' (collet/execute-actions (execution/steps eval-ctx [action] identity sample!) context)
                                         value    (get-in context' [:state (:name action)])]
                                     (swap! a (fn [state]
                                                (-> state
                                                    (update-in [:tasks name :steps] (fnil conj []) context')
                                                    (assoc-in [:tasks name :actions (:name action)]
                                                              {:index                 i
                                                               :sourceRevision        (:source-revision s)
                                                               :configurationRevision (:config-revision s)
                                                               :status                "completed"
                                                               :preview               (data/preview value)
                                                               :inputs                (data/preview (select-keys context [:inputs :state]))})
                                                    (assoc-in [:tasks name :context] context'))))
                                     (recur context' (next remaining)))))
                               (let [context   (get-in @a [:tasks name :context])
                                     complete? (= (count actions) (count (get-in @a [:tasks name :steps])))
                                     result    (when complete? ((collet/extract-data-fn eval-ctx prepared) context))]
                                 (swap! a update-in
                                   [:tasks name]
                                   merge
                                   {:status  (if complete? :completed :partial)
                                    :preview (data/preview (get-in context [:state (:name (nth actions index))]))})
                                 (when complete? (task-output! app a (assoc prepared :spec prepared) result))))))
          (swap! a update :revision inc)
          (emit! app
                 {:kind       "session"
                  :session-id (str id)
                  :task       (data/label name)
                  :duration   (/ (- (System/nanoTime) started) 1e6)})
          (catch Throwable error
            (swap! a #(-> %
                          (assoc-in [:tasks name :status] :failed)
                          (assoc-in [:tasks name :error] (data/error-data error))
                          (update :revision inc)))
            (emit! app {:kind "session" :session-id (str id) :task (data/label name) :error (data/error-data error)}))))))
  (if (= response :snapshot)
    (let [entry (get-in @(session app id) [:tasks (keyword task-name)])]
      {:task   task-name
       :result (select-keys entry [:status :preview :handoff-preview :error :source-revision :config-revision :actions])})
    (session-view app id)))


(defn save-plan!
  "Reuse an unchanged origin version; allocate edited content through the Store."
  ([app plan]
   (save-plan! app plan false))
  ([app plan exact-source?]
   (let [db      (:store (:ctx app))
         origin  (when (:version plan) (store/load-pipeline db (:name plan) (:version plan)))
         content (dissoc plan :version)
         ignored (if exact-source? [:version] [:version :collet.workbench/source])]
     (if (and origin (= (apply dissoc content ignored) (apply dissoc origin ignored)))
       origin
       (store/save-pipeline! db {} content)))))


(defn save! [app id]
  (let [a (session app id)]
    (locking a
      (let [plan  (data/compile-plan (:spec @a))
            saved (save-plan! app plan)]
        (swap! a #(-> % (assoc-in [:spec :version] (:version saved)) (update :revision inc))))))
  (session-view app id))


(defn run-view
  ([app id]
   (run-view app id {}))
  ([app id {:keys [overview? task-name]}]
   (let [id    (uuid id)
         ctx   (:ctx app)
         run   (or (store/get-run (:store ctx) id) (fail! "Run not found." 404))
         tasks (store/get-task-runs (:store ctx) id)
         plan  (store/load-pipeline (:store ctx)
                                    (get-in run [:run/pipeline :pipeline/name])
                                    (get-in run [:run/pipeline :pipeline/version]))]
     (cond-> {:id       (str id)
              :run      (data/safe-value run)
              :owned    (contains? @(:handles app) id)
              :document (data/document plan {:overview? overview?})
              :tasks    (mapv #(data/safe-value (if overview? (select-keys % [:task/name :task/status :task/error]) %)) tasks)
              :events   (filterv #(and (= (str id) (or (get % "run-id") (:run-id %)))
                                       (or (not overview?)
                                           (and task-name (= task-name (data/label (get % "task"))))))
                                 (:items @(:events app)))}
       (not overview?) (assoc :configEdn (when-let [config (get @(:run-configs app) id)] (data/source config)))))))


(defn run-text [app id area]
  (let [id  (uuid id)
        db  (:store (:ctx app))
        run (or (store/get-run db id) (fail! "Run not found." 404))
        {:pipeline/keys [name version]} (:run/pipeline run)]
    (case area
      "source" (let [plan (store/load-pipeline db name version)]
                 {:edn (or (:collet.workbench/source plan) (data/source (data/authoring-spec plan)))})
      "configuration" {:configEdn (some-> (get @(:run-configs app) id)
                                          data/source)})))


(defn- start-plan! [app plan config]
  (let [run (execution/start (:ctx app) plan config {:trace {:workbench/instance (:instance-id app)} :sample! #(capture! app %)})
        id  (:run/id run)]
    (swap! (:run-configs app) assoc id config)
    (swap! (:handles app) assoc id run)
    (emit! app {:kind "run" :run-id (str id)})
    (run-view app id)))


(defn start-run! [app id]
  (let [a (session app id)]
    (locking a
      (start-plan! app (data/compile-plan (:spec @a)) (:config @a)))))


(defn start-saved-run!
  ([app name version]
   (start-saved-run! app name version {}))
  ([app name version config]
   (if-let [plan (store/load-pipeline (:store (:ctx app)) (keyword name) (parse-long (str version)))]
     (start-plan! app plan config)
     (fail! "Pipeline revision not found." 404))))


(defn control! [app id command]
  (let [run (or (get @(:handles app) (uuid id)) (fail! "This run belongs to a previous app process; it cannot be resumed here." 409))]
    ((case command
       "pause" collet/pause
       "resume" collet/resume
       "stop" collet/stop
       (fail! "Unknown run command." 400))
     run)
    (emit! app {:kind "run" :run-id (str id)})
    (run-view app id)))


(defn output [app run-id task-name]
  (let [id  (uuid run-id)
        t   (some #(when (= (:task/name %) (keyword task-name)) %)
                  (store/get-task-runs (:store (:ctx app)) id))
        ref (:task/output t)]
    (if (and ref (contains? @(:handles app) id))
      (try {:available true :preview (data/preview (collet/read-output (:ctx app) ref))}
           (catch Exception _ {:available false :reason "Output has been released."}))
      {:available false :reason "Output was not retained, or belongs to a previous app session."})))


(defn selected-value [app {:keys [session-id run-id task-name action-name view]}]
  (let [s           (when session-id @(session app session-id))
        t           (when (and s action-name (= view "Inputs")) (task (:spec s) (keyword task-name)))
        available?  (and t (not (:iterator t)) (not (:parallel t)))
        entry       (get-in s [:tasks (keyword task-name)])
        _ (when (and (not available?) (= :stale (:status entry))) (fail! "This result is stale. Execute the step again." 409))
        observation (when action-name
                      (last (filter #(and (= (keyword task-name) (:task %))
                                          (= (keyword action-name) (:action %))
                                          (if session-id
                                            (and (= (uuid session-id) (:session-id %))
                                                 (= (:execution-id entry) (:execution-id %)))
                                            (= (uuid run-id) (:run-id %))))
                                    @(:inspect-values app))))]
    (cond
      available? (dissoc (:context (action-input-context s
                                                         t
                                                         (first (keep-indexed #(when (= (keyword action-name) (:name %2)) %1)
                                                                              (flatten (:actions t))))))
                  :config)
      (= view "Inputs") (if-let [index (get-in entry [:actions (keyword action-name) :index])]
                          (select-keys (if (zero? index) (:setup-context entry) (get-in entry [:steps (dec index)])) [:inputs :state])
                          (if observation
                            (:params observation)
                            (if session-id
                              (inputs @(session app session-id) (task (:spec @(session app session-id)) (keyword task-name)))
                              (fail! "Inputs are no longer available." 404))))
      (and session-id action-name (contains? (get-in entry [:context :state]) (keyword action-name)))
      (get-in entry [:context :state (keyword action-name)])
      action-name (if observation (:result observation) (fail! "This action value is no longer available in memory." 404))
      session-id (if (contains? entry :value) (:value entry) (fail! "Execute the task before inspecting its output." 409))
      :else (let [t (some #(when (= (:task/name %) (keyword task-name)) %)
                          (store/get-task-runs (:store (:ctx app)) (uuid run-id)))]
              (when-not (:task/output t) (fail! "Output is unavailable." 404))
              (collet/read-output (:ctx app) (:task/output t))))))


(defn inspect! [app request]
  (let [value (if (contains? request :value) (:value request) (selected-value app request))
        p     (portal/open {:value value :window-title "Collet inspector" :launcher false})]
    (when-let [old @(:portal app)] (portal-runtime/close-session (:session-id old)))
    (reset! (:portal app) p)
    {:url (portal/url p)}))


(defn catalog [app]
  {:pipelines (mapv data/safe-value (store/list-pipelines (:store (:ctx app)) 100))
   :runs      (mapv data/safe-value (store/list-runs (:store (:ctx app)) 100))})


(defn- stored-plan [app name version]
  (let [db (:store (:ctx app))]
    (or (if version
          (store/load-pipeline db (keyword name) (parse-long (str version)))
          (store/load-pipeline db (keyword name)))
        (fail! "Pipeline revision not found." 404))))


(defn definition-source [app name version]
  (let [plan (stored-plan app name version)]
    (or (:collet.workbench/source plan) (data/source (data/authoring-spec plan)))))


(defn load-definition [app name version]
  (data/document (stored-plan app name version)))


(defn prepare-session!
  "Prepare an exact authoring snapshot, retaining only proven unchanged prefixes."
  [app id edn config stamps]
  (let [id        (uuid id)
        spec      (data/validate (data/read-edn edn))
        _ (collet/compile-pipeline spec)
        candidate (atom {:id id :revision 0 :spec spec :config config :tasks {}})]
    (swap! (:sessions app) #(if (contains? % id) % (assoc % id candidate)))
    (let [a (session app id)]
      (locking a
        (when (or (not= spec (:spec @a)) (not= config (:config @a)))
          (change-session! a update-definition spec config))
        (swap! a merge stamps)))
    (session-view app id)))
