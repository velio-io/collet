(ns collet.workbench.fixtures
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [collet.core :as collet]
            [collet.runtime :as runtime]
            [collet.store :as store]
            [collet.stream :as stream]
            [collet.utils :as utils]
            [collet.workbench.data :as data]
            [collet.workbench.execution :as execution]
            [collet.workbench.fixture-io :as fixture-io]
            [collet.workbench.knowledge :as knowledge]
            [collet.workbench.wire :as wire]
            [com.brunobonacci.mulog :as ml]
            [malli.core :as m]
            [tech.v3.dataset :as ds])
  (:import [java.io ByteArrayInputStream]
           [java.nio.file Files StandardCopyOption]
           [java.nio.file.attribute FileAttribute]
           [java.util Base64 UUID]))


(defn- fail! [status code message]
  (throw (ex-info message {:status status :code code})))


(defn- uuid! [id]
  (try (UUID/fromString (str id))
       (catch Exception _ (fail! 400 "invalid-fixture-id" "Expected a fixture or draft UUID."))))


(defn- directory [app draft-id]
  (let [id (uuid! draft-id)]
    (when-not (store/get-draft (:store (:ctx app)) id)
      (fail! 404 "draft-not-found" "Fixture cases belong to a saved draft."))
    (io/file (str (:artifact-dir (:ctx app))) ".." "fixtures" (str id))))


(defn- case-file [app draft-id fixture-id]
  (io/file (directory app draft-id) (str (uuid! fixture-id) ".edn")))


(defn get-case [app draft-id fixture-id]
  (let [file (case-file app draft-id fixture-id)]
    (when-not (.isFile file) (fail! 404 "fixture-not-found" "Fixture case not found."))
    (edn/read-string (slurp file))))


(defn list-cases [app draft-id]
  (let [dir (directory app draft-id)]
    {:fixtures (->> (.listFiles dir)
                    (filter #(and (.isFile %)
                                  (str/ends-with? (.getName %) ".edn")
                                  (not (str/starts-with? (.getName %) "."))))
                    (map #(select-keys (edn/read-string (slurp %)) [:fixtureId :fixtureRevision :name]))
                    (sort-by (juxt :name :fixtureId))
                    vec)}))


(def ^:private response-schema
  (let [fields [:map {:closed true}
                [:status [:int {:min 100 :max 599}]]
                [:headers {:optional true} [:map-of [:or :string :keyword] [:or :string [:vector :string]]]]]]
    [:or (conj fields [:json :any])
     (conj fields [:body {:optional true} :string])]))


(def ^:private route-schema
  [:map {:closed true}
   [:request
    [:map {:closed true}
     [:method {:optional true} [:or :keyword :string]]
     [:url
      [:and :string
       [:fn
        (fn [url]
          (try (some? (.getHost (java.net.URI. url)))
               (catch Exception _ false)))]]]
     [:query-params {:optional true} [:map-of [:or :keyword :string] :any]]]]
   [:responses [:vector {:min 1} response-schema]]])


(def ^:private fixture-schema
  (m/schema
   [:map {:closed true}
    [:config {:optional true} [:maybe :map]]
    [:inputs {:optional true} [:maybe :map]]
    [:state {:optional true} [:maybe :map]]
    [:bindings {:optional true} [:maybe :map]]
    [:allow {:optional true} [:maybe [:set :keyword]]]
    [:mocks {:optional true}
     [:maybe
      [:map-of :keyword
       [:or [:map {:closed true} [:value :any]]
        [:map {:closed true} [:responses [:vector :any]]]]]]]
    [:http {:optional true} [:map-of :keyword [:vector {:min 1} route-schema]]]
    [:temp-files {:optional true} [:set :keyword]]
    [:expect {:optional true}
     [:map {:closed true}
      [:status {:optional true} [:enum :completed :failed]]
      [:error-code {:optional true} :string]
      [:rows {:optional true} [:map-of :keyword [:vector {:max 100} :map]]]
      [:csv {:optional true}
       [:map-of [:fn fixture-io/filename?]
        [:map {:closed true}
         [:header [:vector :string]]
         [:rows [:vector {:max 100} [:vector :string]]]]]]]]]))


(defn- invalid-fixture! [path expected message]
  (throw (ex-info "Fixture EDN has an invalid field."
                  {:status 400
                   :code   "invalid-fixture"
                   :path   path
                   :hint   message
                   :errors [{:code "invalid-fixture-field" :path path :expected expected :message message}]})))


(defn- fixture-data [text]
  (let [fixture (data/read-edn text)]
    (when-let [{:keys [in schema type]} (first (:errors (m/explain fixture-schema fixture)))]
      (let [expected (case (first in)
                       :allow "a set of action keywords such as #{:report-fields}"
                       :temp-files "a set of file sink action keywords such as #{:write-report}"
                       (if (= :malli.core/extra-key type) "a documented field" (pr-str (m/form schema))))]
        (invalid-fixture! in expected (str "Expected " expected " at " (pr-str in) "."))))
    (doseq [[action routes] (:http fixture)]
      (when-not (apply distinct? (map (comp fixture-io/request-key :request) routes))
        (invalid-fixture! [:http action] "one route per request" "Combine replies for the same HTTP request in its :responses vector.")))
    (when-let [action (some #(when (or (contains? (:http fixture) %) (contains? (:temp-files fixture) %)) %)
                            (keys (:mocks fixture)))]
      (invalid-fixture! [:mocks action] "one substitution per action" "Choose a result mock, HTTP route or temporary file sink for this action."))
    fixture))


(defn put-case! [app draft-id {:keys [fixtureId fixtureRevision name edn]}]
  (when-not (and (string? name)
                 (not (str/blank? name))
                 (<= (count name) 120)
                 (string? edn)
                 (<= (count edn) 262144))
    (fail! 400 "invalid-fixture" "Provide a fixture name and at most 256 KiB of EDN."))
  ;; Check for a single data value and reject tagged readers.
  (fixture-data edn)
  (let [id   (str (or fixtureId (random-uuid)))
        file (case-file app draft-id id)]
    (locking (:executions app)
      (let [revision (if (.isFile file)
                       (let [prior (:fixtureRevision (get-case app draft-id id))]
                         (when-not (= prior fixtureRevision)
                           (fail! 409 "fixture-conflict" "Fixture changed. Reload it before saving."))
                         (inc prior))
                       (if (nil? fixtureId)
                         0
                         (fail! 404 "fixture-not-found" "Fixture case not found.")))
            saved    {:fixtureId id :fixtureRevision revision :name name :edn edn}
            dir      (.toPath (.getParentFile file))]
        (Files/createDirectories dir (make-array FileAttribute 0))
        (let [temp (Files/createTempFile dir ".fixture-" ".tmp" (make-array FileAttribute 0))]
          (try
            (spit (.toFile temp) (pr-str saved))
            (Files/move temp
                        (.toPath file)
                        (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
            (finally (Files/deleteIfExists temp))))
        (dissoc saved :edn)))))


(def ^:private pure-actions
  #{:mapper :fold :counter :stats :slicer :switch :enrich
    :clj/identity :clj/str :clj/get :clj/get-in :clj/assoc :clj/dissoc :clj/merge
    :clj/hash-map :clj/vector :clj/select-keys :clj/count :clj/conj
    :clj/+ :clj/- :clj/* :clj// :clj/inc :clj/dec})


(defn- mock-value [value]
  (cond
    (and (map? value) (contains? value :collet.fixture/body-base64))
    (-> value
        (assoc :body (ByteArrayInputStream. (.decode (Base64/getDecoder) ^String (:collet.fixture/body-base64 value))))
        (dissoc :collet.fixture/body-base64))

    (and (map? value) (contains? value :collet.fixture/body-dataset-batches))
    (-> value
        (assoc :body (with-meta (mapv ds/->dataset (:collet.fixture/body-dataset-batches value)) {:ds-seq true}))
        (dissoc :collet.fixture/body-dataset-batches))

    :else value))


(defn- prepare-action [{:keys [mocks allow temp-files]} used counters {:keys [routes requests files directory]}]
  (let [external (into #{:clj/slurp :clj/spit}
                       (comp (remove #(= "collet-core" (:package %)))
                             (remove #(str/starts-with? (:effects %) "pure"))
                             (remove #(= :collet.actions.file/inspect-image (:type %)))
                             (map :type))
                       (knowledge/actions))]
    (fn [{:keys [name type params] :as action}]
      (let [reviewed?          (contains? allow name)
            executable-params? (some list? (tree-seq coll? seq params))]
        (cond
          (contains? mocks name)
          (let [mock (get mocks name)]
            (assoc action
              :type :custom
              :collet/sensitive? true
              :params (when (or reviewed? (not executable-params?)) params)
              :fn (fn [& _]
                    (swap! used conj name)
                    (mock-value
                     (if (contains? mock :value)
                       (:value mock)
                       (let [index (dec (get (swap! counters update name (fnil inc 0)) name))]
                         (if (< index (count (:responses mock)))
                           (nth (:responses mock) index)
                           (fail! 400 "fixture-exhausted" (str "Fixture responses exhausted for " name ".")))))))))

          (and (= type :collet.actions.http/request) (contains? routes name))
          (assoc action
            :type :custom
            :collet/sensitive? true
            :fn (fn [params]
                  (swap! used conj name)
                  (fixture-io/request! routes requests name params)))

          (and (= type :collet.actions.file/sink) (contains? temp-files name))
          (assoc action
            :type :custom
            :fn (fn [params] (fixture-io/write! directory files name params)))

          (and reviewed? (= type :collet.actions.file/decode-base64))
          (assoc action
            :type :custom
            :fn (requiring-resolve 'collet.actions.file/decode-base64))

          (and reviewed? (= type :collet.actions.file/inspect-image))
          (assoc action
            :type :custom
            :fn (fn [{:keys [input] :as params}]
                  (when-not (or (bytes? input) (instance? ByteArrayInputStream input))
                    (fail! 400 "fixture-image-input" "Provide synthetic image bytes or a fixture response stream."))
                  ((requiring-resolve 'collet.actions.file/inspect-image) params)))

          (and (not (external type))
               (or reviewed? (and (pure-actions type) (not executable-params?))))
          action

          :else
          (assoc action
            :type :custom
            :params nil
            :fn (fn [] (fail! 400 "fixture-mock-required" (str "Supply a fixture mock for " name ".")))))))))


(defn- handoff [app spec handles task value]
  (let [output (runtime/scratch-output! (:scratch-dir app) spec (assoc task :keep-state true) value)]
    (swap! handles conj output)
    {:value (runtime/read-scratch-output output) :representation (:representation output)}))


(defn- review-task! [task {:keys [mocks allow http]} full-task?]
  (when (and full-task? (:parallel task))
    (let [actions (tree-seq map? #(concat (:setup %) (:actions %) (:case %)) task)
          names   (into #{}
                        (mapcat (fn [{:keys [name type]}]
                                  (cond-> [name] (= :enrich type) (conj (keyword (str (clojure.core/name name) "-action"))))))
                        actions)]
      (when (some #(or (get-in mocks [% :responses]) (contains? http %)) names)
        (fail! 400 "parallel-fixture-sequence" "Use repeatable fixture values in a parallel task."))))
  (when (and (some? (:return task)) (not (vector? (:return task))) (not (contains? allow :task-return)))
    (fail! 400 "fixture-approval-required" "Add :task-return to :allow to execute the task return function.")))


(defn- row-check! [checks fixture task value]
  (when-let [expected (get-in fixture [:expect :rows task])]
    (swap! checks assoc task (fixture-io/rows-match? value expected))))


(defn- execute-case [app spec {:keys [config inputs state bindings allow mocks] :as fixture}
                     {:keys [scope task-name index execution-id]} prepare checks]
  (let [{:keys [deps]} spec
        task           (or (collet/find-task spec (keyword task-name))
                           (fail! 404 "task-not-found" "Fixture task not found."))
        handles        (atom [])
        action-preview (atom {})
        ;; Replace file-backed actions before plan expansion can open their source.
        block-files    (fn block [a]
                         (cond
                           (= :switch (:type a)) (update a :case #(mapv (fn [branch] (update branch :actions (partial mapv block))) %))
                           (and (keyword? (:type a)) (str/ends-with? (name (:type a)) ".edn"))
                           (if (contains? mocks (:name a))
                             (assoc a :type :custom)
                             (fail! 400 "fixture-mock-required" (str "Supply a fixture mock for " (:name a) ".")))
                           :else a))
        task           (cond-> (update task :actions (partial mapv block-files))
                         (:setup task) (update :setup (partial mapv block-files)))]
    (try
      (review-task! task fixture (= scope "task"))
      (when (seq allow)
        (when (seq (:requires deps)) (apply require (:requires deps))))
      (let [eval-context (if (seq allow) (utils/eval-ctx (:requires deps) (:imports deps)) (utils/eval-ctx))
            context      {:config (collet/config-with-defaults spec (or config {}))
                          :inputs (into {}
                                        (map (fn [name]
                                               (when-not (contains? inputs name)
                                                 (fail! 400 "fixture-input-required" (str "Supply fixture input " name ".")))
                                               [name
                                                (:value (handoff app
                                                                 spec
                                                                 handles
                                                                 (dissoc (collet/find-task spec name) :state-format)
                                                                 (get inputs name)))]))
                                        (:inputs task))
                          :state  (merge state bindings)}
            sample!      (fn [{:keys [action result]}]
                           (swap! action-preview assoc action {:preview (data/observation-preview result)}))
            prepared     (collet/prepare-task-plan task)
            result       (ml/with-context {:execution-id execution-id :task (:name task) :workbench/instance (:instance-id app)}
                                          (if (= scope "task")
                                            (let [{:keys [value representation]} (handoff app
                                                                                          spec
                                                                                          handles
                                                                                          {:name (:name task) :spec prepared :state-format (:state-format prepared)}
                                                                                          ((:task-fn (execution/task eval-context prepared prepare sample!)) context))]
                                              (row-check! checks fixture (:name task) value)
                                              (assoc (data/preview value) :representation representation))
                                            (let [actions  (:actions prepared)
                                                  selected (or (get actions index)
                                                               (fail! 400 "invalid-fixture-action" "Fixture action index is out of range."))
                                                  steps    (if (= scope "action")
                                                             [selected]
                                                             (concat (:setup prepared) (take (inc index) actions)))
                                                  output   (collet/execute-actions (execution/steps eval-context steps prepare sample!) context)
                                                  value    (get-in output [:state (:name selected)])]
                                              (row-check! checks fixture (:name task) value)
                                              (data/preview value))))]
        {:status "completed" :result {:status :completed :preview result :actions @action-preview}})
      (finally (doseq [handle (reverse @handles)] (runtime/close-scratch-output! handle))))))


(defn- execute-pipeline [app plan {:keys [config] :as fixture} execution-id prepare checks]
  (when (some seq [(:inputs fixture) (:state fixture) (:bindings fixture)])
    (fail! 400 "fixture-pipeline-inputs" "A full pipeline fixture uses actual task handoffs. Remove :inputs, :state and :bindings."))
  (doseq [task (:tasks plan)] (review-task! task fixture true))
  (let [dir      (Files/createTempDirectory (:scratch-dir app) "fixture-runtime-" (make-array FileAttribute 0))
        context  (atom nil)
        results  (atom {})
        complete (fn [{:task/keys [name run output]}]
                   (let [ctx   @context
                         value (if output
                                 (collet/read-output ctx output)
                                 (get-in @(:results (get @(:runs ctx) run)) [name :output/value]))]
                     (try
                       (row-check! checks fixture name value)
                       (swap! results assoc name {:preview (data/preview value)})
                       (finally
                        (when-let [close (:collet/close (meta value))] (close))))))
        ctx      (collet/context {:data-dir         (str dir)
                                  :on-task-complete complete})]
    (reset! context ctx)
    (try
      (let [run    (execution/start ctx
                                    plan
                                    (or config {})
                                    {:substitute           prepare
                                     :resolve-coordinates? false
                                     :trace                {:execution-id execution-id :workbench/instance (:instance-id app)}})
            state  @run
            status (:run/status state)
            tasks  (store/get-task-runs (:store ctx) (:run/id run))]
        (cond-> {:status      (if (= :done status) "completed" (data/label status))
                 :taskCounts  (update-keys (frequencies (map :task/status tasks)) data/label)
                 :taskResults @results}
          (:run/error state)
          (assoc :error (wire/run-error (:run/error state) "execution-failed"))))
      (finally
       (collet/close ctx)
       (doseq [file (reverse (file-seq (.toFile dir)))] (.delete ^java.io.File file))))))


(defn- assertions [fixture outcome checks directory]
  (let [{:keys [status error-code rows csv] :or {status :completed}} (:expect fixture)]
    (into [(cond-> {:kind "status" :passed (= (name status) (:status outcome))}
             (not= (name status) (:status outcome)) (assoc :expected (name status)))]
          (concat (when error-code
                    [{:kind     "error-code"
                      :passed   (= error-code (get-in outcome [:error :code]))
                      :expected error-code}])
                  (for [task (sort (keys rows))]
                    {:kind "rows" :task (data/label task) :passed (true? (get @checks task))})
                  (for [[file expected] (sort-by key csv)]
                    {:kind "csv" :file file :passed (boolean (fixture-io/csv-match? directory file expected))})))))


(defn execute [app spec saved {:keys [scope execution-id return-failure?] :as options}]
  (stream/with-resources
   (let [fixture   (fixture-data (:edn saved))
         used      (atom #{})
         checks    (atom {})
         files     (atom {})
         requests  (atom {})
         routes    (fixture-io/routes (:http fixture))
         directory (when (seq (:temp-files fixture))
                     (Files/createTempDirectory (:scratch-dir app) "fixture-files-" (make-array FileAttribute 0)))
         prepare   (prepare-action fixture used (atom {}) {:routes routes :requests requests :files files :directory directory})
         outcome   (try
                     (let [result (if (= "pipeline-fixture" scope)
                                    (execute-pipeline app spec fixture execution-id prepare checks)
                                    (execute-case app spec fixture options prepare checks))]
                       (if-let [unused (when (= "completed" (:status result)) (fixture-io/unused-response routes requests))]
                         (assoc result
                           :status "failed"
                           :error unused)
                         result))
                     (catch Exception e
                       (if return-failure?
                         {:status "failed" :error (:error (wire/error e))}
                         (throw e))))
         checked   (assertions fixture outcome checks directory)]
     (cond-> (assoc outcome
               :mockedActions (mapv data/label (sort @used))
               :assertions checked
               :assertionsPassed (every? :passed checked))
       directory (assoc :outputDirectory
                   (str directory)
                   :files
                   (vec (sort-by :name (vals @files))))))))
