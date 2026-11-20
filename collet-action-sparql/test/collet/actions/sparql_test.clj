(ns collet.actions.sparql-test
  (:require [charred.api :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [collet.action :as action]
            [collet.actions.http :as http]
            [collet.actions.sparql :as sp]
            [collet.core :as core]
            [collet.test-fixtures :as tf])
  (:import [java.io ByteArrayInputStream StringReader]
           [org.eclipse.rdf4j.model Resource]
           [org.eclipse.rdf4j.model.util Models]
           [org.eclipse.rdf4j.rio Rio RDFFormat]))


(def xsd "http://www.w3.org/2001/XMLSchema#")


(def select-data
  {:head    {:vars ["id" "count" "label" "flag" "unbound"]}
   :results {:bindings [{:id    {:type "uri" :value "urn:one"}
                         :count {:type "literal" :datatype (str xsd "integer") :value "001"}
                         :label {:type "literal" :xml:lang "en" :value "quoted \"text\""}
                         :flag  {:type "literal" :datatype (str xsd "boolean") :value "true"}}
                        {:id {:type "bnode" :value "b1"}}]}})


(def graph "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> . <urn:s> <urn:p> \"hello\"@en ; <urn:n> 1.25 ; <urn:link> _:b . _:b <urn:flag> true .")


(defn error [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e e)))


(defn query [kind as body]
  (with-redefs [http/request (constantly {:status  200
                                          :headers {"content-type" (if (#{:select :ask} kind) "application/sparql-results+json" "text/turtle; charset=utf-8")}
                                          :body    (if (string? body) body (json/write-json-str body))})]
    (sp/query {:url "https://fixture.test/sparql" :query "complete query" :query-kind kind :as as})))


(deftest term-conversion-preserves-rdf-information
  (let [rdf (query :select :rdf select-data)
        clj (query :select :clj select-data)]
    (is (= {:rdf/type :iri :value "urn:one"} (get-in rdf [0 :id])))
    (is (= "001" (get-in rdf [0 :count :value])))
    (is (= (str xsd "integer") (get-in rdf [0 :count :datatype])))
    (is (= "en" (get-in rdf [0 :label :language])))
    (is (str/includes?
         (sp/format-statement {:update {:insert-data [["<urn:s>" "<urn:label>" (get-in rdf [0 :label])]]}})
         "\\\"text\\\"\"@en"))
    (is (= :sparql/invalid-literal
           (:code (ex-data (error #(sp/format-statement
                                    {:update {:insert-data [["<urn:s>" "<urn:label>"
                                                             {:rdf/type :literal :value "1" :language "en" :datatype (str xsd "integer")}]]}}))))))
    (is (= {:rdf/type :bnode :value "b1"} (get-in rdf [1 :id])))
    (is (not (contains? (first rdf) :unbound)))
    (is (= [{:id "urn:one" :count 1N :label "quoted \"text\"" :flag true} {:id "_:b1"}] clj))))


(deftest query-result-matrix
  (doseq [as [:rdf :clj]]
    (is (true? (query :ask as {:head {} :boolean true})))
    (is (false? (query :ask as {:head {} :boolean false}))))
  (doseq [kind [:construct :describe]]
    (is (= 4 (count (query kind :rdf graph))))
    (is (some #(= {:rdf/type :bnode :value "b"} (last %)) (query kind :rdf graph)))
    (is (= 4 (count (query kind :clj graph))))
    (is (some #(= 1.25M (nth % 2)) (query kind :clj graph)))
    (let [result   (query kind :jsonld graph)
          actual   (Rio/parse (StringReader. (json/write-json-str result)) "urn:base:" RDFFormat/JSONLD (make-array Resource 0))
          expected (Rio/parse (StringReader. graph) "urn:base:" RDFFormat/TURTLE (make-array Resource 0))]
      (is (vector? result))
      (is (not-any? #(contains? % "@context") result))
      (is (Models/isomorphic expected actual)))))


(deftest unsupported-inputs-fail-before-network
  (let [calls (atom 0)]
    (with-redefs [http/request (fn [_] (swap! calls inc))]
      (doseq [opts [{:query "SELECT * {}"}
                    {:query "SELECT * {}" :query-kind :select :as :jsonld}
                    {:query "ASK {}" :query-kind :ask :as :jsonld}
                    {:query {:select ['?s] :where []} :query-kind :ask}]]
        (is (some? (error #(sp/query (assoc opts :url "https://fixture.test"))))))
      (is (= :sparql/ambiguous-retry
             (:code (ex-data (error #(sp/update-graph {:url "https://fixture.test" :update "CLEAR ALL" :retry {:max-retries 1}}))))))
      (is (zero? @calls)))))


(deftest update-rejects-a-query-before-sending
  (let [calls (atom 0)]
    (with-redefs [http/request (fn [_] (swap! calls inc))]
      (is (= :sparql/invalid-statement
             (:code (ex-data (error #(sp/update-graph {:url "https://fixture.test" :query "SELECT * {}"}))))))
      (is (zero? @calls)))))


(deftest formatting-escapes-values-once-and-preserves-expressions
  (is (= :sparql/invalid-query-kind
         (:code (ex-data (error #(sp/format-statement {:query "ASK {}"}))))))
  (is (= "ASK {}" (sp/format-statement {:query "ASK {}" :query-kind :ask})))
  (let [value     "\" ; DROP ALL ; #\n\\end\r\t"
        formatted (sp/format-statement {:query {:prefixes {:ex "<urn:ex:>"}
                                                :select   ['?s]
                                                :where    [['?s :ex/name {:rdf/type :literal :value value}]
                                                           [:filter (list '> '?n 0.5)]]}})]
    (is (str/includes? formatted "FILTER (?n > 0.5)"))
    (is (str/includes? formatted "\\\" ; DROP ALL ; #\\n\\\\end\\r\\t")))
  (let [triples [[{:rdf/type :iri :value "urn:s"} "<urn:p>" {:rdf/type :literal :value "<urn:literal>"}]
                 ["<urn:s>" "<urn:l>" {:rdf/type :literal :value "hi" :language "en"}]
                 ["<urn:s>" "<urn:n>" {:rdf/type :literal :value "01" :datatype (str xsd "integer")}]]
        output  (sp/format-statement {:update {:insert-data triples}})]
    (is (str/includes? output "\"<urn:literal>\""))
    (is (str/includes? output "\"hi\"@en"))
    (is (str/includes? output "\"01\"^^<http://www.w3.org/2001/XMLSchema#integer>"))))


(deftest catalogue-preparation-keeps-sparql-lists-as-data
  (let [spec     {:name      :format
                  :type      :collet.actions.sparql/format
                  :selectors {'threshold [:config :threshold]}
                  :params    {:query {:select ['?x]
                                      :where  [['?s "<urn:value>" '?x]
                                               [:filter (list '> '?x 'threshold)]]}}}
        pipeline {:name :format-query :tasks [{:name :render :actions [spec]}]}
        result   (:render (tf/run-pipeline! (core/compile-pipeline pipeline) {:threshold 0.75}))]
    (is (str/includes? result "FILTER (?x > 0.75)"))
    (is (= :data (:collet/params-mode (action/prep {:type :collet.actions.sparql/query}))))))


(deftest requests-and-update-receipts
  (let [requests (atom [])
        closed   (atom false)
        body     (proxy [ByteArrayInputStream] [(byte-array 0)] (close [] (reset! closed true)))]
    (with-redefs [http/request (fn [req] (swap! requests conj req) {:status 204 :body body})]
      (is (= {:status 204}
             (sp/update-graph {:url    "https://fixture.test"
                               :auth   {:type :basic :username "u" :password "secret"}
                               :update [{:insert-data [["<urn:s>" "<urn:p>" "one"]]}
                                        {:insert-data [["<urn:s>" "<urn:p>" "two"]]}]})))
      (is @closed)
      (is (= :post (:method (first @requests))))
      (is (= {} (:retry (first @requests))))
      (is (= "application/sparql-update" (get-in @requests [0 :headers "Content-Type"])))
      (is (str/includes? (:body (first @requests)) ";"))))
  (with-redefs [http/request (fn [_] (throw (ex-info "secret" {:status 503 :body "secret"} (Exception. "secret"))))]
    (let [e (error #(sp/update-graph {:url "https://fixture.test" :update "CLEAR ALL"}))]
      (is (= 503 (:status (ex-data e))))
      (is (true? (:ambiguous? (ex-data e))))
      (is (nil? (ex-cause e)))
      (is (not (str/includes? (pr-str (Throwable->map e)) "secret"))))))


(deftest malformed-results-and-response-limits
  (is (some? (error #(query :ask :rdf {:boolean "true"}))))
  (is (some? (error #(query :select :rdf {:results {:bindings []}}))))
  (with-redefs [http/request (constantly {:status 200 :body (json/write-json-str select-data)})]
    (is (= :sparql/response-too-large
           (:code (ex-data (error #(sp/query {:url "https://fixture.test" :query "SELECT * {}" :query-kind :select :max-response-bytes 10}))))))))


(deftest cancellation-preserves-control-flow-without-leaking-secrets
  (doseq [failure [(doto (InterruptedException. "secret") (.initCause (Exception. "secret")))
                   (java.util.concurrent.CancellationException. "secret")]]
    (try
      (with-redefs [http/request (fn [_] (throw failure))]
        (let [caught       (try (sp/query {:url "https://fixture.test" :query "ASK {}" :query-kind :ask})
                                (catch Exception e e))
              ;; clojure.test's STM bookkeeping can consume the interrupt flag.
              interrupted? (Thread/interrupted)]
          (is (= (class failure) (class caught)))
          (is (nil? (ex-cause caught)))
          (is (not (str/includes? (pr-str (Throwable->map caught)) "secret")))
          (when (instance? InterruptedException failure)
            (is interrupted?))))
      (finally (Thread/interrupted)))))
