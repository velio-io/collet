(ns collet.core-test
  (:require
   [clojure.test :refer :all]
   [collet.actions.mapper :as mapper]
   [collet.actions.slicer :as slicer]
   [collet.arrow :as arrow]
   [collet.core :as sut]
   [collet.test-fixtures :as tf]
   [collet.utils :as utils]
   [tech.v3.dataset :as ds]))


(use-fixtures :once (tf/instrument! 'collet.core))


(deftest action-params-test
  (testing "Params compilation"
    (let [compiled-params (sut/compile-action-params
                           {:params    '[param1 {:p2 param2} [1 2 state1]]
                            :selectors '{param1 [:config :param1]
                                         param2 [:config :param2]
                                         state1 [:state :some-action :state1]}}
                           {:config {:param1 "value1"
                                     :param2 "value2"}
                            :state  {:some-action {:state1 "state-value"}}}
                           (utils/eval-ctx))]
      (is (= compiled-params
             ["value1" {:p2 "value2"} [1 2 "state-value"]])))

    (testing "Params could be a map as well"
      (let [compiled-params (sut/compile-action-params
                             {:params    '{:p1 param1
                                           :p2 param2
                                           :p3 [1 2 state1]}
                              :selectors '{param1 [:config :param1]
                                           param2 [:config :param2]
                                           state1 [:state :some-action :state1]}}
                             {:config {:param1 "value1"
                                       :param2 "value2"}
                              :state  {:some-action {:state1 "state-value"}}}
                             (utils/eval-ctx))]
        (is (= compiled-params
               {:p1 "value1"
                :p2 "value2"
                :p3 [1 2 "state-value"]}))))))


(deftest action-params-data-mode-test
  (let [spec {:collet/params-mode :data
              :params             '{:query {:select [?s]
                                            :where  [[?s :ex/score ?score]
                                                     [:filter (> ?score threshold)]]}}
              :selectors          '{threshold [:config :threshold]}}]
    (is (= '{:query {:select [?s]
                     :where  [[?s :ex/score ?score]
                              [:filter (> ?score 0.5)]]}}
           (sut/compile-action-params spec {:config {:threshold 0.5} :state {}} (utils/eval-ctx)))))
  (is (= {:value 3}
         (sut/compile-action-params {:params '{:value (+ 1 2)}}
                                    {:config {} :state {}}
                                    (utils/eval-ctx)))))


(deftest action-params-resolve-selectors-in-expanded-forms
  (let [spec {:params    '{:name (symbol "n")
                           :data (quote {n [unknown missing disabled alias]})
                           :form (list (quote +) (quote n) 1)}
              :selectors '{n [:config :n] missing [:config :missing] disabled [:config :disabled] alias [:config :alias]}}]
    (is (= '{:name 42 :data {42 [unknown nil false n]} :form (+ 42 1)}
           (sut/compile-action-params spec
                                      (sut/->context '{:n 42 :disabled false :alias n})
                                      (utils/eval-ctx))))))


(deftest action-parameter-forms-preserve-runtime-values
  (let [dataset   (ds/->dataset [{:id 42}])
        consumed? (atom false)
        batches   (lazy-seq (reset! consumed? true) (list dataset))
        spec      {:params    '{:dataset (or rows [])
                                :batches (identity pages)
                                :option  (:enabled? settings)
                                :mixed   (vector rows pages (quote settings))
                                :names   (map (constantly (quote settings)) pages)}
                   :selectors '{rows [:state :dataset] pages [:state :batches] settings [:config :settings]}}
        params    (sut/compile-action-params spec
                                             {:config {:settings {:enabled? true}}
                                              :state  {:dataset dataset :batches batches}}
                                             (utils/eval-ctx))]
    (is (identical? dataset (:dataset params)))
    (is (identical? batches (:batches params)))
    (is (true? (:option params)))
    (let [[rows pages settings] (:mixed params)]
      (is (identical? dataset rows))
      (is (identical? batches pages))
      (is (= {:enabled? true} settings)))
    (is (false? @consumed?))
    (is (= [{:enabled? true}] (:names params)))))


(deftest compile-action-test
  (testing "Compiles an action spec into a function"
    (let [action-spec {:type :clj/select-keys
                       :name :test}
          action      (sut/compile-action (utils/eval-ctx) action-spec)]
      (is (fn? action))))

  (testing "Action type prefixed with 'clj' is resolved as a Clojure core function"
    (let [action-spec {:type   :clj/select-keys
                       :name   :keys-selector
                       :params [{:a 1 :b 2 :c 3 :d 4 :e 5}
                                [:a :b :e]]}
          action      (sut/compile-action (utils/eval-ctx) action-spec)
          actual      (-> (action {:config {} :state {}})
                          (get-in [:state :keys-selector]))]
      (is (= actual {:a 1 :b 2 :e 5}))))

  (testing "Custom action are allowed"
    (let [action-spec {:type   :custom
                       :name   :format-string
                       :params ["My name is %s"]
                       :fn     (fn [format-str]
                                 (format format-str "John"))}
          action      (sut/compile-action (utils/eval-ctx) action-spec)
          actual      (-> (action {:config {} :state {}})
                          (get-in [:state :format-string]))]
      (is (= actual "My name is John")))

    (let [action-spec {:type   :custom
                       :name   :format-string
                       :params {:template "My name is %s"
                                :value    "John"}
                       :fn     (fn [{:keys [template value]}]
                                 (format template value))}
          action      (sut/compile-action (utils/eval-ctx) action-spec)
          actual      (-> (action {:config {} :state {}})
                          (get-in [:state :format-string]))]
      (is (= actual "My name is John"))))

  (testing "Unknown action type raises an error"
    (let [action-spec {:type :random
                       :name :random-test}]
      (is (thrown? Exception (sut/compile-action (utils/eval-ctx) action-spec)))))

  (testing "Action with selectors"
    (let [action-spec {:type      :custom
                       :name      :params-test
                       :selectors '{param1 [:config :param1]
                                    param2 [:config :param2]
                                    state1 [:state :some-action :state1]}
                       :params    '[param1 {:p2 param2} [1 2 state1]]
                       :fn        (fn [p1 {:keys [p2]} [_ _ s1]]
                                    (format "param1: %s, param2: %s, state1: %s" p1 p2 s1))}
          action      (sut/compile-action (utils/eval-ctx) action-spec)
          actual      (-> (action {:config {:param1 "value1" :param2 "value2"}
                                   :state  {:some-action {:state1 "state-value"}}})
                          (get-in [:state :params-test]))]
      (is (= actual "param1: value1, param2: value2, state1: state-value"))))

  (testing "Predefined actions"
    (let [counter-action (sut/compile-action (utils/eval-ctx) {:type :counter :name :counter-test})]
      (is (fn? counter-action)))))


(deftest conditional-action-execution
  (testing "when condition specified, action executed only in case of match"
    (let [action-spec {:type      :custom
                       :name      :condition-test
                       :when      [:> [:config :b] 0]
                       :selectors '{a [:config :a]
                                    b [:config :b]}
                       :params    '[a b]
                       :fn        (fn [a b]
                                    (/ a b))}
          action      (sut/compile-action (utils/eval-ctx) action-spec)
          match       (-> (action {:config {:a 20 :b 5}
                                   :state  {}})
                          (get-in [:state :condition-test]))
          no-match    (action {:config {:a 20 :b 0}
                               :state  {:other :data}})]
      (is (= 4 match)
          "action executed")

      (is (= :data (get-in no-match [:state :other]))
          "other data is not affected")

      (is (nil? (get-in no-match [:state :condition-test]))
          "action not executed"))))


(deftest compile-and-run-task
  (testing "Compiles and runs a task"
    (let [task-spec         {:name    :test-task
                             :actions [{:type   :clj/select-keys
                                        :name   :keys-selector
                                        :params [{:a 1 :b 2 :c 3 :d 4 :e 5}
                                                 [:a :b :e]]}
                                       {:type      :custom
                                        :name      :format-string
                                        :selectors '{a [:state :keys-selector :a]
                                                     b [:state :keys-selector :b]
                                                     e [:state :keys-selector :e]}
                                        :params    '[a b e]
                                        :fn        (fn [a b e]
                                                     (format "Params extracted a: %s, b: %s, e: %s"
                                                             a
                                                             b
                                                             e))}]}
          {:keys [task-fn]} (sut/compile-task (utils/eval-ctx) task-spec)
          actual            (task-fn {:config {} :state {}})]
      (is (= actual "Params extracted a: 1, b: 2, e: 5"))))

  (testing "Task with setup actions"
    (let [task-spec         {:name    :test-task
                             :setup   [{:type   :clj/select-keys
                                        :name   :keys-selector
                                        :params [{:a 1 :b 2 :c 3 :d 4 :e 5}
                                                 [:a :b :e]]}]
                             :actions [{:type      :custom
                                        :name      :format-string
                                        :selectors '{a [:state :keys-selector :a]
                                                     b [:state :keys-selector :b]
                                                     e [:state :keys-selector :e]}
                                        :params    '[a b e]
                                        :fn        (fn [a b e]
                                                     (format "Params extracted a: %s, b: %s, e: %s"
                                                             a
                                                             b
                                                             e))}]}
          {:keys [task-fn]} (sut/compile-task (utils/eval-ctx) task-spec)
          actual            (task-fn {:config {} :state {}})]
      (is (= actual "Params extracted a: 1, b: 2, e: 5"))))

  (testing "Task with iterator"
    (let [counter           (atom 0)
          task-spec         {:name     :test-task
                             :actions  [{:type :custom
                                         :name :count-action
                                         :fn   (fn []
                                                 {:count (swap! counter inc)})}]
                             :iterator {:next true}
                             :return   [:state :count-action :count]}
          {:keys [task-fn]} (sut/compile-task (utils/eval-ctx) task-spec)
          result            (task-fn {:config {} :state {}})]
      ;; result becomes a sequence of what :data iterator property returns
      (is (= (take 10 result) (range 1 11)))
      (is (= (first result) 11))))

  (testing "Task with external actions"
    (let [task-spec         {:name    :test-task
                             :actions [{:name   :count-action
                                        :type   :test.collet/counter-action.edn
                                        :params [0]}]
                             ;; name of the action is overridden by the external action
                             :return  [:state :my-external-action]}
          {:keys [task-fn]} (sut/compile-task (utils/eval-ctx) task-spec)
          result            (task-fn {:config {} :state {}})]
      (is (= 1 result)))))


(deftest handle-task-errors-test
  (testing "Tasks failed on error"
    (let [task-spec         {:name    :throwing-task
                             :actions [{:type :custom
                                        :name :bad-action
                                        :fn   (fn []
                                                (throw (ex-info "Bad action" {})))}]}
          {:keys [task-fn]} (sut/compile-task (utils/eval-ctx) task-spec)]
      (is (thrown? Exception (task-fn {:config {} :state {}})))))

  (testing "Tasks retried on failure"
    (let [runs-count        (atom 0)
          task-spec         {:name    :throwing-task
                             :retry   {:max-retries 3}
                             :actions [{:type :custom
                                        :name :bad-action
                                        :fn   (fn []
                                                (swap! runs-count inc)
                                                (throw (ex-info "Bad action" {})))}]}
          {:keys [task-fn]} (sut/compile-task (utils/eval-ctx) task-spec)]
      (is (thrown? Exception (task-fn {:config {} :state {}})))
      ;; function will be called 4 times: 1 initial run + 3 retries
      (is (= @runs-count 4)))))


(deftest execute-task-test
  (testing "execute-task respects the state format option"
    (let [context {:state {:gh-repos [{:name "repo1"}
                                      {:name "repo2"}
                                      {:name "repo3"}
                                      {:name "repo4"}
                                      {:name "repo5"}]
                           :gh-prs   {"repo1" [{:title "PR1" :state "open"}
                                               {:title "PR2" :state "closed"}]
                                      "repo2" [{:title "PR3" :state "open"}
                                               {:title "PR4" :state "closed"}]
                                      "repo3" [{:title "PR5" :state "open"}
                                               {:title "PR6" :state "closed"}]
                                      "repo4" [{:title "PR7" :state "open"}
                                               {:title "PR8" :state "closed"}]
                                      "repo5" [{:title "PR9" :state "open"}
                                               {:title "PR10" :state "closed"}]}}}]
      (let [task   {:name         :gh-prs
                    :inputs       [:gh-repos :gh-prs]
                    :parallel     {:items   [:inputs :gh-repos]
                                   :threads 2}
                    :actions      [{:name      :fetch-gh-prs
                                    :type      :custom
                                    :selectors {'repo-name [:$parallel/item :name]
                                                'all-prs   [:state :gh-prs]}
                                    :params    ['all-prs 'repo-name]
                                    :fn        (fn [prs repo-name]
                                                 (get prs repo-name))}]
                    :state-format :flatten}
            result (sut/execute-task task {} context)]
        (is (= 10 (count result)))
        (is (= ["PR1" "PR2" "PR3" "PR4" "PR5" "PR6" "PR7" "PR8" "PR9" "PR10"]
               (map :title result))))

      (testing "with iterator only first result is calculated"
        (let [task   {:name         :gh-prs
                      :inputs       [:gh-repos :gh-prs]
                      :actions      [{:name      :repo
                                      :type      :mapper
                                      :selectors {'repos [:inputs :gh-repos]}
                                      :params    {:sequence 'repos}}
                                     {:name      :fetch-gh-prs
                                      :type      :custom
                                      :selectors {'repo-name [:$mapper/item :name]
                                                  'all-prs   [:state :gh-prs]}
                                      :params    ['all-prs 'repo-name]
                                      :fn        (fn [prs repo-name]
                                                   (get prs repo-name))}]
                      :iterator     {:next [:true? [:$mapper/has-next-item]]}
                      :state-format :flatten}
              result (sut/execute-task task {} context)]
          (is (= 2 (count result)))
          (is (= ["PR1" "PR2"]
                 (map :title result))))))))


(deftest arrow-task-result-batching-test
  (let [schema (arrow/normalize-schema
                {:version 1
                 :fields  [{:key :id :name "id" :type :int64}]})
        rows   (with-meta (map #(hash-map :id %) (range 8200))
                          {:arrow-columns schema})
        result (sut/handle-task-result :lazy-rows rows {:use-arrow true :keep-result true})
        data   (sut/arrow->dataset result)]
    (is (sut/arrow-task-result? result))
    (is (= [4096 4096 8] (mapv ds/row-count data)))
    (is (= {:id 0} (first (ds/rows (first data)))))
    (is (= {:id 8199} (last (ds/rows (last data)))))
    (is (= schema (:arrow-columns (meta data)))))
  (let [schema  (arrow/normalize-schema
                 {:version 1
                  :fields  [{:key :id :name "id" :type :int64}]})
        batches (with-meta [[] [{:id 1} {:id 2}] [] [{:id 3}]]
                           {:arrow-columns schema})
        result  (sut/handle-task-result :batched-rows batches {:use-arrow true :keep-result true})]
    (is (= [2 1] (mapv ds/row-count (sut/arrow->dataset result)))))
  (let [error (try
                (sut/handle-task-result :ambiguous
                                        [{:profile {:name "Ada"}}]
                                        {:use-arrow true :keep-result true})
                (catch clojure.lang.ExceptionInfo error
                  error))]
    (is (= :collet.error/arrow-schema-required
           (:collet.error/type (ex-data error)))))
  (let [fallback (with-meta (map identity [{:json {:a 1}}])
                            {:collet.arrow/skip-conversion? true})
        result   (sut/handle-task-result :jdbc-json-fallback
                                         fallback
                                         {:use-arrow true :keep-result true})]
    (is (not (sut/arrow-task-result? result)))
    (is (= [{:json {:a 1}}] (vec result)))
    (is (true? (-> result meta :collet.arrow/skip-conversion?))))
  (let [schema   (arrow/normalize-schema
                  {:version 1
                   :fields  [{:key :id :name "id" :type :int64}]})
        view     (with-meta [(ds/->dataset [{:id 1}])
                             (ds/->dataset [{:id 2} {:id 3}])]
                            {:ds-seq true})
        result   (sut/handle-task-result :arrow-view view {:use-arrow true :keep-result true})
        datasets (sut/arrow->dataset result)]
    (is (sut/arrow-task-result? result))
    (is (= [1 2] (mapv ds/row-count datasets)))
    (is (= [{:id 1} {:id 2} {:id 3}]
           (vec (mapcat ds/rows datasets))))
    (is (= schema (:arrow-columns (meta datasets))))))


(deftest arrow-task-result-consumer-test
  (let [schema   (arrow/normalize-schema
                  {:version 1
                   :fields  [{:key :id :name "id" :type :int64}
                             {:key    :profile
                              :name   "profile"
                              :type   :struct
                              :fields [{:key :name :name "name" :type :string}
                                       {:key :score :name "score" :type :int32}]}
                             {:key     :events
                              :name    "events"
                              :type    :list
                              :element {:type   :struct
                                        :fields [{:key :kind :name "kind" :type :string}]}}]})
        rows     (with-meta [{:id      1
                              :profile {:name "Ada" :score 7}
                              :events  [{:kind "created"}]}
                             {:id      2
                              :profile nil
                              :events  []}]
                            {:arrow-columns schema})
        result   (sut/handle-task-result :nested-rows rows {:use-arrow true :keep-result true})
        datasets (sut/arrow->dataset result)
        mapped   (mapper/map-sequence {:sequence datasets} nil)
        sliced   (slicer/concat-dataset-seq datasets)]
    (is (= {:id      1
            :profile {:name "Ada" :score 7}
            :events  [{:kind "created"}]}
           (:current mapped)))
    (is (= schema (:arrow-columns (meta sliced))))
    (is (= [{:id      1
             :profile {:name "Ada" :score 7}
             :events  [{:kind "created"}]}
            {:id 2 :profile nil :events []}]
           (mapv #(arrow/prep-record % schema) (ds/rows sliced))))))


(deftest action-tracing-preserves-the-callers-context-shape
  (let [action  (sut/compile-action (utils/eval-ctx) {:name :value :type :clj/identity :params [42]})
        context {:config {} :state {}}]
    (is (= {:config {} :state {:value 42}} (action context)))
    (let [caller-id (random-uuid)]
      (is (= caller-id (::caller (action (assoc context ::caller caller-id))))))))


(deftest failing-action-evaluates-parameters-once
  (let [counter (atom 0)
        action  (sut/compile-action (utils/eval-ctx)
                                    '{:name      :fail
                                      :type      :clj/nth
                                      :selectors {calls [:config :calls]}
                                      :params    [[0] (swap! calls inc)]})
        error   (try (action {:config {:calls counter} :state {}})
                     (catch Exception e e))]
    (is (= 1 @counter))
    (is (= :fail (:action (ex-data error))))
    (is (= [[0] 1] (:params (ex-data error))))))


(deftest action-dependencies-include-setup-and-nested-switches
  (is (= #{['collet.actions.http] ['collet.actions.jdbc]}
         (set (sut/get-actions-deps
               [{:setup   [{:name :token :type :collet.actions.http/oauth2}]
                 :actions [{:name   :route
                            :type   :switch
                            :params {:case [{:condition :default
                                             :actions   [{:name :write :type :collet.actions.jdbc/execute}]}]}}]}])))))


(deftest retry-backoff-normalizes-integer-multipliers
  (doseq [factor [2 2.0]]
    (let [calls (atom 0)
          task  (:task-fn (sut/compile-task (utils/eval-ctx)
                                            {:name    :retry
                                             :retry   {:max-retries 1 :backoff-ms [1 5 factor]}
                                             :actions [{:name :value
                                                        :type :custom
                                                        :fn   (fn [] (if (= 1 (swap! calls inc)) (throw (ex-info "retry" {})) 42))}]}))]
      (is (= 42 (task {:state {} :config {}})))
      (is (= 2 @calls)))))


(deftest config-spec-reports-paths-without-values
  (let [pipeline {:config-spec [:map [:service [:map [:secret :int]]]]}
        failure  (try (sut/validate-config! pipeline {:service {:secret "private-value"}})
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= "invalid-configuration" (:code failure)))
    (is (not (contains? failure :status)))
    (is (= [:config :service :secret] (get-in failure [:errors 0 :path])))
    (is (not (.contains (pr-str failure) "private-value")))
    (is (nil? (sut/validate-config! pipeline {:service {:secret 42}})))))


(deftest invalid-config-spec-reports-code-and-path
  (doseq [schema [::unknown-schema [:int {:default/fn '(fn [] 1)}]]]
    (is (= {:code "invalid-config-spec" :path [:config-spec]}
           (try (sut/compile-config-spec schema)
                (catch clojure.lang.ExceptionInfo e (ex-data e)))))))


(deftest config-spec-fills-literal-defaults-before-validation
  (let [pipeline {:config-spec [:map
                                [:service
                                 [:map [:url {:optional true} [:re {:default "https://fixture.invalid"} "\\Ahttps://[^/]+\\z"]]
                                  [:token :string]]]]}
        config   {:service {:token "synthetic" :other "preserved"}}
        default  (assoc-in config [:service :url] "https://fixture.invalid")
        override (assoc-in config [:service :url] "https://other.invalid")]
    (is (= default (sut/prepare-config! pipeline config)))
    (is (= override (sut/prepare-config! pipeline override)))
    (is (= {:service {:url "https://fixture.invalid"}} (sut/config-with-defaults pipeline {:service {}})))
    (is (= [:config :service :url]
           (try (sut/prepare-config! pipeline (assoc-in override [:service :url] "https://other.invalid/"))
                (catch clojure.lang.ExceptionInfo e (get-in (ex-data e) [:errors 0 :path])))))
    (is (thrown-with-msg? Exception
                          #"literal"
                          (sut/compile-config-spec [:string {:default/fn '(fn [] "unsafe")}])))))


(deftest action-error-preserves-the-cause-with-empty-dataset-batches
  (let [action {:name      :require-rows
                :type      :custom
                :selectors {'rows [:state :pages]}
                :params    ['rows]
                :fn        (fn [_] (throw (ex-info "No matching rows." {})))}
        error  (try
                 (sut/execute-action action {} {:state {:pages (with-meta [] {:ds-seq true})}})
                 (catch Exception e e))]
    (is (= "No matching rows."
           (some-> error
                   ex-cause
                   ex-message)))
    (is (= [[]] (:params (ex-data error))))))
