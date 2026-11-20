(ns collet.validation
  "Passive authoring checks. Never compile, resolve user vars, or load dependencies."
  (:require [clojure.string :as str]
            [collet.conditions :as conditions]
            [collet.core :as core]
            [collet.deps :as deps]
            [collet.select :as select]
            [malli.core :as m]))


(def schemas
  {:pipeline  core/pipeline-spec
   :task      core/task-spec
   :action    core/action-spec
   :selector  select/select-path
   :condition conditions/condition?
   :deps      deps/deps-spec})


(defn- dynamic? [x]
  (or (symbol? x) (list? x)))


(defn- contains-dynamic? [x]
  (cond (dynamic? x) true
        (map? x) (boolean (some contains-dynamic? (vals x)))
        (or (vector? x) (set? x)) (boolean (some contains-dynamic? x))
        :else false))


(defn- dynamic-path? [value path]
  (some #(dynamic? (get-in value (take % path))) (range (inc (count path)))))


(defn- shape-errors [schema value prefix]
  (mapv (fn [{:keys [in schema]}]
          (let [expected (pr-str (m/form schema))]
            {:code     "invalid-shape"
             :path     (into prefix in)
             :message  (str "Expected " expected ".")
             :expected expected}))
        (remove #(and (seq prefix) (dynamic-path? value (:in %))) (:errors (m/explain schema value)))))


(defn- parameter-errors [descriptor params path]
  (let [schema (:params-schema descriptor)]
    (if (and (= :slicer (:type descriptor)) (vector? (:apply params)))
      (let [branches (into {}
                           (keep (fn [x]
                                   (when (and (vector? x)
                                              (= :tuple (first x))
                                              (vector? (second x))
                                              (= := (first (second x))))
                                     [(second (second x)) x])))
                           (tree-seq coll? seq schema))]
        (into (shape-errors schema (assoc params :apply []) path)
              (mapcat (fn [[i operation]]
                        (if-let [branch (and (vector? operation) (get branches (first operation)))]
                          (shape-errors [:schema (second schema) branch] operation (into path [:apply i]))
                          [{:code    "unknown-operation"
                            :path    (into path [:apply i 0])
                            :message (str "Use one of " (pr-str (sort (keys branches))) ".")}]))
               (map-indexed vector (:apply params)))))
      (shape-errors schema params path))))


(defn- nested-actions [[path action :as item]]
  (cons item
        (mapcat (fn [[ci branch]]
                  (mapcat (fn [[ai child]]
                            (nested-actions [(into path [:params :case ci :actions ai]) child]))
                   (map-indexed vector (:actions branch))))
         (map-indexed vector (get-in action [:params :case])))))


(defn- action-checks [action path descriptors]
  (let [type       (:type action)
        descriptor (some #(when (= (:type %) type) %) descriptors)
        shorthand  (when (and (keyword? type) (namespace type) (not (str/includes? (namespace type) ".")))
                     (some #(when (and (= (name type) (name (:type %)))
                                       (= (namespace type) (last (str/split (namespace (:type %)) #"\."))))
                              (:type %))
                           (filter #(namespace (:type %)) descriptors)))
        errors     (into (shape-errors core/action-spec action path)
                         (concat (when-let [schema (:action-schema descriptor)] (shape-errors schema action path))
                                 (when (:params-schema descriptor)
                                   (parameter-errors descriptor (or (:params action) {}) (conj path :params)))))]
    {:errors   (cond-> errors
                 shorthand
                 (conj {:code     "qualified-action-type-required"
                        :path     (conj path :type)
                        :message  (str "Use the exact action type " shorthand ".")
                        :expected (str shorthand)}))
     :deferred (if (or (contains-dynamic? action) (nil? descriptor))
                 [{:code    "runtime-check-required"
                   :path    path
                   :message "Custom code, dynamic parameters, or an external action require preparation and execution to verify."}]
                 [])}))


(defn validate
  "Validate a DSL value with optional containing pipeline and package-owned descriptors."
  ([kind value]
   (validate kind value {}))
  ([kind value {:keys [pipeline descriptors]}]
   (if-let [schema (get schemas (keyword kind))]
     (let [kind           (keyword kind)
           errors         (atom (shape-errors schema value []))
           deferred       (atom [])
           warnings       (atom [])
           tasks          (case kind
                            :pipeline (:tasks value)
                            :task [value]
                            nil)
           graph-pipeline (if (and pipeline (= kind :task))
                            (update pipeline :tasks #(conj (vec (remove (fn [t] (= (:name t) (:name value))) %)) value))
                            (or pipeline value))
           actions        (mapcat nested-actions
                           (if (= :action kind)
                             [[[] value]]
                             (for [[ti task]   (map-indexed vector tasks)
                                   group       [:setup :actions]
                                   [ai action] (map-indexed vector (flatten (get task group)))]
                               [(cond-> []
                                  (= :pipeline kind) (into [:tasks ti])
                                  true (into [group ai]))
                                action])))]
       (when (and (= :pipeline kind) (empty? @errors) (contains? value :config-spec))
         (try (core/compile-config-spec (:config-spec value))
              (catch Exception e
                (swap! errors conj
                  (assoc (select-keys (ex-data e) [:code :path])
                    :message (ex-message e))))))
       (when (and (empty? @errors) (or (= :pipeline kind) pipeline))
         (try (core/validate-task-dependencies! (:tasks graph-pipeline))
              (catch Exception e
                (swap! errors conj
                  {:code    (name (or (:problem (ex-data e)) :invalid-task-graph))
                   :path    [:tasks]
                   :message (ex-message e)}))))
       (doseq [[ti task] (map-indexed vector tasks)]
         (let [names (map :name (concat (:setup task) (flatten (:actions task))))]
           (when (some #(> % 1) (vals (frequencies names)))
             (swap! warnings conj
               {:code    "duplicate-action-name"
                :path    (if (= :pipeline kind) [:tasks ti] [])
                :message "Repeated action names share state within a task; verify this is intended."}))))
       (doseq [[path action] actions
               :when         (map? action)]
         (let [checks (action-checks action path descriptors)]
           (swap! errors into (:errors checks))
           (swap! deferred into (:deferred checks))))
       (when (or (seq actions) (#{:task :selector :condition} kind))
         (swap! deferred conj
           {:code    "inputs-not-executed"
            :path    []
            :message "Input fields and runtime behaviour have not been verified by this check."}))
       {:valid (empty? @errors) :kind (name kind) :errors @errors :warnings @warnings :deferred @deferred})
     {:valid    false
      :errors   [{:code    "unknown-validation-kind"
                  :path    []
                  :message "Use pipeline, task, action, selector, condition, or deps."}]
      :warnings []
      :deferred []})))
