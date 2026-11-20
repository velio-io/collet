(ns portable.runner
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [collet.core :as collet]
            [collet.store :as store]
            [collet.workbench.knowledge :as knowledge]
            [tech.v3.dataset :as ds]))


(defn check [expected actual context]
  (when-not (= expected actual)
    (throw (ex-info (str "Standalone portability assertion failed: " (pr-str {:expected expected :actual actual :context context})) {:expected expected :actual actual :context context}))))


(defn rows [value]
  (cond
    (ds/dataset? value) (vec (ds/rows value))
    (and (sequential? value) (ds/dataset? (first value))) (vec (mapcat ds/rows value))
    :else value))


(defn documentation-text [id section]
  (loop [cursor nil
         text   ""]
    (let [page (knowledge/documentation (cond-> {:id id :section section :full true :maxBytes 32768}
                                          cursor (assoc :cursor cursor)))
          text (str text (:text page))]
      (if-let [cursor (:nextCursor page)]
        (recur cursor text)
        text))))


(defn run-case! [ctx {:keys [spec config task expected retries repeats] :or {config {} repeats 1}}]
  (dotimes [_ repeats]
    (let [loaded (collet/load-pipeline ctx (:name spec))
          run    @(collet/start ctx loaded config)
          tasks  (store/get-task-runs (:store ctx) (:run/id run))
          output (some #(when (= task (:task/name %)) %) tasks)]
      (check :done (:run/status run) run)
      (let [value (rows (collet/read-output ctx (:task/output output)))]
        (check expected (if (set? expected) (set value) value) (:name spec)))
      (when retries
        (check retries (:task/retry-count (some #(when (= :enrich-customers (:task/name %)) %) tasks)) :execution-local-retries)))))


(let [[mode input] *command-line-args*
      {:keys [jar data-dir cases negative manifest documents]} (edn/read-string (slurp input))]
  (check jar (System/getProperty "java.class.path") :packaged-classpath)
  (check nil (io/resource "collet/authoring_test.clj") :no-development-tests)
  (check nil (io/resource "collet/actions/http.clj") :optional-action-not-bundled)
  (check nil (io/resource "collet/actions/s3.clj") :optional-s3-action-not-bundled)
  (check nil (io/resource "collet/actions/file.clj") :optional-file-action-not-bundled)
  (check (:packages manifest) (:packages (#'knowledge/compatibility)) :prepared-packaged-parity)
  (doseq [[[id section] text] documents]
    (check text (documentation-text id section) [id section :packaged-documentation]))
  (let [ctx (collet/context {:data-dir data-dir})]
    (try
      (case mode
        "missing"
        (let [result (try
                       @(collet/start ctx (collet/compile-pipeline (update (:spec negative) :deps dissoc :coordinates)) (:config negative))
                       :unexpected-success
                       (catch Exception e (ex-message e)))]
          (when-not (and (string? result) (re-find #"external dependency|Unknown action" result))
            (throw (ex-info "Missing coordinates incorrectly passed portability check" {:result result}))))

        "persist"
        (doseq [{:keys [spec]} cases]
          (store/save-pipeline! (:store ctx) {} (collet/compile-pipeline spec)))

        "run"
        (do
          (doseq [test-case cases] (run-case! ctx test-case))
          (let [repository (.toPath (.getCanonicalFile (io/file (System/getProperty "user.home") ".m2/repository/io/velio")))]
            (doseq [path ["collet/actions/http.clj" "collet/actions/s3.clj" "collet/actions/file.clj"]
                    :let [resource (io/resource path)]]
              (check "jar"
                     (some-> resource
                             .getProtocol)
                     [path :packaged-action])
              (let [jar (.toPath (.getCanonicalFile (io/file (.toURI (.getJarFileURL (.openConnection resource))))))]
                (check true (.startsWith jar repository) [path :isolated-maven-repository]))))))
      (println "PORTABILITY_OK" mode)
      (finally (collet/close ctx) (shutdown-agents)))))
