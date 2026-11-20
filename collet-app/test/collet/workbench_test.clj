(ns collet.workbench-test
  (:require [charred.api :as json]
            [clojure.test :refer [deftest is testing]]
            [collet.action :as action]
            [collet.core :as collet]
            [collet.store :as store]
            [collet.workbench :as wb]
            [collet.workbench.authoring :as authoring]
            [collet.workbench.data :as data]
            [collet.workbench.fixtures :as fixtures]
            [collet.workbench.http :as http]
            [collet.workbench.inspection :as inspection]
            [collet.workbench.wire :as wire]
            [com.brunobonacci.mulog :as ml])
  (:import [java.nio.file Files FileVisitOption]
           [java.nio.file.attribute FileAttribute]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration]))


(defn temp-dir []
  (Files/createTempDirectory "collet-workbench-test-" (make-array FileAttribute 0)))


(defn delete-tree! [path]
  (with-open [paths (Files/walk path (make-array FileVisitOption 0))]
    (doseq [entry (reverse (sort-by str (iterator-seq (.iterator paths))))]
      (Files/deleteIfExists entry))))


(defmacro with-workbench
  [[binding] & body]
  `(let [path#    (temp-dir)
         ~binding (wb/open! {:data-dir (str path#)})]
     (try ~@body (finally (wb/close! ~binding) (delete-tree! path#)))))


(defn eventually [f]
  (loop [n 100]
    (or (f) (when (pos? n) (Thread/sleep 50) (recur (dec n))))))


(def pipeline
  '{:name  :incremental
    :tasks [{:name       :source
             :keep-state true
             :setup      [{:name :base :type :clj/identity :params [4]}]
             :actions    [{:name :first :type :clj/inc :selectors {n [:state :base]} :params [n]}
                          {:name :second :type :clj/* :selectors {n [:state :first]} :params [n 2]}]}
            {:name       :dependent
             :inputs     [:source]
             :keep-state true
             :actions    [{:name :first :type :clj/inc :selectors {n [:inputs :source]} :params [n]}]}]})


(defn session! [app spec]
  (wb/create-session! app {:edn (pr-str spec) :configEdn "{}"}))


(defn execute! [app id task mode & [index]]
  (wb/execute! app id {:task-name task :mode mode :index index}))


(defn result [view task]
  (get-in view [:tasks task :preview :value]))


(def prefix-calls (atom []))


(defn record-prefix-call [step value]
  (swap! prefix-calls conj step)
  value)


(deftest unexecuted-action-inputs-use-retained-prefix
  (with-workbench [app]
                  (let [spec         (update-in pipeline [:tasks 0 :actions] #(vec (take 1 %)))
                        {:keys [id]} (session! app spec)]
                    (execute! app id "source" "action" 0)
                    (wb/apply-definition! app id {:op "add-action" :task "source"})
                    (let [before (wb/session-view app id)
                          view   (wb/action-inputs app id "source" 1)]
                      (is (= {"base" 4 "first" 5} (get-in view [:preview :value "state"])))
                      (is (some #(= "[:state :first]" (:selector %)) (get-in view [:preview :selectors])))
                      (is (empty? (:notices view)))
                      (is (= {:base 4 :first 5}
                             (:state (wb/selected-value app {:session-id id :task-name "source" :action-name "step-1" :view "Inputs"}))))
                      (is (= before (wb/session-view app id)) "Reading inputs never executes an action")))))


(deftest available-inputs-respect-prerequisites-and-action-position
  (with-workbench [app]
                  (let [spec         (update-in pipeline [:tasks 0 :actions] conj '{:name :third :type :clj/identity :selectors {n [:state :second]} :params [n]})
                        {:keys [id]} (wb/create-session! app {:edn (pr-str spec) :configEdn "{:region :EU}"})
                        before       (wb/session-view app id)
                        initial      (wb/action-inputs app id "source" 0)
                        dependent    (wb/action-inputs app id "dependent" 0)]
                    (is (= {"region" ":EU"} (get-in initial [:preview :value "config"])))
                    (is (= {} (get-in initial [:preview :value "state"])))
                    (is (some #(re-find #"setup has not run" %) (:notices initial)))
                    (is (= {} (get-in dependent [:preview :value "inputs"])))
                    (is (some #(re-find #"upstream task :source" %) (:notices dependent)))
                    (is (= before (wb/session-view app id)))
                    (doseq [index [nil -1 3]]
                      (is (thrown-with-msg? Exception #"Action no longer exists" (wb/action-inputs app id "source" index))))
                    (execute! app id "source" "action" 0)
                    (let [later (wb/action-inputs app id "source" 2)]
                      (is (= {"base" 4 "first" 5} (get-in later [:preview :value "state"])))
                      (is (some #(re-find #"earlier action :second" %) (:notices later)))
                      (is (= {"base" 4} (get-in (wb/action-inputs app id "source" 0) [:preview :value "state"]))))
                    (execute! app id "source" "action" 1)
                    (execute! app id "source" "action" 2)
                    (is (= {"base" 4 "first" 5} (get-in (wb/action-inputs app id "source" 1) [:preview :value "state"])))
                    (is (= {"source" 10} (get-in (wb/action-inputs app id "dependent" 0) [:preview :value "inputs"])))
                    (is (nil? (get-in (wb/session-view app id) [:tasks "dependent" :executionId])) "Inspecting an upstream result never executes the dependent task")
                    (wb/apply-definition! app id {:edn (pr-str (assoc-in spec [:tasks 0 :actions 1 :params] '[n 3]))})
                    (is (= {"base" 4 "first" 5} (get-in (wb/action-inputs app id "source" 1) [:preview :value "state"])))
                    (is (= {} (get-in (wb/action-inputs app id "dependent" 0) [:preview :value "inputs"])))
                    (wb/apply-definition! app id {:edn (pr-str (assoc-in spec [:tasks 0 :actions 0 :params] '[40]))})
                    (let [stale (wb/action-inputs app id "source" 2)]
                      (is (= {} (get-in stale [:preview :value "state"])))
                      (is (some #(re-find #"earlier action :first" %) (:notices stale)))
                      (is (= {} (:state (wb/selected-value app {:session-id id :task-name "source" :action-name "third" :view "Inputs"})))))
                    (is (thrown-with-msg? Exception #"stale" (wb/selected-value app {:session-id id :task-name "source" :action-name "first"}))))))


(deftest available-inputs-remain-bounded-and-passive
  (with-workbench [app]
                  (let [rows         (mapv #(hash-map :amount %) (range 150))
                        spec         {:name :input-sample :tasks [{:name :source :actions [{:name :rows :type :clj/identity :params [rows]}]}]}
                        {:keys [id]} (session! app spec)]
                    (execute! app id "source" "action" 0)
                    (wb/apply-definition! app id {:op "add-action" :task "source"})
                    (let [view (wb/action-inputs app id "source" 1)]
                      (is (:truncated (:preview view)))
                      (is (= 100 (count (get-in view [:preview :value "state" "rows"]))))
                      (is (some #(= "[:state :rows 0 :amount]" (:selector %)) (get-in view [:preview :selectors]))))
                    (let [reads (atom 0)
                          value (lazy-seq (swap! reads inc) [{:amount 1}])
                          s     (get @(:sessions app) (java.util.UUID/fromString id))]
                      (swap! s assoc-in [:tasks :source :steps 0 :state :rows] value)
                      (is (:deferred (:preview (wb/action-inputs app id "source" 1))))
                      (is (zero? @reads))))))


(deftest later-action-edits-preserve-completed-prefix
  (reset! prefix-calls [])
  (with-workbench [app]
                  (let [spec         '{:name  :prefix
                                       :tasks [{:name    :source
                                                :setup   [{:name :base :type :custom :fn collet.workbench-test/record-prefix-call :params [:setup 4]}]
                                                :actions [{:name      :first
                                                           :type      :custom
                                                           :fn        collet.workbench-test/record-prefix-call
                                                           :selectors {n [:state :base]}
                                                           :params    [:first n]}]}
                                               {:name    :dependent
                                                :inputs  [:source]
                                                :actions [{:name :value :type :clj/identity :selectors {n [:inputs :source]} :params [n]}]}]}
                        {:keys [id]} (session! app spec)]
                    (is (= 4 (result (execute! app id "source" "action" 0) "source")))
                    (execute! app id "dependent" "task")
                    (let [before (get-in (wb/session-view app id) [:tasks "source" :actions "first"])
                          added  (wb/apply-definition! app id {:op "add-action" :task "source"})]
                      (is (= before (get-in added [:tasks "source" :actions "first"])))
                      (is (= 0 (get-in added [:tasks "source" :through])))
                      (is (= "partial" (get-in added [:tasks "source" :status])))
                      (is (= "stale" (get-in added [:tasks "dependent" :status])))
                      (is (= 4 (wb/selected-value app {:session-id id :task-name "source" :action-name "first"}))))
                    (wb/apply-definition! app
                                          id
                                          {:op        "replace-action"
                                           :task      "source"
                                           :index     1
                                           :actionEdn "{:name :second :type :clj/* :selectors {n [:state :first]} :params [n 2]}"})
                    (is (= 8 (result (execute! app id "source" "action" 1) "source")))
                    (is (= [:setup :first] @prefix-calls) "Adding and running the next action does not rerun setup or the prefix")
                    (is (= {:base 4}
                           (:state (wb/selected-value app {:session-id id :task-name "source" :action-name "first" :view "Inputs"}))))
                    (let [edited (wb/apply-definition! app
                                                       id
                                                       {:edn (pr-str (assoc-in (data/read-edn (get-in (wb/session-view app id) [:document :edn]))
                                                                      [:tasks 0 :actions 1 :params]
                                                                      '[n 3]))})]
                      (is (= 0 (get-in edited [:tasks "source" :through])))
                      (is (nil? (get-in edited [:tasks "source" :actions "second"])))
                      (is (thrown? Exception (wb/selected-value app {:session-id id :task-name "source" :action-name "second"}))))
                    (is (= 12 (result (execute! app id "source" "action" 1) "source")))
                    (is (= [:setup :first] @prefix-calls) "Editing a later action keeps the earlier invocation"))))


(deftest prefix-retention-respects-context-and-upstream-changes
  (with-workbench [app]
                  (doseq [[label request]
                          [["setup" {:edn (pr-str (assoc-in pipeline [:tasks 0 :setup 0 :params] [40]))}]
                           ["configuration" {:edn (pr-str pipeline) :configEdn "{:value 40}"}]
                           ["dependencies" {:edn (pr-str (assoc pipeline :deps {:requires '[[clojure.string]]}))}]
                           ["task return" {:edn (pr-str (assoc-in pipeline [:tasks 0 :return] [:state :first]))}]
                           ["earlier action" {:edn (pr-str (assoc-in pipeline [:tasks 0 :actions 0 :params] [40]))}]
                           ["reordered actions" {:op "move-action" :task "source" :index 1 :direction "up"}]]]
                    (testing label
                      (let [{:keys [id]} (session! app pipeline)]
                        (execute! app id "source" "prefix" 1)
                        (execute! app id "dependent" "task")
                        (let [edited (wb/apply-definition! app id request)]
                          (is (= "stale" (get-in edited [:tasks "source" :status])))
                          (is (= -1 (get-in edited [:tasks "source" :through])))
                          (is (= "stale" (get-in edited [:tasks "dependent" :status])))
                          (is (thrown? Exception (wb/selected-value app {:session-id id :task-name "source" :action-name "first"})))
                          (is (thrown-with-msg? Exception #"earlier actions" (execute! app id "source" "action" 1))))
                        (wb/delete-session! app id))))
                  (testing "a changed upstream task invalidates a downstream prefix, even when its own later action changed"
                    (let [spec         (-> pipeline
                                           (update-in [:tasks 1 :actions] conj '{:name :second :type :clj/inc :selectors {n [:state :first]} :params [n]})
                                           (update :tasks conj '{:name :leaf :inputs [:dependent] :actions [{:name :value :type :clj/identity :selectors {n [:inputs :dependent]} :params [n]}]}))
                          {:keys [id]} (session! app spec)]
                      (execute! app id "source" "prefix" 1)
                      (execute! app id "dependent" "prefix" 1)
                      (execute! app id "leaf" "task")
                      (let [edited (wb/apply-definition! app
                                                         id
                                                         {:edn (pr-str (-> spec
                                                                           (assoc-in [:tasks 0 :actions 1 :params] '[n 3])
                                                                           (assoc-in [:tasks 1 :actions 1 :params] '[50])))})]
                        (is (= 0 (get-in edited [:tasks "source" :through])))
                        (is (= -1 (get-in edited [:tasks "dependent" :through])))
                        (is (= "stale" (get-in edited [:tasks "dependent" :status])))
                        (is (= "stale" (get-in edited [:tasks "leaf" :status])))
                        (is (thrown-with-msg? Exception #"upstream" (execute! app id "dependent" "action" 1))))))))


(deftest incremental-execution-and-invalidation
  (with-workbench [app]
                  (let [{:keys [id]} (session! app pipeline)]
                    (is (thrown-with-msg? Exception #"upstream" (execute! app id "dependent" "task")))
                    (is (thrown-with-msg? Exception #"earlier actions" (execute! app id "source" "action" 1)))
                    (is (= 5 (result (execute! app id "source" "action" 0) "source")))
                    (is (= 10 (result (execute! app id "source" "action" 1) "source")))
                    (is (= 11 (result (execute! app id "dependent" "task") "dependent")))
                    (let [rerun (execute! app id "source" "action" 0)]
                      (is (= "stale" (get-in rerun [:tasks "dependent" :status])))
                      (is (nil? (get-in rerun [:tasks "source" :actions "second"])))
                      (is (thrown-with-msg? Exception
                                            #"no longer available"
                                            (wb/selected-value app {:session-id id :task-name "source" :action-name "second"}))))
                    (is (= 10 (result (execute! app id "source" "prefix" 1) "source")))
                    (is (empty? (store/list-runs (:store (:ctx app)) 100)) "Scratch does not create durable runs")
                    (let [edited (wb/apply-definition! app id {:edn (pr-str (assoc-in pipeline [:tasks 0 :actions 1 :params] '[n 3]))})]
                      (is (= "partial" (get-in edited [:tasks "source" :status])))
                      (is (= 15 (result (execute! app id "source" "action" 1) "source"))))
                    (is (= 15 (result (execute! app id "source" "prefix" 1) "source")))
                    (let [before (wb/session-view app id)]
                      (is (thrown? Exception (wb/apply-definition! app id {:edn "{:name"})))
                      (is (= before (wb/session-view app id))))
                    (is (= {} (:tasks (wb/reset-session! app id)))))))


(deftest inserting-the-next-action-preserves-only-its-predecessors
  (with-workbench [app]
                  (let [{:keys [id]} (session! app pipeline)]
                    (execute! app id "source" "prefix" 1)
                    (execute! app id "dependent" "task")
                    (let [first-result (get-in (wb/session-view app id) [:tasks "source" :actions "first"])
                          inserted     (wb/apply-definition! app id {:op "add-action" :task "source" :index 1})]
                      (is (= ["first" "step-1" "second"] (mapv :name (get-in inserted [:document :tasks 0 :actions]))))
                      (is (= first-result (get-in inserted [:tasks "source" :actions "first"])))
                      (is (= 0 (get-in inserted [:tasks "source" :through])))
                      (is (nil? (get-in inserted [:tasks "source" :actions "second"])))
                      (is (= "stale" (get-in inserted [:tasks "dependent" :status])))
                      (is (= {"base" 4 "first" 5} (get-in (wb/action-inputs app id "source" 1) [:preview :value "state"]))))
                    (wb/apply-definition! app id {:op "replace-action" :task "source" :index 1 :actionEdn "{:name :middle :type :clj/inc :selectors {n [:state :first]} :params [n]}"})
                    (is (= 6 (result (execute! app id "source" "action" 1) "source")))
                    (doseq [index [-1 4 "one"]]
                      (let [before (wb/session-view app id)]
                        (is (thrown-with-msg? Exception #"position" (wb/apply-definition! app id {:op "add-action" :task "source" :index index})))
                        (is (= before (wb/session-view app id))))))))


(deftest shared-source-and-visual-edits-commit-once
  (with-workbench [app]
                  (let [{:keys [id]} (session! app pipeline)
                        _ (execute! app id "source" "prefix" 1)
                        _ (execute! app id "dependent" "task")
                        before       (wb/session-view app id)
                        edited       (assoc-in pipeline [:tasks 0 :actions 1 :params] '[n 3])
                        request      {:edn (pr-str edited) :revision (:revision before) :op "add-action" :task "source" :index 2}]
                    (doseq [invalid [{}
                                     (assoc request :op nil)
                                     (assoc request :task "missing")
                                     (assoc request :edn "{:name")
                                     (assoc request :configEdn "[]")
                                     (assoc request :revision -1)]]
                      (is (thrown? Exception (wb/apply-definition! app id invalid)))
                      (is (= before (wb/session-view app id))))
                    (let [after (wb/apply-definition! app id request)]
                      (is (= (inc (:revision before)) (:revision after)))
                      (is (= ["first" "second" "step-1"] (mapv :name (get-in after [:document :tasks 0 :actions]))))
                      (is (= '[n 3] (get-in (data/read-edn (get-in after [:document :edn])) [:tasks 0 :actions 1 :params])))
                      (is (= (get-in before [:tasks "source" :actions "first"]) (get-in after [:tasks "source" :actions "first"])))
                      (is (= 0 (get-in after [:tasks "source" :through])))
                      (is (= "stale" (get-in after [:tasks "dependent" :status])))
                      (is (= 15 (result (execute! app id "source" "action" 1) "source")))))))


(deftest graph-edits-preserve-semantics-and-revisions
  (with-workbench [app]
                  (let [spec         (assoc pipeline
                                       :max-parallelism 3
                                       :custom {:nested [1 nil 'foo]})
                        {:keys [id]} (session! app spec)
                        saved        (wb/save! app id)
                        version      (get-in saved [:document :version])
                        edited       (wb/apply-definition! app id {:op "move-action" :task "source" :index 1 :direction "up"})
                        parsed       (data/read-edn (get-in edited [:document :edn]))]
                    (is (= (:custom spec) (:custom parsed)))
                    (is (= (get-in spec [:tasks 0 :setup]) (get-in parsed [:tasks 0 :setup])))
                    (is (nil? (:version parsed)))
                    (is (= :first (get-in (store/load-pipeline (:store (:ctx app)) :incremental version) [:tasks 0 :actions 0 :name])))
                    (is (= (inc version) (get-in (wb/save! app id) [:document :version])))
                    (is (thrown? Exception (wb/apply-definition! app id {:op "connect" :source "dependent" :target "source"})))
                    (is (= [:source] (get-in (data/read-edn (get-in (wb/session-view app id) [:document :edn])) [:tasks 1 :inputs])))
                    (is (= [] (get-in (data/edit spec {:op "disconnect" :source "source" :target "dependent"}) [:tasks 1 :inputs])))
                    (is (= [:source] (get-in (data/edit (assoc-in spec [:tasks 1 :inputs] []) {:op "connect" :source "source" :target "dependent"}) [:tasks 1 :inputs])))
                    (is (thrown-with-msg? Exception #"Draft changed" (wb/apply-definition! app id {:revision 0 :edn (pr-str spec)}))))))


(defmethod action/prep ::prepared
  [spec]
  (assoc spec
    :type :clj/inc
    :params [41]))


(deftest prepared-actions-and-execution-failures
  (with-workbench [app]
                  (let [{:keys [id]} (session! app {:name :prepared :tasks [{:name :one :actions [{:name :value :type ::prepared}]}]})]
                    (is (= 42 (result (execute! app id "one" "action" 0) "one"))))
                  (let [{:keys [id]} (session! app '{:name :failure :tasks [{:name :one :actions [{:name :divide :type :clj// :params [1 0]}]}]})
                        view         (execute! app id "one" "action" 0)]
                    (is (= "failed" (get-in view [:tasks "one" :status])))
                    (is (some #(re-find #"Divide by zero" %) (get-in view [:tasks "one" :error :causes]))))))


(deftest prepared-action-results-can-feed-an-appended-action
  (with-workbench [app]
                  (let [{:keys [id]} (session! app {:name :prepared-prefix :tasks [{:name :one :actions [{:name :value :type ::prepared}]}]})]
                    (execute! app id "one" "action" 0)
                    (wb/apply-definition! app id {:op "add-action" :task "one"})
                    (wb/apply-definition! app
                                          id
                                          {:op        "replace-action"
                                           :task      "one"
                                           :index     1
                                           :actionEdn "{:name :next :type :clj/inc :selectors {n [:state :value]} :params [n]}"})
                    (is (= 43 (result (execute! app id "one" "action" 1) "one")))
                    (is (= 42 (wb/selected-value app {:session-id id :task-name "one" :action-name "value"}))))))


(deftest task-plan-changes-reset-the-prefix
  (with-workbench [app]
                  (doseq [next-action '[{:name :folded :type :fold :params {}}]]
                    (let [{:keys [id]} (session! app pipeline)]
                      (execute! app id "source" "prefix" 1)
                      (let [edited (wb/apply-definition! app id {:edn (pr-str (update-in pipeline [:tasks 0 :actions] conj next-action))})]
                        (is (= "stale" (get-in edited [:tasks "source" :status])))
                        (is (= -1 (get-in edited [:tasks "source" :through])))
                        (is (thrown? Exception (wb/selected-value app {:session-id id :task-name "source" :action-name "first"}))))))))


(deftest file-backed-action-prefix-is-preserved
  (with-workbench [app]
                  (let [spec         {:name :file-prefix :tasks [{:name :one :actions [{:name :value :type :test.prefix-fixture/identity.edn}]}]}
                        {:keys [id]} (session! app spec)]
                    (is (= 41 (result (execute! app id "one" "action" 0) "one")))
                    (wb/apply-definition! app
                                          id
                                          {:edn (pr-str (update-in spec
                                                                   [:tasks 0 :actions]
                                                                   conj
                                                                   '{:name :next :type :clj/inc :selectors {n [:state :value]} :params [n]}))})
                    (is (= 42 (result (execute! app id "one" "action" 1) "one")))
                    (is (= 41 (wb/selected-value app {:session-id id :task-name "one" :action-name "value"}))))))


(deftest fresh-normal-runs-and-retained-output
  (with-workbench [app]
                  (let [{:keys [id]} (session! app pipeline)]
                    (execute! app id "source" "prefix" 0)
                    (let [run    (wb/start-run! app id)
                          handle (get @(:handles app) (java.util.UUID/fromString (:id run)))]
                      (is (= :done (:run/status @handle)))
                      (is (= {} (data/read-edn (:configEdn (wb/run-view app (:id run))))))
                      (is (= 11 (get-in (wb/output app (:id run) "dependent") [:preview :value])))
                      (is (= "partial" (get-in (wb/session-view app id) [:tasks "source" :status])) "Normal execution does not reuse or fill scratch state")
                      (is (= 1 (count (:runs (wb/catalog app)))))
                      (is (:id (session! app {:name :after-run :tasks []})))
                      "App remains usable after completion"))))


(deftest bounded-previews-and-exact-selectors
  (let [sample (data/preview (mapv (fn [i] {"string-key" i :keyword-key i}) (range 150)))]
    (is (= 100 (:count sample)))
    (is (:truncated sample))
    (is (= #{"[0 \"string-key\"]" "[0 :keyword-key]"} (set (:columnSelectors sample))))
    (is (some #(= "[3 :keyword-key]" (:selector %)) (:selectors sample))))
  (is (:truncated (data/preview (apply str (repeat 5000 "x")))))
  (is (thrown? Exception (data/read-edn "{} {}")))
  (is (thrown? Exception (data/read-edn "#=(println :no)"))))


(deftest passive-observation-does-not-execute-deferred-inputs
  (let [reads    (atom 0)
        rows     (lazy-seq (swap! reads inc) [{:value 1}])
        observed (data/observation-preview {:params [rows]})]
    (is (:deferred observed))
    (is (zero? @reads))
    (data/safe-value {:params rows})
    (is (zero? @reads))
    (is (= 1 (get-in (data/preview rows) [:value 0 "value"])))
    (is (= 1 @reads))))


(defn request! [port method path body header?]
  (let [builder (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port path)))
                    (.timeout (Duration/ofSeconds 10)))
        builder (if (= method "POST") (.POST builder (HttpRequest$BodyPublishers/ofString (json/write-json-str body))) (.GET builder))]
    (when header? (.header builder "X-Collet-Workbench" "1"))
    (.send (HttpClient/newHttpClient) (.build builder) (HttpResponse$BodyHandlers/ofString))))


(deftest configuration-error-responses
  (doseq [[schema code status] [[::unknown-schema "invalid-config-spec" 400]
                                [[:map [:port :int]] "invalid-configuration" 409]]]
    (let [error    (try (collet/prepare-config! {:config-spec schema} {})
                        (catch clojure.lang.ExceptionInfo e e))
          response (#'http/error-response error)
          details  (get-in (json/read-json (:body response) :key-fn keyword) [:error :details])]
      (is (= status (:status response)))
      (is (= {:code code :status status} (select-keys details [:code :status])))
      (is (= {:code code :status status} (select-keys (:error (wire/error error)) [:code :status]))))))


(deftest store-error-responses
  (doseq [[details code status] [[{:collet.error/type :collet.error/invalid-draft-source} "invalid-request" 400]
                                 [{:collet.error/type :collet.error/draft-not-found} "not-found" 404]
                                 [{:collet.error/type :collet.error/draft-already-exists} "conflict" 409]
                                 [{:code "invalid-cursor"} "invalid-cursor" 400]
                                 [{:code "pipeline-not-found"} "pipeline-not-found" 404]
                                 [{:code "pipeline-changed"} "pipeline-changed" 409]
                                 [{:code "pipeline-busy"} "pipeline-busy" 409]
                                 [{:code :draft-conflict} "draft-conflict" 409]]]
    (let [error (ex-info "Store error" details)]
      (is (= status (:status (#'http/error-response error))))
      (is (= {:code code :status status} (select-keys (:error (wire/error error)) [:code :status]))))))


(deftest http-lifecycle-reconnect-and-validation
  (let [path (temp-dir)
        app  (http/start! {:port 0 :data-dir (str path)})
        port (:port app)]
    (try
      (is (= 200 (.statusCode (request! port "GET" "/api/health" nil false))))
      (is (= 403 (.statusCode (request! port "POST" "/api/sessions" {} false))))
      (is (= 403 (.statusCode (request! port "POST" "/api/format" {:edn "{}"} false))))
      (let [text     ";; Configuration\n{:token #env \"TOKEN\",:enabled true}"
            response (request! port "POST" "/api/format" {:edn text} true)]
        (is (= 200 (.statusCode response)))
        (is (= (data/format-edn text) (:edn (json/read-json (.body response) :key-fn keyword)))))
      (is (= 400 (.statusCode (request! port "POST" "/api/format" {:edn "{:broken"} true))))
      (is (= 400 (.statusCode (request! port "POST" "/api/format" {} true))))
      (let [response (request! port "POST" "/api/sessions" {:edn (pr-str pipeline)} true)
            session  (json/read-json (.body response) :key-fn keyword)]
        (is (= 200 (.statusCode response)))
        (is (= (:id session) (:id (json/read-json (.body (request! port "GET" (str "/api/sessions/" (:id session)) nil false)) :key-fn keyword))))
        (let [inputs (request! port "GET" (str "/api/sessions/" (:id session) "/inputs/source/0") nil false)]
          (is (= 200 (.statusCode inputs)))
          (is (= {} (get-in (json/read-json (.body inputs) :key-fn keyword) [:preview :value :state]))))
        (is (= 409 (.statusCode (request! port "GET" (str "/api/sessions/" (:id session) "/inputs/source/no-index") nil false))))
        (is (= 400 (.statusCode (request! port "POST" "/api/validate" {:edn "{:broken"} true)))))
      (let [response (.send (HttpClient/newHttpClient)
                            (.build (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port "/api/events"))))
                            (HttpResponse$BodyHandlers/ofInputStream))]
        (with-open [reader (java.io.BufferedReader. (java.io.InputStreamReader. (.body response)))]
          (is (= "event: snapshot" (.readLine reader)))))
      (finally ((:stop app)) (delete-tree! path)))
    (is @(:closed? app))
    ((:stop app))))


(deftest saved-revisions-run-directly-without-an-authoring-session
  (let [path (temp-dir)
        app  (http/start! {:port 0 :data-dir (str path)})
        port (:port app)
        db   (:store (:ctx app))]
    (try
      (let [spec        (assoc pipeline :name :saved/incremental)
            first-plan  (store/save-pipeline! db {} (collet/compile-pipeline spec))
            second-plan (store/save-pipeline! db {} (collet/compile-pipeline (assoc-in spec [:tasks 0 :actions 1 :params] '[n 3])))
            endpoint    (str "/api/definitions/saved%2Fincremental/" (:version first-plan) "/run")
            command     {:instanceId (:instance-id app) :idempotencyKey "unknown-version" :configuration {:source "empty"}}]
        (is (not (contains? first-plan :collet.workbench/source)))
        (is (= 403 (.statusCode (request! port "POST" endpoint command false))))
        (is (= 404 (.statusCode (request! port "POST" "/api/definitions/saved%2Fincremental/999999/run" command true))))
        (is (empty? @(:handles app)))
        (let [ids (doall
                   (for [_ (range 2)]
                     (let [response (request! port "POST" endpoint (assoc command :idempotencyKey (str (random-uuid))) true)
                           job      (json/read-json (.body response) :key-fn keyword)]
                       (is (= 200 (.statusCode response)))
                       (is (eventually #(:runId (authoring/get-execution app (:id job)))))
                       (let [run-id (:runId (authoring/get-execution app (:id job)))
                             handle (get @(:handles app) (java.util.UUID/fromString run-id))]
                         (is (= :done (:run/status @handle)))
                         (is (= (:version first-plan) (get-in (wb/run-view app run-id) [:document :version])))
                         (is (= 11 (get-in (wb/output app run-id "dependent") [:preview :value])))
                         run-id))))]
          (is (= 2 (count (distinct ids)))))
        (is (empty? @(:sessions app)))
        (is (= first-plan (store/load-pipeline db :saved/incremental (:version first-plan))))
        (is (= second-plan (store/load-pipeline db :saved/incremental (:version second-plan))))
        (is (= 2 (count (:pipelines (wb/catalog app))))))
      (finally ((:stop app)) (delete-tree! path)))))


(def retry-count (atom 0))


(defn retry-once []
  (when (= 1 (swap! retry-count inc)) (throw (ex-info "try again" {:transient true})))
  :done)


(deftest correlated-events-cover-parallel-loops-retries-and-repeated-names
  (reset! retry-count 0)
  (with-workbench [app]
                  (let [spec
                        '{:name            :correlation
                          :max-parallelism 3
                          :tasks           [{:name       :parallel
                                             :parallel   {:range {:start 0 :end 3}}
                                             :keep-state true
                                             :actions    [{:name :same :type :clj/identity :selectors {x [:$parallel/item]} :params [x]}]}
                                            {:name       :retry
                                             :retry      {:max-retries 2 :backoff-ms [5 10]}
                                             :keep-state true
                                             :actions    [{:name :same :type :custom :fn collet.workbench-test/retry-once}]}
                                            {:name         :loop
                                             :keep-state   true
                                             :state-format :latest
                                             :setup        [{:name :seed :type :clj/identity :params [0]}]
                                             :actions      [{:name :same :type :clj/inc :selectors {x [:state :seed]} :params [x]}
                                                            {:name :seed :type :clj/identity :selectors {x [:state :same]} :params [x]}]
                                             :iterator     {:next [:< [:state :same] 3]}}]}
                        s (session! app spec)
                        run (wb/start-run! app (:id s))
                        handle (get @(:handles app) (java.util.UUID/fromString (:id run)))]
                    (is (= :done (:run/status @handle)))
                    (is (eventually #(>= (count (filter (fn [e] (= ":collet/action-start" (get e "mulog/event-name"))) (:events (wb/run-view app (:id run))))) 12)))
                    (let [events  (:events (wb/run-view app (:id run)))
                          starts  (filter #(= ":collet/action-start" (get % "mulog/event-name")) events)
                          results (filter #(= ":action-result" (get % "collet/event")) events)]
                      (is (= (count starts) (count (set (map #(get % "invocation-id") starts)))))
                      (is (every? #(and (= (:id run) (get % "run-id")) (get % "task-id")) starts))
                      (is (every? #(= (get-in run [:document :version]) (get % "version")) starts))
                      (is (= 3 (count (filter #(= ":parallel" (get % "task")) starts))))
                      (is (= 2 (count (filter #(= ":retry" (get % "task")) starts))))
                      (is (some #(= ":collet/retrying-task" (get % "mulog/event-name")) events))
                      (is (every? (set (map #(get % "invocation-id") starts)) (map #(get % "invocation-id") results)))))))


(deftest whole-task-preview-realizes-iterator-and-parallel-results
  (with-workbench [app]
                  (let [task         '{:name         :iterate
                                       :state-format :latest
                                       :setup        [{:name :seed :type :clj/identity :params [0]}]
                                       :actions      [{:name :seed :type :clj/inc :selectors {x [:state :seed]} :params [x]}]
                                       :iterator     {:next [:< [:state :seed] 3]}}
                        {:keys [id]} (session! app {:name :iterator-preview :tasks [task]})]
                    (is (thrown-with-msg? Exception #"whole-task" (execute! app id "iterate" "action" 0)))
                    (is (= 3 (result (execute! app id "iterate" "task") "iterate")))))
  (with-workbench [app]
                  (let [{:keys [id]} (session! app
                                               '{:name  :parallel-preview
                                                 :tasks [{:name     :parallel
                                                          :parallel {:range {:start 0 :end 3}}
                                                          :actions  [{:name :same :type :clj/identity :selectors {x [:$parallel/item]} :params [x]}]}]})]
                    (is (= [0 1 2] (result (execute! app id "parallel" "task") "parallel"))))))


(deftest saved-parallel-definitions-remain-editable-source
  (with-workbench [app]
                  (let [spec         '{:name  :saved-parallel
                                       :tasks [{:name       :p
                                                :parallel   {:range {:start 0 :end 3}}
                                                :keep-state true
                                                :actions    [{:name :value :type :clj/identity :selectors {x [:$parallel/item]} :params [x]}]}]}
                        {:keys [id]} (session! app spec)
                        saved        (wb/save! app id)
                        loaded       (wb/load-definition app "saved-parallel" (get-in saved [:document :version]))
                        draft        (session! app (data/read-edn (:edn loaded)))]
                    (is (= '[x] (get-in (data/read-edn (:edn loaded)) [:tasks 0 :actions 0 :params])))
                    (is (= '[:$parallel/item] (get-in (data/read-edn (:edn loaded)) [:tasks 0 :actions 0 :selectors 'x])))
                    (is (= [0 1 2] (result (execute! app (:id draft) "p" "task") "p"))))))


(deftest regex-source-and-portal-selection
  (let [original  (data/read-edn "{:name :regex :tasks [] :pattern #rgx \"(?i)abc\"}")
        edited    (data/edit original {:op "add-task"})
        read-back (data/read-edn (data/source edited))]
    (is (= (.pattern (:pattern original)) (.pattern (:pattern read-back))))
    (is (= (.flags (:pattern original)) (.flags (:pattern read-back)))))
  (with-workbench [app]
                  (let [{:keys [id]} (session! app pipeline)]
                    (execute! app id "source" "prefix" 1)
                    (is (= 5 (wb/selected-value app {:session-id id :task-name "source" :action-name "first"})))
                    (is (= 10 (wb/selected-value app {:session-id id :task-name "source"})))
                    (wb/reset-session! app id)
                    (is (thrown? Exception (wb/selected-value app {:session-id id :task-name "source" :action-name "first"}))))))


(defn- response-json [response]
  (json/read-json (.body response) :key-fn keyword))


(defn- start-packaged-run [port session version]
  (let [job       (response-json (request! port
                                           "POST"
                                           "/api/executions"
                                           {:scope          "pipeline"
                                            :configuration  {:source "draft" :id (:id session) :revision (:configurationRevision session)}
                                            :pipeline       (get-in session [:document :name])
                                            :version        version
                                            :instanceId     (:instanceId session)
                                            :idempotencyKey (str (random-uuid))}
                                           true))
        execution #(response-json (request! port "GET" (str "/api/executions/" (:id job)) nil false))]
    (is (eventually #(:runId (execution))))
    (is (eventually #(#{"completed" "failed"} (:status (execution)))))
    (let [result (execution)]
      (is (= "completed" (:status result)) (pr-str result))
      (:runId result))))


(defn- assert-packaged-server [port]
  (let [page    (request! port "GET" "/" nil false)
        asset   (second (re-find #"src=\"([^\"]+\.js)\"" (.body page)))
        session (response-json (request! port "POST" "/api/sessions" {:edn "{:name :packaged :tasks []}"} true))
        saved   (response-json (request! port "POST" (str "/api/sessions/" (:id session) "/save") {:revision 0} true))]
    (is (= 200 (.statusCode page)))
    (is (re-find #"Collet Workbench" (.body page)))
    (is asset)
    (when asset (is (= 200 (.statusCode (request! port "GET" asset nil false)))))
    (is (string? (start-packaged-run port session (:savedVersion saved))))
    (is (= 200 (.statusCode (request! port "GET" "/api/health" nil false))))
    (is (= 200 (.statusCode (request! port "GET" "/api/documentation/getting-started" nil false))))
    (is (= 20 (count (:actions (response-json (request! port "GET" "/api/actions" nil false))))))
    (is (= 200 (.statusCode (request! port "GET" "/api/schemas" nil false)))))
  (let [session (response-json (request! port "POST" "/api/sessions" {} true))
        saved   (response-json (request! port "POST" (str "/api/sessions/" (:id session) "/save") {:revision 0} true))]
    (dotimes [_ 2]
      (let [run-id (start-packaged-run port session (:savedVersion saved))]
        (is (= 24 (get-in (response-json (request! port "GET" (str "/api/runs/" run-id "/outputs/report") nil false)) [:preview :value :orders])))
        (is (= 200 (.statusCode (request! port "POST" "/api/inspect" {:run-id run-id :task-name "report"} true))))))))


(deftest ^:integration packaged-workbench-serves-assets-and-stays-alive
  (let [path    (temp-dir)
        port    (with-open [socket (java.net.ServerSocket. 0)] (.getLocalPort socket))
        log     (.toFile (.resolve path "process.log"))
        builder (ProcessBuilder.
                 ^java.util.List
                 ["java" "--add-opens=java.base/java.nio=ALL-UNNAMED"
                  "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED" "--enable-native-access=ALL-UNNAMED"
                  "-jar" (.getCanonicalPath (java.io.File. "target/collet.jar")) "--ui" "--port" (str port)])
        _ (.directory builder (.toFile path))
        _ (.put (.environment builder) "COLLET_DATA_DIR" (str path))
        process (-> builder (.redirectErrorStream true) (.redirectOutput log) (.start))]
    (try
      (let [ready? (loop [n 400]
                     (or (try (= 200 (.statusCode (request! port "GET" "/api/health" nil false)))
                              (catch Exception _ false))
                         (when (and (pos? n) (.isAlive process))
                           (Thread/sleep 50)
                           (recur (dec n)))))]
        (is ready? (slurp log))
        (when ready? (assert-packaged-server port))
        (is (.isAlive process)))
      (finally
       (.destroy process)
       (let [stopped? (.waitFor process 10 java.util.concurrent.TimeUnit/SECONDS)]
         (is stopped? "Workbench exits after Portal was opened without a browser")
         (when-not stopped?
           (.destroyForcibly process)
           (.waitFor process)))
       (delete-tree! path)))))


(deftest http-preserves-namespaced-identifiers
  (let [path (temp-dir)
        app  (http/start! {:port 0 :data-dir (str path)})
        port (:port app)]
    (try
      (let [session    (response-json (request! port
                                                "POST"
                                                "/api/sessions"
                                                {:edn "{:name :ui/orders :tasks [{:name :ui/source :keep-state true :actions [{:name :value :type :clj/identity :params [42]}]}]}"}
                                                true))
            saved      (response-json (request! port "POST" (str "/api/sessions/" (:id session) "/save") {:revision (:revision session)} true))
            definition (response-json (request! port "GET" (str "/api/definitions/ui%2Forders/" (get-in saved [:document :version])) nil false))
            job        (response-json (request! port
                                                "POST"
                                                "/api/executions"
                                                {:scope          "pipeline"
                                                 :configuration  {:source "empty"}
                                                 :pipeline       "ui/orders"
                                                 :version        (:savedVersion saved)
                                                 :instanceId     (:instance-id app)
                                                 :idempotencyKey (str (random-uuid))}
                                                true))
            finished   #(response-json (request! port "GET" (str "/api/executions/" (:id job)) nil false))]
        (is (= "ui/orders" (:name definition)))
        (is (eventually #(:runId (finished))))
        (let [output #(response-json (request! port "GET" (str "/api/runs/" (:runId (finished)) "/outputs/ui%2Fsource") nil false))]
          (is (eventually #(:available (output))))
          (is (= 42 (get-in (output) [:preview :value])))))
      (finally ((:stop app)) (delete-tree! path)))))


(deftest older-compiled-revisions-can-be-used-as-drafts
  (with-workbench [app]
                  (let [spec         '{:name  :legacy
                                       :tasks [{:name     :parallel
                                                :parallel {:range {:start 0 :end 3}}
                                                :actions  [{:name :value :type :clj/identity :selectors {x [:$parallel/item]} :params [x]}]}]}
                        _ (store/save-pipeline! (:store (:ctx app)) {} (collet/compile-pipeline spec))
                        loaded       (wb/load-definition app "legacy" 1)
                        {:keys [id]} (session! app (data/read-edn (:edn loaded)))]
                    (is (= [0 1 2] (result (execute! app id "parallel" "task") "parallel"))))))


(def task-release (atom nil))


(defn gated-value
  ([]
   (gated-value 5000))
  ([timeout-ms]
   (deref @task-release timeout-ms 7)))


(deftest action-telemetry-excludes-values-and-failure-data
  (let [id     (random-uuid)
        events (atom [])
        secret "private-probe-value"
        stop   (ml/start-publisher! {:type         :custom
                                     :fqn-function "collet.workbench.events/publisher"
                                     :on-events    #(swap! events into (filter (fn [event] (= id (::probe event))) %))})]
    (try
      (ml/with-context {::probe id}
                       (collet/execute-action {:type :clj/identity :name :success :params [secret]} {})
                       (is (thrown-with-msg? Exception
                                             #"Action failed"
                                             (collet/execute-action {:type   :custom
                                                                     :name   :failure
                                                                     :params [secret]
                                                                     :fn     (fn [v] (throw (ex-info v {:request v})))}
                                                                    {}))))
      (let [spans #(filter (fn [e] (= :collet/executing-action (:mulog/event-name e))) @events)]
        (is (eventually #(= 2 (count (spans)))))
        (is (= {:success :ok :failure :error} (into {} (map (juxt :action :mulog/outcome)) (spans))))
        (is (every? #(empty? (select-keys % [:exception :params :result])) @events))
        (is (not (.contains (pr-str @events) secret))))
      (finally (stop)))))


(deftest samples-and-fixture-substitution-are-independent-of-publishing
  (with-workbench [app]
                  ((:stop-publisher app))
                  (let [gate    (promise)
                        _ (reset! task-release gate)
                        spec    '{:name  :isolated-samples
                                  :deps  {:requires [[collet.workbench-test :as probe]]}
                                  :tasks [{:name       :rows
                                           :keep-state true
                                           :setup      [{:name :gate :type :custom :fn collet.workbench-test/gated-value :params [30000]}]
                                           :actions    [{:name :each :type :mapper :params {:sequence [1 2]}}
                                                        {:name      :value
                                                         :type      :clj/identity
                                                         :selectors {n [:$mapper/item]}
                                                         :params    [(probe/record-prefix-call :parameter {:value n})]
                                                         :return    [:value]}]
                                           :iterator   {:next [:true? [:state :each :next]]}}]}
                        session (session! app spec)
                        _ (reset! prefix-calls [])
                        run     (wb/start-run! app (:id session))
                        handle  (get @(:handles app) (java.util.UUID/fromString (:id run)))
                        plan    (collet/load-pipeline (:ctx app) :isolated-samples 1)]
                    (try
                      (let [fixture  {:edn (pr-str {:mocks {:gate {:value 42} :value {:value {:value 99}}}})}
                            options  {:scope "pipeline-fixture" :execution-id (str (random-uuid))}
                            result   (fixtures/execute app plan fixture options)
                            selected (fixtures/execute app spec fixture {:scope "task" :task-name "rows"})]
                        (is (= "completed" (:status result)))
                        (is (= 99 (get-in selected [:result :actions :value :preview :value])))
                        (is (empty? @prefix-calls))
                        (is (not (realized? (get-in @(:runs (:ctx app)) [(:run/id handle) :completion]))))
                        (is (= plan (collet/load-pipeline (:ctx app) :isolated-samples 1))))
                      (finally (deliver gate 7)))
                    (is (= :done (:run/status @handle)))
                    (is (= [:parameter :parameter] @prefix-calls))
                    (is (= 2 (get-in (inspection/read-run app (:id run) {:view "result" :task-name "rows" :action-name "value"}) [:sample :value])))
                    (is (= [{:value 2}] (wb/selected-value app {:run-id (:id run) :task-name "rows" :action-name "value" :view "Inputs"})))
                    (is (empty? (filter #(get % "mulog/event-name") (:events (wb/run-view app (:id run)))))))))


(deftest owned-run-controls-and-released-outputs
  (reset! task-release (promise))
  (with-workbench [app]
                  (let [spec       '{:name  :controlled
                                     :tasks [{:name :source :actions [{:name :value :type :custom :fn collet.workbench-test/gated-value}]}
                                             {:name    :consumer
                                              :inputs  [:source]
                                              :actions [{:name :value :type :clj/inc :selectors {x [:inputs :source]} :params [x]}]}]}
                        session    (session! app spec)
                        run        (wb/start-run! app (:id session))
                        handle     (get @(:handles app) (java.util.UUID/fromString (:id run)))
                        task-state #(some (fn [t] (when (= ":source" (get t "task/name")) (get t "task/status")))
                                          (:tasks (wb/run-view app (:id run))))]
                    (is (eventually #(= ":running" (task-state))))
                    (wb/control! app (:id run) "pause")
                    (deliver @task-release 7)
                    (is (eventually #(= ":completed" (task-state))))
                    (is (= ":paused" (get-in (wb/run-view app (:id run)) [:run "run/status"])))
                    (wb/control! app (:id run) "resume")
                    (is (= :done (:run/status @handle)))
                    (is (false? (:available (wb/output app (:id run) "source"))))
                    (is (thrown-with-msg? Exception
                                          #"previous app process"
                                          (wb/control! (assoc app :handles (atom {})) (:id run) "resume"))))))


(deftest portal-without-a-browser-does-not-block-shutdown
  (let [started (System/nanoTime)]
    (with-workbench [app]
                    (let [{:keys [id]} (session! app pipeline)]
                      (execute! app id "source" "prefix" 0)
                      (dotimes [_ 2]
                        (is (string? (:url (wb/inspect! app {:session-id id :task-name "source" :action-name "first"})))))))
    (is (< (/ (- (System/nanoTime) started) 1e6) 5000))))
