(ns collet.actions.sparql
  (:require [charred.api :as json]
            [clojure.string :as str]
            [collet.action :as action]
            [collet.actions.http :as http]
            [com.yetanalytics.flint :as flint]
            [com.yetanalytics.flint.axiom.protocol :as fp])
  (:import [java.io ByteArrayOutputStream InputStream StringReader StringWriter]
           [java.net URI]
           [java.nio.charset StandardCharsets]
           [org.eclipse.rdf4j.model BNode IRI Literal Resource Value]
           [org.eclipse.rdf4j.model.impl SimpleValueFactory]
           [org.eclipse.rdf4j.model.datatypes XMLDatatypeUtil]
           [org.eclipse.rdf4j.rio ParserConfig RDFFormat Rio]
           [org.eclipse.rdf4j.rio.helpers BasicParserSettings NTriplesUtil]))


(defn- fail! [code message]
  (throw (ex-info message {:code code :collet/redact-params true})))


(defn- iri [value]
  (when-not (and (string? value) (not (re-find #"[\s<>\"\\{}|^`]" value)))
    (fail! :sparql/invalid-iri "Invalid RDF IRI."))
  (let [uri (URI. value)]
    (when-not (.isAbsolute uri) (fail! :sparql/invalid-iri "RDF IRIs must be absolute."))
    uri))


(defrecord EscapedLiteral [^Literal literal]
  fp/Literal
  (-valid-literal? [_] true)
  (-format-literal [_] (NTriplesUtil/toNTriplesString literal))
  (-format-literal [_ _opts] (NTriplesUtil/toNTriplesString literal))
  (-format-literal-strval [_] (.getLabel literal))
  (-format-literal-lang-tag [_] (.orElse (.getLanguage literal) nil))
  (-format-literal-url [_] (str "<" (.stringValue (.getDatatype literal)) ">"))
  (-format-literal-url [this _opts] (fp/-format-literal-url this)))


(defn- literal [{:keys [value datatype language]}]
  (when-not (and (string? value)
                 (or (not (and datatype language))
                     (= datatype "http://www.w3.org/1999/02/22-rdf-syntax-ns#langString")))
    (fail! :sparql/invalid-literal "RDF literals require a string value; language literals may only have the rdf:langString datatype."))
  (when (and language (not (and (string? language) (re-matches #"[A-Za-z]+(?:-[A-Za-z0-9]+)*" language))))
    (fail! :sparql/invalid-literal "Invalid RDF language tag."))
  (let [vf (SimpleValueFactory/getInstance)]
    (->EscapedLiteral
     (cond language (.createLiteral vf ^String value ^String language)
           datatype (do (iri datatype) (.createLiteral vf ^String value (.createIRI vf ^String datatype)))
           :else (.createLiteral vf ^String value)))))


(defn- terms [form]
  (cond
    (and (map? form) (:rdf/type form))
    (case (:rdf/type form)
      :iri (iri (:value form))
      :literal (literal form)
      :bnode (let [id (:value form)]
               (when-not (and (string? id) (re-matches #"[A-Za-z0-9_][A-Za-z0-9_.-]*" id))
                 (fail! :sparql/invalid-bnode "Invalid blank node identifier."))
               (symbol (str "_" id)))
      (fail! :sparql/invalid-term "Unknown RDF term type."))
    (string? form) (if (and (str/starts-with? form "<") (str/ends-with? form ">"))
                     form
                     (literal {:value form}))
    ;; Flint's one-entry language maps also accept unescaped application text.
    (and (map? form)
         (= 1 (count form))
         (keyword? (ffirst form))
         (string? (val (first form)))
         (not (str/starts-with? (val (first form)) "<")))
    (literal {:language (name (ffirst form)) :value (val (first form))})
    (map? form) (into (empty form) (map (fn [[k v]] [k (terms v)])) form)
    (vector? form) (mapv terms form)
    (list? form) (apply list (map terms form))
    (set? form) (into #{} (map terms) form)
    :else form))


(defn- query-kind [{:keys [query query-kind]}]
  (let [inferred (when (map? query)
                   (some (fn [[key kind]] (when (contains? query key) kind))
                         [[:select :select] [:select-distinct :select] [:select-reduced :select]
                          [:ask :ask] [:construct :construct] [:describe :describe]]))
        kind     (or inferred query-kind)]
    (when-not (and (#{:select :ask :construct :describe} kind)
                   (or (nil? query-kind) (= kind query-kind)))
      (fail! :sparql/invalid-query-kind "Raw queries require :query-kind; it must agree with Flint query data."))
    kind))


(defn format-statement
  "Validate and format exactly one :query or :update. Strings are complete SPARQL, never templates."
  [{:keys [query update pretty?] :as options}]
  (when (= (contains? options :query) (contains? options :update))
    (fail! :sparql/invalid-statement "Supply exactly one of :query or :update."))
  (when (contains? options :query) (query-kind options))
  (let [statement (if (contains? options :query) query update)]
    (if (string? statement)
      (if (str/blank? statement) (fail! :sparql/empty-statement "SPARQL must not be blank.") statement)
      (try
        (cond
          (contains? options :query) (flint/format-query (terms statement) :pretty? (boolean pretty?))
          (vector? statement) (do (when (empty? statement) (fail! :sparql/empty-statement "Update list must not be empty."))
                                  (flint/format-updates (mapv terms statement) :pretty? (boolean pretty?)))
          :else (flint/format-update (terms statement) :pretty? (boolean pretty?)))
        (catch Exception e
          (fail! (or (:code (ex-data e)) :sparql/invalid-statement) "Invalid SPARQL data; check RDF terms, prefixes and expression syntax."))))))


(defn- header [response wanted]
  (some (fn [[k v]]
          (when (= wanted (str/lower-case (name k)))
            (if (sequential? v) (first v) v)))
        (:headers response)))


(defn- read-body [body limit]
  (when-not (pos-int? limit) (fail! :sparql/invalid-limit ":max-response-bytes must be positive."))
  (if (instance? InputStream body)
    (with-open [in  ^InputStream body
                out (ByteArrayOutputStream.)]
      (let [buffer (byte-array 8192)]
        (loop [total 0]
          (let [n (.read in buffer)]
            (if (neg? n)
              (.toString out StandardCharsets/UTF_8)
              (do (when (> (+ total n) limit) (fail! :sparql/response-too-large "SPARQL response exceeds :max-response-bytes."))
                  (.write out buffer 0 n)
                  (recur (+ total n))))))))
    (let [s (cond (nil? body) ""
                  (bytes? body) (String. ^bytes body StandardCharsets/UTF_8)
                  :else (str body))]
      (when (> (alength (.getBytes s StandardCharsets/UTF_8)) limit)
        (fail! :sparql/response-too-large "SPARQL response exceeds :max-response-bytes."))
      s)))


(defn- rdf-term [^Value value]
  (cond
    (instance? IRI value) {:rdf/type :iri :value (.stringValue value)}
    (instance? BNode value) {:rdf/type :bnode :value (.getID ^BNode value)}
    (instance? Literal value)
    (cond-> {:rdf/type :literal
             :value    (.getLabel ^Literal value)
             :datatype (.stringValue (.getDatatype ^Literal value))}
      (.isPresent (.getLanguage ^Literal value))
      (assoc :language (.get (.getLanguage ^Literal value))))))


(defn- binding-term [{:keys [type value datatype] language :xml:lang}]
  (when-not (and (string? value) (#{"uri" "bnode" "literal" "typed-literal"} type))
    (fail! :sparql/invalid-response "Invalid SPARQL result binding."))
  (case type
    "uri" (do (iri value) {:rdf/type :iri :value value})
    "bnode" {:rdf/type :bnode :value value}
    (cond-> {:rdf/type :literal
             :value    value
             :datatype (or datatype
                           (if language
                             "http://www.w3.org/1999/02/22-rdf-syntax-ns#langString"
                             "http://www.w3.org/2001/XMLSchema#string"))}
      language (assoc :language language))))


(defn- clj-value [{:keys [value datatype] kind :rdf/type}]
  (cond
    (= kind :bnode) (str "_:" value)
    (and (= kind :literal) datatype)
    (let [dt (.createIRI (SimpleValueFactory/getInstance) ^String datatype)]
      (cond
        (XMLDatatypeUtil/isIntegerDatatype dt) (bigint (XMLDatatypeUtil/parseInteger value))
        (= datatype "http://www.w3.org/2001/XMLSchema#decimal") (XMLDatatypeUtil/parseDecimal value)
        (= datatype "http://www.w3.org/2001/XMLSchema#boolean") (XMLDatatypeUtil/parseBoolean value)
        (= datatype "http://www.w3.org/2001/XMLSchema#double") (XMLDatatypeUtil/parseDouble value)
        (= datatype "http://www.w3.org/2001/XMLSchema#float") (XMLDatatypeUtil/parseFloat value)
        :else value))
    :else value))


(defn- select-result [body as]
  (let [rows (get-in body [:results :bindings])
        vars (get-in body [:head :vars])]
    (when-not (and (vector? rows) (vector? vars) (every? string? vars) (every? map? rows))
      (fail! :sparql/invalid-response "Expected a SPARQL SELECT result set."))
    (mapv (fn [row]
            (when-not (every? (set (map keyword vars)) (keys row))
              (fail! :sparql/invalid-response "SPARQL result contains an undeclared variable."))
            (update-vals row #(cond-> (binding-term %) (= :clj as) clj-value)))
          rows)))


(defn- graph-result [body response url as]
  (let [mime   (some-> (header response "content-type")
                       (str/split #";")
                       first
                       str/trim
                       str/lower-case)
        format (case mime
                 "text/turtle" RDFFormat/TURTLE
                 "application/n-triples" RDFFormat/NTRIPLES
                 nil)]
    (when-not format (fail! :sparql/unsupported-content-type "Expected Turtle or N-Triples from the graph endpoint."))
    (let [config (doto (ParserConfig.) (.set BasicParserSettings/PRESERVE_BNODE_IDS true))
          model  (Rio/parse (StringReader. body) url format config (SimpleValueFactory/getInstance) nil (make-array Resource 0))]
      (if (= :jsonld as)
        (let [out (StringWriter.)]
          (Rio/write model out RDFFormat/JSONLD)
          (json/read-json (.toString out)))
        (mapv (fn [statement]
                (mapv #(cond-> (rdf-term %) (= :clj as) clj-value)
                      [(.getSubject statement) (.getPredicate statement) (.getObject statement)]))
              model)))))


(defn- execute [options operation]
  (let [submitted? (volatile! false)]
    (try
      (when-not (contains? options operation)
        (fail! :sparql/invalid-statement "Supply the statement matching the selected action."))
      (let [{:keys [url as method retry retry-safe? max-response-bytes]
             :or   {as :rdf method :post max-response-bytes 33554432}} options
            kind     (when (= :query operation) (query-kind options))
            graph?   (#{:construct :describe} kind)
            _ (when-not (and (string? url)
                             (pos-int? max-response-bytes)
                             (#{:rdf :clj :jsonld} as)
                             (#{:get :post} method)
                             (or (= :query operation) (= :post method)))
                (fail! :sparql/invalid-options "Invalid SPARQL URL, output format or HTTP method."))
            _ (when (and (= :jsonld as) (not graph?))
                (fail! :sparql/unsupported-format ":jsonld requires CONSTRUCT or DESCRIBE."))
            _ (when (and (= :update operation) (pos? (get retry :max-retries 0)) (not (true? retry-safe?)))
                (fail! :sparql/ambiguous-retry "Update retries require :retry-safe? true; a timed-out update may already have executed."))
            text     (format-statement options)
            request  (merge (select-keys options [:url :auth :timeout :headers])
                            {:method               method
                             :as                   :stream
                             :retry                (or retry {})
                             :unexceptional-status (set (range 200 300))})
            request  (update request
                             :headers
                             #(assoc %
                                "Accept" (if graph?
                                           "text/turtle, application/n-triples;q=0.9"
                                           "application/sparql-results+json")))
            request  (if (= :get method)
                       (assoc request :query-params {:query text})
                       (-> request
                           (assoc :body text)
                           (update :headers assoc "Content-Type" (str "application/sparql-" (name operation)))))
            _ (vreset! submitted? true)
            response (http/request request)
            body     (read-body (:body response) max-response-bytes)]
        (case operation
          :update {:status (:status response)}
          :query (if graph?
                   (graph-result body response url as)
                   (let [parsed (json/read-json body :key-fn keyword)]
                     (if (= :ask kind)
                       (let [answer (:boolean parsed)]
                         (when-not (boolean? answer) (fail! :sparql/invalid-response "Expected an ASK boolean."))
                         answer)
                       (select-result parsed as))))))
      (catch InterruptedException _
        (.interrupt (Thread/currentThread))
        (throw (InterruptedException. "SPARQL request interrupted.")))
      (catch java.util.concurrent.CancellationException _
        (throw (java.util.concurrent.CancellationException. "SPARQL request cancelled.")))
      (catch Exception e
        (throw (ex-info "SPARQL action failed."
                        (merge {:code :sparql/request-failed :collet/redact-params true}
                               (select-keys (ex-data e) [:code :status])
                               (when (and @submitted? (= :update operation))
                                 {:ambiguous? true}))))))))


(defn query [options]
  (execute options :query))


(defn update-graph [options]
  (execute options :update))


(doseq [type [::format ::query ::update]]
  (defmethod action/prep type
    [spec]
    (assoc spec
      :collet/params-mode :data
      :collet/sensitive? true)))


(defmethod action/action-fn ::format [_] format-statement)


(defmethod action/action-fn ::query [_] query)


(defmethod action/action-fn ::update [_] update-graph)
