(ns collet.workbench.data
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [collet.core :as collet]
            [collet.stream :as stream]
            [collet.utils :as utils]
            [collet.validation :as validation]
            [tech.v3.dataset :as ds]
            [zprint.core :as zprint])
  (:import [java.io StringReader]
           [clojure.lang LineNumberingPushbackReader]
           [java.util UUID]
           [java.util.regex Pattern]))


(defn label [x]
  (if (keyword? x) (subs (str x) 1) (str x)))


(defn- read-regex [value]
  (if (string? value)
    (re-pattern value)
    (Pattern/compile (:pattern value) (int (:flags value)))))


(defn read-edn [text]
  (when-not (and (string? text) (<= (count text) 262144))
    (throw (ex-info "Provide EDN text smaller than 256 KiB." {:status 400})))
  (with-open [reader (LineNumberingPushbackReader. (StringReader. text))]
    (let [options {:eof ::eof :readers {'rgx read-regex}}
          value   (edn/read options reader)]
      (when (or (= ::eof value)
                (not= ::eof (edn/read options reader)))
        (throw (ex-info "Provide exactly one EDN value." {:status 400})))
      value)))


(defn format-edn [text]
  (when-not (and (string? text) (<= (count text) 262144))
    (throw (ex-info "Provide EDN text smaller than 256 KiB." {:status 400})))
  (zprint/zprint-str text
                     {:parse-string-all? true
                      :width             88
                      :style             :community
                      :map               {:indent 0 :comma? false :justify? true :sort? false}
                      :binding           {:indent 0 :justify? true}
                      :vector            {:wrap-after-multi? false}
                      :comment           {:wrap? false}}))


(defn source [value]
  (str (pr-str (walk/postwalk #(if (instance? Pattern %)
                                 (tagged-literal 'rgx {:pattern (.pattern ^Pattern %) :flags (.flags ^Pattern %)})
                                 %)
                              value))
       "\n"))


(defn authoring-spec [plan]
  (if-let [source (:collet.workbench/source plan)]
    (assoc (read-edn source) :version (:version plan))
    (if-not (:version plan)
      plan
      ;; Older revisions contain only compiled data, including expanded parallel paths.
      (-> (walk/postwalk #(if (and (map? %) (= :regex (:collet.runtime/type %))) (read-regex %) %) plan)
          (update :tasks
                  (fn [tasks]
                    (mapv (fn [task]
                            (if-not (:parallel task)
                              task
                              (walk/postwalk
                               #(if (and (vector? %) (= [:state :$parallel/item] (vec (take 2 %))))
                                  (into [:$parallel/item] (drop 2 %))
                                  %)
                               task)))
                          tasks)))))))


(defn compile-plan
  ([spec]
   (compile-plan spec (source (dissoc spec :version :collet.workbench/source))))
  ([spec edn]
   (assoc (collet/compile-pipeline spec) :collet.workbench/source edn)))


(defn- bounded
  ([value]
   (bounded value true))
  ([value consume-deferred?]
   (let [nodes      (volatile! 2000)
         characters (volatile! 32768)
         clipped    (volatile! false)
         deferred   (volatile! false)
         clip       (fn [] (vreset! clipped true) "…")]
     (letfn
       [(visit [x depth]
          (cond
            (or (> depth 6) (neg? (vswap! nodes dec)) (neg? @characters)) (clip)
            (and (not consume-deferred?)
                 (or (instance? clojure.lang.LazySeq x) (:ds-seq (meta x))))
            (do (vreset! deferred true) "<deferred sequence; inspect the retained task output>")
            (string? x) (let [n (min 4000 (max 0 @characters) (count x))]
                          (vswap! characters - n)
                          (when (< n (count x)) (vreset! clipped true))
                          (subs x 0 n))
            (map? x) (do (when (> (count x) 50) (vreset! clipped true))
                         (into {} (map (fn [[k v]] [k (visit v (inc depth))])) (take 50 x)))
            (or (sequential? x) (set? x))
            (loop [tail  (seq x)
                   items []]
              (cond
                (nil? tail) items
                (or (= 100 (count items)) (not (pos? @characters)) (not (pos? @nodes)))
                (do (vreset! clipped true) items)
                :else (let [item (visit (first tail) (inc depth))]
                        (recur (next tail) (conj items item)))))
            (or (nil? x)
                (boolean? x)
                (number? x)
                (keyword? x)
                (symbol? x)
                (instance? UUID x)
                (inst? x)) x
            (instance? Throwable x) (visit (ex-message x) depth)
            :else (str "<" (.getName (class x)) ">")))]
       (let [result (visit value 0)] {:value result :truncated @clipped :deferred @deferred})))))


(defn- json-value [value]
  (cond
    (map? value) (into {} (map (fn [[k v]] [(label k) (json-value v)])) value)
    (sequential? value) (mapv json-value value)
    (or (nil? value) (boolean? value) (number? value) (string? value)) value
    :else (str value)))


(defn safe-value [value]
  (json-value (:value (bounded value false))))


(def ^:private error-status
  {"invalid-config-spec"              400
   "invalid-cursor"                   400
   :collet.error/invalid-draft-source 400
   "pipeline-not-found"               404
   :collet.error/draft-not-found      404
   "invalid-configuration"            409
   "pipeline-busy"                    409
   "pipeline-changed"                 409
   :draft-conflict                    409
   :collet.error/draft-already-exists 409})


(defn error-details [error]
  (let [{:keys [code status] :collet.error/keys [type] :as details} (ex-data error)]
    (if-let [http-status (and (nil? status) (error-status (or code type)))]
      (cond-> (assoc details :status http-status)
        (nil? code) (dissoc :collet.error/type))
      details)))


(defn error-data [error]
  {:message (or (ex-message error) "Execution failed")
   :details (safe-value (error-details error))
   :causes  (->> (iterate ex-cause error)
                 (take-while some?)
                 (take 6)
                 (mapv #(or (ex-message %) (str (class %)))))})


(defn- selector-paths
  ([value]
   (vec (take 500 (selector-paths value [] [] 0))))
  ([value path selector depth]
   (when (< depth 6)
     (mapcat (fn [[k v]]
               (let [path'     (conj path (if (number? k) k (label k)))
                     selector' (conj selector k)]
                 (cons {:path path' :selector (pr-str selector')}
                       (selector-paths v path' selector' (inc depth)))))
      (cond (map? value) (take 50 value)
            (sequential? value) (map-indexed vector (take 100 value)))))))


(defn preview [value]
  (let [row-context? (or (ds/dataset? value) (utils/ds-seq? value))
        records      (or row-context? (sequential? value))
        projection   (bounded (cond (ds/dataset? value) (or (seq (ds/rows value)) [])
                                    (utils/ds-seq? value) (stream/rows value)
                                    :else value))
        shown        (:value projection)
        rows         (when (and records (seq shown) (every? map? shown)) shown)
        columns      (when rows (vec (take 30 (distinct (mapcat keys rows)))))
        printable    (source shown)]
    {:value           (json-value shown)
     :edn             (subs printable 0 (min 50000 (count printable)))
     :columns         (mapv label columns)
     :representation  (cond (ds/dataset? value) "dataset"
                            (utils/ds-seq? value) "dataset-batches"
                            :else "value")
     :selectorContext (when row-context? "mapper-row")
     :columnSelectors (mapv #(pr-str (if row-context? [:$mapper/item %] [0 %])) columns)
     :selectors       (if row-context?
                        (mapv #(assoc % :selector (pr-str (into [:$mapper/item] (rest (read-edn (:selector %))))))
                              (filter #(> (count (:path %)) 1) (selector-paths shown)))
                        (selector-paths shown))
     :rows            (when rows (mapv #(mapv (fn [k] (json-value (get % k))) columns) rows))
     :count           (when records (count shown))
     :truncated       (boolean (or (:truncated projection)
                                   (> (count printable) 50000)
                                   (and rows (> (count (distinct (mapcat keys rows))) 30))))
     :type            (cond rows "table"
                            records "sequence"
                            (map? value) "map"
                            :else "scalar")}))


(defn observed-value [value]
  (:value (bounded (if (ds/dataset? value) (vec (take 100 (ds/rows value))) value) false)))


(defn observation-preview
  "Build an inspector preview without realizing live sequences."
  [value]
  (if (ds/dataset? value)
    (preview value)
    (let [projection (bounded value false)
          result     (preview (:value projection))]
      (assoc result
        :deferred (:deferred projection)
        :truncated (or (:truncated projection) (:truncated result))))))


(defn validate [spec]
  (when-not (map? spec)
    (throw (ex-info "A pipeline definition must be an EDN map." {:status 400})))
  (let [result (validation/validate :pipeline spec)]
    (when-not (:valid result)
      (throw (ex-info "Invalid pipeline definition." (assoc result :status 400)))))
  spec)


(defn document
  ([definition]
   (document definition {}))
  ([definition {:keys [overview? raw]}]
   (let [spec (authoring-spec definition)]
     (cond-> {:name    (label (:name spec))
              :version (:version spec)
              :tasks   (mapv (fn [task]
                               {:name      (label (:name task))
                                :inputs    (mapv label (:inputs task))
                                :complex   (boolean (or (:iterator task) (:parallel task)))
                                :keepState (boolean (:keep-state task))
                                :actions   (mapv (fn [index action]
                                                   (cond-> {:index index
                                                            :name  (label (:name action))
                                                            :type  (label (:type action))}
                                                     (not overview?) (assoc :edn (source action))))
                                                 (range)
                                                 (flatten (:actions task)))})
                             (:tasks spec))}
       (not overview?) (assoc :edn (or raw (:collet.workbench/source definition) (source spec)))))))


(defn- fresh-name [prefix names]
  (first (remove (set names) (map #(keyword (str prefix %)) (range 1 10000)))))


(defn edit [spec {:keys [op task source target index direction actionEdn taskEdn]}]
  (let [task-name  (some-> task
                           keyword)
        task-index (first (keep-indexed #(when (= task-name (:name %2)) %1) (:tasks spec)))
        task-path  [:tasks task-index]
        flat!      (fn []
                     (when-not (every? map? (get-in spec (conj task-path :actions)))
                       (throw (ex-info "Edit nested action groups in EDN." {:status 400}))))
        remove-at  (fn [xs i] (vec (concat (take i xs) (drop (inc i) xs))))]
    (when (and task (nil? task-index))
      (throw (ex-info "Task no longer exists." {:status 409})))
    (when (and (#{"replace-action" "remove-action" "move-action"} op)
               (not (and (nat-int? index) (< index (count (get-in spec (conj task-path :actions)))))))
      (throw (ex-info "Action position no longer exists." {:status 409 :code "action-not-found"})))
    (when (#{"connect" "disconnect"} op)
      (doseq [name [source target]]
        (when-not (some #(= (keyword name) (:name %)) (:tasks spec))
          (throw (ex-info "Connection task does not exist." {:status 409 :code "task-not-found" :task name})))))
    (validate
     (case op
       "add-task" (let [added (if taskEdn
                                (read-edn taskEdn)
                                {:name       (fresh-name "task-" (map :name (:tasks spec)))
                                 :keep-state true
                                 :actions    [{:name :value :type :clj/identity :params [nil]}]})]
                    (when (some #(= (:name added) (:name %)) (:tasks spec))
                      (throw (ex-info "Task name already exists." {:status 409 :code "task-name-conflict"})))
                    (update spec :tasks conj added))
       "replace-task" (assoc-in spec task-path (read-edn taskEdn))
       "remove-task" (update spec
                             :tasks
                             #(mapv (fn [t] (update t :inputs (fn [xs] (vec (remove #{task-name} xs)))))
                                    (remove (fn [t] (= task-name (:name t))) %)))
       "connect" (update spec
                         :tasks
                         #(mapv (fn [t]
                                  (if (= (keyword target) (:name t))
                                    (update t :inputs (fn [xs] (vec (distinct (conj (vec xs) (keyword source))))))
                                    t))
                                %))
       "disconnect" (update spec
                            :tasks
                            #(mapv (fn [t]
                                     (if (= (keyword target) (:name t))
                                       (update t :inputs (fn [xs] (vec (remove #{(keyword source)} xs))))
                                       t))
                                   %))
       "add-action" (let [path    (conj task-path :actions)
                          actions (get-in spec path)
                          at      (if (some? index) (do (flat!) index) (count actions))
                          action  (if actionEdn
                                    (read-edn actionEdn)
                                    {:name   (fresh-name "step-" (map :name (flatten actions)))
                                     :type   :clj/identity
                                     :params [nil]})
                          _ (when (some #{(:name action)} (map :name (flatten actions)))
                              (throw (ex-info "Action name already exists." {:status 409 :code "action-name-conflict"})))]
                      (when-not (and (integer? at) (<= 0 at (count actions)))
                        (throw (ex-info "Action position no longer exists." {:status 409})))
                      (assoc-in spec path (into (conj (vec (take at actions)) action) (drop at actions))))
       "replace-action" (do (flat!) (assoc-in spec (conj task-path :actions index) (read-edn actionEdn)))
       "remove-action" (do (flat!) (update-in spec (conj task-path :actions) remove-at index))
       "move-action" (do (flat!)
                         (let [path  (conj task-path :actions)
                               xs    (get-in spec path)
                               other (+ index (if (= direction "up") -1 1))]
                           (if (<= 0 other (dec (count xs)))
                             (assoc-in spec
                              path
                              (assoc xs
                                index (nth xs other)
                                other (nth xs index)))
                             spec)))
       (throw (ex-info "Unknown graph edit." {:status 400}))))))
