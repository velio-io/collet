(ns collet.workbench-data-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [collet.stream :as stream]
            [collet.workbench.data :as data]
            [tech.v3.dataset :as ds]))


(deftest community-edn-formatting
  (let
    [text
     ";; Keep this comment\n{:name :read-orders,:type :collet.actions.http/request,:params {:url \"https://example.test/orders\" :method :get},:fn (fn [row] (let [amount (:amount row)] (if (pos? amount) amount 0))),:note \"commas, stay in strings\"}\n;; Keep this too\n"
     formatted (data/format-edn text)]
    (is (= (data/read-edn text) (data/read-edn formatted)))
    (is (str/starts-with? formatted ";; Keep this comment\n"))
    (is (str/ends-with? formatted ";; Keep this too"))
    (is (str/includes? formatted "commas, stay in strings"))
    (is (str/includes? formatted "\n :type   :collet.actions.http/request"))
    (is (not (str/includes? formatted ":read-orders,")))
    (is (= formatted (data/format-edn formatted)) "Formatting is idempotent")
    (is (every? #(<= (count %) 88) (str/split-lines formatted))))
  (testing "Formatting parses syntax without evaluating forms or resolving tags"
    (is (string? (data/format-edn "(throw (Exception. \"must not run\"))")))
    (is (str/includes? (data/format-edn ";; Configuration\n{:token #env \"TOKEN\"}") "#env \"TOKEN\""))))


(deftest generated-edn-and-previews-serialize-without-formatting
  (let [value  {:name   :read-orders
                :type   :collet.actions.http/request
                :params {:url "https://example.test/orders" :method :get}}
        source (data/source value)]
    (is (= value (data/read-edn source)))
    (with-redefs [data/format-edn (fn [_] (throw (ex-info "Unexpected formatting" {})))]
      (is (= source (data/source value)))
      (is (= source (:edn (data/preview value))))))
  (let [pattern   (java.util.regex.Pattern/compile "foo\\d+" java.util.regex.Pattern/CASE_INSENSITIVE)
        source    (data/source {:pattern pattern})
        read-back (:pattern (data/read-edn source))]
    (is (str/includes? source "#rgx"))
    (is (= (.pattern pattern) (.pattern read-back)))
    (is (= (.flags pattern) (.flags read-back))))
  (let [preview (data/preview (vec (repeat 101 {:value (apply str (repeat 4001 "x"))})))]
    (is (:truncated preview))
    (is (<= (count (:edn preview)) 50000))))


(deftest formatting-rejects-unusable-input
  (doseq [text [nil {} (apply str (repeat 262145 "x"))]]
    (is (thrown-with-msg? Exception #"smaller than 256 KiB" (data/format-edn text))))
  (is (thrown? Exception (data/format-edn "{:broken [1}"))))


(deftest wide-rows-stop-at-the-preview-character-budget
  (let [opened  (atom 0)
        source  (stream/view #(repeatedly (fn [] (swap! opened inc) (ds/->dataset [{:base64 (apply str (repeat 8000 "a"))}]))) {})
        preview (data/preview source)]
    (is (:truncated preview))
    (is (< 0 @opened 15))
    (is (<= (reduce + (map #(count (get % "base64")) (:value preview))) 32768))
    (is (= "<deferred sequence; inspect the retained task output>" (data/observed-value source)))))
