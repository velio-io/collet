(ns collet.workbench.knowledge
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [collet.validation :as validation]
            [collet.workbench.data :as data]
            [collet.workbench.wire :as wire]
            [malli.core :as m]))


(defn- resource-or-source [resource source]
  (or (io/resource resource)
      (some #(let [file (io/file % source)] (when (.isFile file) file)) ["." ".."])))


(defn- compatibility []
  (when-let [resource (resource-or-source "collet/knowledge/compatibility.edn"
                                          "target/workbench-knowledge/collet/knowledge/compatibility.edn")]
    (edn/read-string (slurp resource))))


(defn- slug [title]
  (-> title str/lower-case (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-|-$" "")))


(defn- sections [text]
  (loop [lines  (str/split-lines text)
         fence? false
         title  "Introduction"
         id     "introduction"
         body   []
         result []
         seen   {}]
    (if-let [line (first lines)]
      (if-let [[_ heading] (when-not fence? (re-matches #"#{1,4} +(.+)" line))]
        (let [base (slug heading)
              n    (get seen base 0)]
          (recur (next lines)
                 fence?
                 heading
                 (str base (when (pos? n) (str "-" n)))
                 []
                 (cond-> result (seq body) (conj {:id id :title title :text (str/join "\n" body)}))
                 (update seen base (fnil inc 0))))
        (recur (next lines) (if (str/starts-with? line "```") (not fence?) fence?) title id (conj body line) result seen))
      (cond-> result (seq body) (conj {:id id :title title :text (str/join "\n" body)})))))


(def ^:private catalogue
  (delay
   (let [index         (edn/read-string (slurp (io/resource "collet/knowledge/index.edn")))
         actions       (vec (mapcat (fn [package]
                                      (when-let [resource (resource-or-source (str "collet/catalogue/" package ".edn")
                                                                              (str package "/resources/collet/catalogue/" package ".edn"))]
                                        (edn/read-string (slurp resource))))
                             (:catalogues index)))
         docs          (into {}
                             (map (fn [{:keys [id path] :as entry}]
                                    (let [resource (resource-or-source (str "collet/knowledge/docs/" id ".md") path)]
                                      (when-not resource (throw (ex-info "Missing packaged documentation." {:document id})))
                                      (let [text (slurp resource)]
                                        [id
                                         (assoc entry
                                           :text text
                                           :sections (sections text))])))
                                  (:docs index)))
         compatibility (compatibility)]
     {:index         index
      :actions       actions
      :by-type       (into {} (map (juxt :type identity)) actions)
      :docs          docs
      :compatibility compatibility
      :schemas       (into {}
                           (map (fn [[kind schema]]
                                  [kind
                                   (data/source (walk/postwalk #(cond (fn? %) 'runtime-predicate
                                                                      (m/schema? %) (m/form %)
                                                                      :else %)
                                                               (m/form schema)))])
                                validation/schemas))})))


(defn index []
  (:index @catalogue))


(defn actions []
  (:actions @catalogue))


(defn schemas []
  (:schemas @catalogue))


(defn build-info []
  (or (:build (:compatibility @catalogue)) {:version "development" :distribution "source"}))


(defn- action-availability [action]
  (let [present? (boolean (or (io/resource (str (str/replace (:namespace action) "." "/") ".clj"))
                              (io/resource (str (str/replace (:namespace action) "." "/") "__init.class"))))]
    (assoc action
      :dependency-present present?
      :bundled (= "collet-core" (:package action))
      :availability (if present? "classpath-present" "dependency-required"))))


(def ^:private core-examples
  {"identity" {:params [1] :effects "pure"}
   "slurp"    {:params ["https://example.com/data.json"] :effects "external read: file or network"}
   "spit"     {:params ["/tmp/output.txt" "hello"] :effects "external write: replaces a file"}
   "println"  {:params ["hello"] :effects "writes standard output"}
   "count"    {:params [[1 2 3]] :effects "may realize a lazy sequence"}
   "get"      {:params [{:amount 12} :amount] :effects "pure"}})


(defn describe [type]
  (let [type (keyword (str/replace-first (str type) #"^:" ""))]
    (if-let [action (get-in @catalogue [:by-type type])]
      (let [version  (get-in @catalogue [:compatibility :packages (:coordinate action) :version])
            bundled? (= "collet-core" (:package action))]
        (assoc (action-availability action)
          :version version
          :notes
          (str
           (:notes action)
           " Prefer catalogue built-in actions, then :clj/* core functions; reserve inline EDN forms for domain logic. :coordinates supplies Maven libraries; :requires loads namespaces; :imports exposes classes. classpath-present describes the current process only, not deployment portability.")
          :dependencyEdn (cond bundled? "{}"
                               version (pr-str {:coordinates [[(symbol (:coordinate action)) version]]
                                                :requires    [[(symbol (:namespace action))]]}))
          :distribution (cond bundled? "Bundled with Collet. No pipeline dependency is required."
                              version
                              "Declare these coordinates even when classpath-present. Exact build versions must be available in the deployment's Maven repository; development artifacts may need local installation."
                              :else "Version unresolved. Run bb workbench:prepare before starting source Workbench, then restart it. Do not invent a version or claim deployment portability from classpath-present.")))
      (cond
        (= "clj" (namespace type))
        (let [v        (ns-resolve 'clojure.core (symbol (name type)))
              metadata (meta v)
              example  (get core-examples (name type))]
          (when-not (and v (not (:macro metadata)) (fn? @v))
            (throw (ex-info "Unknown callable clojure.core action."
                            {:status 404
                             :code   "unknown-action"
                             :hint   "Use a public clojure.core function or discover a catalogue action."})))
          (cond-> {:type               type
                   :effects            (or (:effects example) "unknown; depends on function and arguments")
                   :dependency-present true
                   :availability       "classpath-present"
                   :purpose            (wire/clip (or (:doc metadata) "Call this clojure.core function with positional :params.") 1200)
                   :arglists           (pr-str (:arglists metadata))
                   :params-schema      [:vector :any]
                   :documentation      "collet://docs/extensibility"}
            example (assoc :example (pr-str {:name :value :type type :params (:params example)}))))
        (= :custom type)
        {:type type
         :effects "unknown"
         :availability "classpath-present"
         :dependency-present true
         :purpose "Evaluate :fn with SCI during preparation. Parameters and selectors use the usual action contract."
         :example "{:name :double :type :custom :fn (fn [x] (* x 2)) :params [3]}"
         :notes
         "Prefer a catalogue action, then a :clj/* core function. Use custom only for domain logic not covered by those actions. EDN function forms and qualified symbols serialize durably; live function objects and closures do not. :coordinates fetches libraries; :requires loads namespaces; :imports exposes Java classes. SCI does not expose slurp/spit by default."
         :documentation "collet://docs/extensibility"}
        (str/ends-with? (name type) ".edn")
        {:type          type
         :effects       "unknown"
         :availability  "deferred"
         :purpose       "Read a file-backed definition during preparation. Passive discovery never opens that file."
         :documentation "collet://docs/extensibility"}
        :else (throw (ex-info "Action is not in the bundled catalogue."
                              {:status 404
                               :code   "unknown-action"
                               :hint   "Search collet_list_actions by purpose or use an exact action type."}))))))


(defn- tokens [query]
  (remove #{"a" "an" "the" "to" "and" "or" "how" "do" "i" "with" "from"}
          (re-seq #"[a-z0-9]+" (str/lower-case (or query "")))))


(defn- score [query words text exact]
  (+ (if (= (str/lower-case (or query "")) exact) 1000 0)
     (if (empty? words) 1 (count (filter #(str/includes? text %) words)))))


(defn list-actions [request]
  (let [{:keys [query limit cursor availability effects] :as opts} (if (map? request) request {:query request :limit 100})
        words    (tokens query)
        ranked   (->> (actions)
                      (map (fn [a] [(score query words (str/lower-case (str (:type a) " " (:purpose a) " " (:tags a) " " (:package a))) (str (:type a))) a]))
                      (filter #(pos? (first %)))
                      (sort-by (fn [[n a]] [(- n) (str (:type a))]))
                      (map (comp #(select-keys % [:type :purpose :effects :availability :dependency-present :package]) action-availability second)))
        selected (filter #(and (or (nil? availability) (= availability (:availability %)))
                               (or (nil? effects) (str/includes? (str/lower-case (:effects %)) (str/lower-case effects))))
                         ranked)
        offset   (or (some-> cursor
                             parse-long)
                     0)
        items    (map-indexed #(assoc %2 :cursor (str (inc %1))) selected)
        result   (wire/page :actions (take (inc (or limit 20)) (drop offset items)) opts)]
    ;; Availability is computed against the current classpath, never cached or required.
    (assoc result
      :families [{:type ":clj/*" :purpose "Use a public Clojure core function when no catalogue action fits; describe the exact type"}
                 {:type ":custom" :purpose "Durable EDN function forms for domain logic only; prefer catalogue actions and core functions"}
                 {:type "qualified/*.edn" :purpose "File-backed definitions"}])))


(defn- chunk-text [text cursor max-chars]
  (let [offset (or (some-> cursor
                           parse-long)
                   0)]
    (when-not (<= 0 offset (count text)) (throw (ex-info "Invalid documentation cursor." {:status 400 :code "invalid-cursor"})))
    (let [end (min (count text) (+ offset max-chars))]
      (cond-> {:text (subs text offset end)}
        (< end (count text)) (assoc :nextCursor
                               (str end) :truncated
                               true)))))


(defn documentation [request]
  (let [{:keys [id section query cursor limit full maxBytes] :as opts}
        (if (map? request) request {:id request :full true})]
    (cond
      query
      (let [words   (tokens query)
            matches (->> (:docs @catalogue)
                         (mapcat (fn [[id doc]] (map #(assoc % :document id) (:sections doc))))
                         (map #(assoc % :score (score query words (str/lower-case (str (:title %) " " (:text %))) "")))
                         (filter #(pos? (:score %)))
                         (sort-by (juxt (comp - :score) :document :id))
                         (map-indexed (fn [i x]
                                        {:document (:document x)
                                         :section  (:id x)
                                         :title    (:title x)
                                         :excerpt  (wire/clip (:text x) 240)
                                         :uri      (str "collet://docs/" (:document x) "/" (:id x))
                                         :cursor   (str (inc i))})))
            offset  (or (some-> cursor
                                parse-long)
                        0)]
        (wire/page :matches (take (inc (or limit 20)) (drop offset matches)) opts))

      (nil? id)
      {:documents (mapv #(assoc (dissoc % :path) :uri (str "collet://docs/" (:id %))) (:docs (index)))}

      :else
      (let [doc      (or (get-in @catalogue [:docs id]) (throw (ex-info "Documentation not found." {:status 404 :code "document-not-found"})))
            base     {:id id :title (:title doc) :uri (str "collet://docs/" id)}
            selected (when section (some #(when (= section (:id %)) %) (:sections doc)))]
        (when (and section (nil? selected))
          (throw (ex-info "Section not found. Read the document outline." {:status 404 :code "section-not-found"})))
        (if (or selected full (= id "getting-started"))
          (merge base
                 (when section {:section section :uri (str (:uri base) "/" section)})
                 (chunk-text (if selected (:text selected) (:text doc)) cursor (min 8192 (quot (or maxBytes 16384) 4))))
          (assoc base
            :text (wire/clip (:text (first (:sections doc))) 700)
            :sections (mapv #(assoc (select-keys % [:id :title]) :uri (str (:uri base) "/" (:id %))) (:sections doc))))))))


(defn check [{:keys [kind edn pipelineEdn]}]
  (try
    (validation/validate (or kind "pipeline")
                         (data/read-edn edn)
                         {:pipeline (when pipelineEdn (data/read-edn pipelineEdn)) :descriptors (actions)})
    (catch Exception e
      {:valid    false
       :errors   [(merge {:code "invalid-edn" :path [] :message (ex-message e)}
                         (select-keys (ex-data e) [:line :column]))]
       :warnings []
       :deferred []})))


(defn resource [uri]
  (cond
    (str/starts-with? uri "collet://docs/")
    (let [[path query] (str/split (subs uri 14) #"\?" 2)
          [id section] (str/split path #"/" 2)
          cursor       (when query (second (re-matches #"cursor=(\d+)" query)))
          doc          (documentation {:id id :section section :cursor cursor})]
      (str (wire/text doc)
           (when (:nextCursor doc) (str "\nContinue resource: " (:uri doc) "?cursor=" (:nextCursor doc)))))
    (str/starts-with? uri "collet://actions/") (data/source (describe (subs uri 17)))
    (str/starts-with? uri "collet://schemas/") (or (get (schemas) (keyword (subs uri 17))) (throw (ex-info "Schema not found." {:status 404})))
    :else (throw (ex-info "Resource not found." {:status 404}))))
