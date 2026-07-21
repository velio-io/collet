(ns collet.actions.lucene-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [are deftest is testing use-fixtures]]
   [collet.action :as action]
   [collet.actions.lucene :as sut]
   [collet.core :as collet]
   [collet.test-fixtures :as tf]
   [tech.v3.dataset :as ds])
  (:import [clojure.lang ExceptionInfo]
           [java.io File]))


(use-fixtures :once (tf/instrument! 'collet.actions.lucene))


;;-----------------------------------------------------------------------------
;; Test Helpers
;;-----------------------------------------------------------------------------

(defn- create-temp-index
  "Creates a temporary directory for Lucene index and returns its path."
  []
  (let [dir (File/createTempFile "lucene-idx" "")]
    (.delete dir)
    (.mkdir dir)
    (.getAbsolutePath dir)))


(defn- cleanup-dir
  "Recursively deletes a directory and all its contents."
  [path]
  (doseq [f (reverse (file-seq (io/file path)))]
    (.delete f)))


(defn- create-test-csv
  "Creates a temporary CSV file with the given content and returns its path."
  [content]
  (let [f (File/createTempFile "test-data" ".csv")]
    (spit f content)
    (.getAbsolutePath f)))




;;-----------------------------------------------------------------------------
;; Normalization Tests (existing)
;;-----------------------------------------------------------------------------

(deftest normalize-column-name-test
  (testing "Basic normalization"
    (is (= :customer_id (#'sut/normalize-column-name "Customer Id")))
    (is (= :first_name (#'sut/normalize-column-name "First Name")))
    (is (= :last_name (#'sut/normalize-column-name "Last Name"))))

  (testing "Numbers in names"
    (is (= :phone_1 (#'sut/normalize-column-name "Phone 1")))
    (is (= :phone_2 (#'sut/normalize-column-name "Phone 2")))
    (is (= :123 (#'sut/normalize-column-name "123"))))

  (testing "Hyphens to underscores"
    (is (= :first_name (#'sut/normalize-column-name "first-name")))
    (is (= :user_name (#'sut/normalize-column-name "User-Name"))))

  (testing "Special characters"
    (is (= :emailaddress (#'sut/normalize-column-name "Email@Address")))
    (is (= :userid (#'sut/normalize-column-name "User#Id")))
    (is (= :priceusd (#'sut/normalize-column-name "Price(USD)"))))

  (testing "Whitespace handling"
    (is (= :name (#'sut/normalize-column-name "  Name  ")))
    (is (= :first_name (#'sut/normalize-column-name "First    Name"))))

  (testing "Multiple underscores"
    (is (= :first_name (#'sut/normalize-column-name "first__name")))
    (is (= :a_b_c (#'sut/normalize-column-name "a___b___c"))))

  (testing "Leading/trailing underscores"
    (is (= :name (#'sut/normalize-column-name "_name_")))
    (is (= :test (#'sut/normalize-column-name "___test___"))))

  (testing "Edge cases"
    (is (= :column (#'sut/normalize-column-name "")))
    (is (= :column (#'sut/normalize-column-name "   ")))
    (is (= :a (#'sut/normalize-column-name "a")))
    (is (= :camelcase (#'sut/normalize-column-name "CamelCase"))))

  (testing "Input types"
    (is (= :name (#'sut/normalize-column-name :name)))
    (is (= :first_name (#'sut/normalize-column-name :first_name)))
    (is (= :name (#'sut/normalize-column-name "name"))))

  (testing "Already normalized"
    (is (= :first_name (#'sut/normalize-column-name "first_name")))
    (is (= :user_id (#'sut/normalize-column-name :user_id)))))


;;-----------------------------------------------------------------------------
;; load-dataset Tests (existing)
;;-----------------------------------------------------------------------------

(deftest load-dataset-default-normalization-test
  (testing "Default normalization applied"
    (let [temp-file (File/createTempFile "test" ".csv")
          _         (spit temp-file "Customer Id,First Name,Phone 1\n1,John,555-1234\n")
          dataset   (#'sut/load-dataset
                     (.getAbsolutePath temp-file)
                     {:format :csv :compressed? false}
                     {})
          row       (first (ds/mapseq-reader dataset))
          row-keys  (set (keys row))]
      (try
        (is (contains? row-keys :customer_id))
        (is (contains? row-keys :first_name))
        (is (contains? row-keys :phone_1))
        (is (= 1 (:customer_id row)))
        (is (= "John" (:first_name row)))
        (is (= "555-1234" (:phone_1 row)))
        (finally
          (.delete temp-file))))))


(deftest load-dataset-custom-key-fn-test
  (testing "Custom :key-fn overrides default"
    (let [temp-file (File/createTempFile "test" ".csv")
          _         (spit temp-file "First Name,Last Name\nJohn,Doe\n")
          dataset   (#'sut/load-dataset
                     (.getAbsolutePath temp-file)
                     {:format :csv :compressed? false}
                     {:key-fn keyword})
          row-keys  (set (keys (first (ds/mapseq-reader dataset))))]
      (try
        ;; Keywords preserve spaces: :'First Name'
        (is (some #(= "First Name" (name %)) row-keys))
        (is (some #(= "Last Name" (name %)) row-keys))
        (finally
          (.delete temp-file))))))


(deftest load-dataset-identity-key-fn-test
  (testing "identity :key-fn disables normalization"
    (let [temp-file (File/createTempFile "test" ".csv")
          _         (spit temp-file "First Name,Last Name\nJohn,Doe\n")
          dataset   (#'sut/load-dataset
                     (.getAbsolutePath temp-file)
                     {:format :csv :compressed? false}
                     {:key-fn identity})
          row-keys  (set (keys (first (ds/mapseq-reader dataset))))]
      (try
        ;; With identity key-fn, column names remain as strings
        (is (contains? row-keys "First Name"))
        (is (contains? row-keys "Last Name"))
        (finally
          (.delete temp-file))))))


;;-----------------------------------------------------------------------------
;; create-index Tests
;;-----------------------------------------------------------------------------

(deftest create-index-simple-path-test
  (testing "create-index with simple string path (backward compatible)"
    (let [index-path (create-temp-index)]
      (try
        (let [writer (sut/create-index index-path)]
          (is (some? writer))
          (.close writer))
        (finally
          (cleanup-dir index-path))))))


(deftest create-index-options-test
  (testing "create-index with options map"
    (let [index-path (create-temp-index)]
      (try
        ;; Test with various analyzers
        (doseq [analyzer [:standard :whitespace :simple]]
          (let [writer (sut/create-index
                        {:index-path index-path
                         :analyzer   analyzer
                         :open-mode  :create})]
            (is (some? writer))
            (.close writer)))

        ;; Test with RAM buffer and open mode options
        (let [writer (sut/create-index
                      {:index-path    index-path
                       :ram-buffer-mb 128
                       :open-mode     :create-or-append})]
          (is (some? writer))
          (.close writer))
        (finally
          (cleanup-dir index-path))))))


;;-----------------------------------------------------------------------------
;; index-files! Tests
;;-----------------------------------------------------------------------------

(deftest index-files-return-stats-test
  (testing "index-files! returns statistics map"
    (let [index-path (create-temp-index)
          csv-path   (create-test-csv "id,name\n1,Alice\n2,Bob\n3,Carol\n")]
      (try
        (let [result (sut/index-files!
                      {:index-path index-path
                       :docs-path  csv-path})]
          (is (map? result))
          (is (= 3 (:indexed result)))
          (is (= 0 (:failed result)))
          (is (number? (:duration-ms result)))
          (is (contains? result :skipped))
          (is (contains? result :errors)))
        (finally
          (cleanup-dir index-path)
          (io/delete-file csv-path))))))


(deftest index-files-progress-fn-test
  (testing "index-files! calls progress callback"
    (let [index-path   (create-temp-index)
          csv-path     (create-test-csv "id,name\n1,Alice\n2,Bob\n")
          progress-log (atom [])]
      (try
        ;; Set commit-every-n to 1 so progress is reported after each row
        (sut/index-files!
         {:index-path     index-path
          :docs-path      csv-path
          :commit-every-n 1
          :progress-fn    (fn [stats]
                            (swap! progress-log conj stats))})
        ;; Progress function should have been called at least once
        (is (pos? (count @progress-log)))
        ;; Each progress update should contain stats
        (doseq [update @progress-log]
          (is (contains? update :indexed)))
        (finally
          (cleanup-dir index-path)
          (io/delete-file csv-path))))))


(deftest index-files-extensions-filter-test
  (testing "index-files! filters by extensions"
    (let [index-path (create-temp-index)
          temp-dir   (File/createTempFile "test-dir" "")
          _          (.delete temp-dir)
          _          (.mkdir temp-dir)
          csv-file   (io/file temp-dir "data.csv")
          json-file  (io/file temp-dir "data.json")]
      (try
        ;; Create CSV and JSON files
        (spit csv-file "id,name\n1,Alice\n")
        (spit json-file "[{\"id\":2,\"name\":\"Bob\"}]")

        ;; Index only CSV files
        (let [result (sut/index-files!
                      {:index-path index-path
                       :docs-path  (.getAbsolutePath temp-dir)
                       :extensions #{:csv}})]
          ;; Should only index CSV (1 row)
          (is (= 1 (:indexed result))))
        (finally
          (cleanup-dir index-path)
          (.delete csv-file)
          (.delete json-file)
          (.delete temp-dir))))))


(deftest index-files-exclude-patterns-test
  (testing "index-files! excludes files matching patterns"
    (let [index-path (create-temp-index)
          temp-dir   (File/createTempFile "test-dir" "")
          _          (.delete temp-dir)
          _          (.mkdir temp-dir)
          main-file  (io/file temp-dir "data.csv")
          test-file  (io/file temp-dir "test_data.csv")]
      (try
        (spit main-file "id,name\n1,Alice\n")
        (spit test-file "id,name\n2,Test\n")

        ;; Exclude files starting with "test"
        (let [result (sut/index-files!
                      {:index-path       index-path
                       :docs-path        (.getAbsolutePath temp-dir)
                       :exclude-patterns #{#"test.*"}})]
          (is (= 1 (:indexed result))))
        (finally
          (cleanup-dir index-path)
          (.delete main-file)
          (.delete test-file)
          (.delete temp-dir))))))


(deftest index-files-dataset-opts-test
  (testing "index-files! with dataset options"
    (let [index-path (create-temp-index)
          csv-path   (create-test-csv "id,name,email,phone\n1,Alice,alice@test.com,555-1234\n")]
      (try
        ;; Test column-allowlist - only index specific columns
        (sut/index-files!
         {:index-path   index-path
          :docs-path    csv-path
          :dataset-opts {:column-allowlist ["id" "name"]}})

        ;; Search should work for allowed columns
        (let [results (sut/search {:index-path index-path
                                   :query      "name:Alice"})]
          (is (pos? (ds/row-count results)))
          ;; Only id and name should be present (plus metadata)
          (let [first-row (first (ds/mapseq-reader results))]
            (is (contains? first-row :id))
            (is (contains? first-row :name))))
        (finally
          (cleanup-dir index-path)
          (io/delete-file csv-path)))))

  (testing "index-files! with custom separator (tab-separated CSV)"
    (let [index-path (create-temp-index)
          ;; Use .csv extension but with tab-separated content
          csv-path   (let [f (File/createTempFile "test-data" ".csv")]
                       (spit f "id\tname\n1\tAlice\n2\tBob\n")
                       (.getAbsolutePath f))]
      (try
        (sut/index-files!
         {:index-path   index-path
          :docs-path    csv-path
          :dataset-opts {:separator \tab}})

        (let [results (sut/search {:index-path index-path
                                   :query      "name:Bob"})]
          (is (= 1 (ds/row-count results))))
        (finally
          (cleanup-dir index-path)
          (io/delete-file csv-path))))))


;;-----------------------------------------------------------------------------
;; search Tests
;;-----------------------------------------------------------------------------

(deftest search-wildcard-test
  (testing "search with wildcard query"
    (let [index-path (create-temp-index)
          csv-path   (create-test-csv "id,name\n1,Alice\n2,Albert\n3,Bob\n")]
      (try
        (sut/index-files! {:index-path index-path :docs-path csv-path})

        (let [results (sut/search {:index-path index-path
                                   :query      "name:Al*"})]
          (is (= 2 (ds/row-count results)))
          (is (every? #(str/starts-with? (:name %) "Al")
                      (ds/mapseq-reader results))))
        (finally
          (cleanup-dir index-path)
          (io/delete-file csv-path))))))


(deftest search-fuzzy-test
  (testing "search with fuzzy query"
    (let [index-path (create-temp-index)
          csv-path   (create-test-csv "id,name\n1,Alice\n2,Bob\n")]
      (try
        (sut/index-files! {:index-path index-path :docs-path csv-path})

        ;; "Alce" is 1 edit away from "Alice"
        (let [results (sut/search {:index-path index-path
                                   :query      "name:Alce~1"})]
          (is (= 1 (ds/row-count results)))
          (is (= "Alice" (-> results ds/mapseq-reader first :name))))
        (finally
          (cleanup-dir index-path)
          (io/delete-file csv-path))))))


(deftest search-phrase-test
  (testing "search with phrase query"
    (let [index-path (create-temp-index)
          csv-path   (create-test-csv "id,description\n1,high quality laptop\n2,laptop quality\n3,high laptop quality\n")]
      (try
        (sut/index-files! {:index-path index-path :docs-path csv-path})

        ;; Exact phrase match
        (let [results (sut/search {:index-path index-path
                                   :query      "description:\"high quality laptop\""})]
          (is (= 1 (ds/row-count results)))
          (is (= "high quality laptop"
                 (-> results ds/mapseq-reader first :description))))
        (finally
          (cleanup-dir index-path)
          (io/delete-file csv-path))))))


(deftest search-range-test
  (testing "search with range query"
    (let [index-path (create-temp-index)
          csv-path   (create-test-csv "id,price\n1,50\n2,150\n3,250\n4,350\n")]
      (try
        (sut/index-files! {:index-path index-path :docs-path csv-path})

        ;; Note: Lucene string range - values need to be padded for numeric ranges
        (let [results (sut/search {:index-path index-path
                                   :query      "price:[100 TO 300]"})]
          ;; Should match 150 and 250
          (is (= 2 (ds/row-count results))))
        (finally
          (cleanup-dir index-path)
          (io/delete-file csv-path))))))


(deftest search-boolean-test
  (testing "search with boolean query"
    (let [index-path (create-temp-index)
          csv-path   (create-test-csv "id,city,country\n1,London,UK\n2,Paris,France\n3,Manchester,UK\n")]
      (try
        (sut/index-files! {:index-path index-path :docs-path csv-path})

        (let [results (sut/search {:index-path index-path
                                   :query      "city:London AND country:UK"})]
          (is (= 1 (ds/row-count results)))
          (is (= "London" (-> results ds/mapseq-reader first :city))))

        ;; OR query
        (let [results (sut/search {:index-path index-path
                                   :query      "city:London OR city:Paris"})]
          (is (= 2 (ds/row-count results))))
        (finally
          (cleanup-dir index-path)
          (io/delete-file csv-path))))))


(deftest search-pagination-test
  (testing "search with offset and limit"
    (let [index-path (create-temp-index)
          csv-data   (apply str "id,name\n"
                            (map #(str % ",Name" % "\n") (range 1 21)))
          csv-path   (create-test-csv csv-data)]
      (try
        (sut/index-files! {:index-path index-path :docs-path csv-path})

        ;; Get first 5 results
        (let [page1 (sut/search {:index-path index-path
                                 :query      "*:*"
                                 :limit      5})]
          (is (= 5 (ds/row-count page1))))

        ;; Get next 5 results (offset 5)
        (let [page2 (sut/search {:index-path index-path
                                 :query      "*:*"
                                 :offset     5
                                 :limit      5})]
          (is (= 5 (ds/row-count page2))))

        ;; Total count should be 20
        (let [all-results (sut/search {:index-path index-path
                                       :query      "*:*"
                                       :limit      100})]
          (is (= 20 (ds/row-count all-results))))
        (finally
          (cleanup-dir index-path)
          (io/delete-file csv-path))))))


(deftest search-fields-selection-test
  (testing "search with specific fields selection"
    (let [index-path (create-temp-index)
          csv-path   (create-test-csv "id,name,email,phone\n1,Alice,alice@test.com,555-1234\n")]
      (try
        (sut/index-files! {:index-path index-path :docs-path csv-path})

        ;; Select only specific fields
        (let [results (sut/search {:index-path index-path
                                   :query      "name:Alice"
                                   :fields     [:name :email]})]
          (is (= 1 (ds/row-count results)))
          (let [row (first (ds/mapseq-reader results))]
            (is (contains? row :name))
            (is (contains? row :email))
            ;; phone should not be in the selected fields
            (is (not (contains? row :phone)))))
        (finally
          (cleanup-dir index-path)
          (io/delete-file csv-path))))))


(deftest search-no-score-test
  (testing "search without score"
    (let [index-path (create-temp-index)
          csv-path   (create-test-csv "id,name\n1,Alice\n")]
      (try
        (sut/index-files! {:index-path index-path :docs-path csv-path})

        ;; With score (default)
        (let [results-with-score (sut/search {:index-path index-path
                                               :query      "name:Alice"})]
          (is (contains? (first (ds/mapseq-reader results-with-score)) :score)))

        ;; Without score
        (let [results-no-score (sut/search {:index-path     index-path
                                            :query          "name:Alice"
                                            :include-score? false})]
          (is (not (contains? (first (ds/mapseq-reader results-no-score)) :score))))
        (finally
          (cleanup-dir index-path)
          (io/delete-file csv-path))))))


(deftest search-custom-analyzer-test
  (testing "search with custom analyzer"
    (let [index-path (create-temp-index)
          csv-path   (create-test-csv "id,code\n1,func_name_test\n2,funcNameTest\n")]
      (try
        ;; Index with whitespace analyzer for code-like content
        (sut/index-files! {:index-path index-path
                           :docs-path  csv-path
                           :analyzer   :whitespace})

        ;; Search with same analyzer - should find exact match
        (let [results (sut/search {:index-path index-path
                                   :query      "code:func_name_test"
                                   :analyzer   :whitespace})]
          (is (= 1 (ds/row-count results))))
        (finally
          (cleanup-dir index-path)
          (io/delete-file csv-path))))))


(deftest search-source-metadata-test
  (testing "search results contain source metadata"
    (let [index-path (create-temp-index)
          csv-path   (create-test-csv "id,name\n1,Alice\n")]
      (try
        (sut/index-files! {:index-path index-path :docs-path csv-path})

        (let [results (sut/search {:index-path index-path
                                   :query      "name:Alice"})
              row     (first (ds/mapseq-reader results))]
          ;; Check for metadata columns
          (is (contains? row :source-path))
          (is (contains? row :row-number))
          (is (string? (:source-path row)))
          (is (number? (:row-number row))))
        (finally
          (cleanup-dir index-path)
          (io/delete-file csv-path))))))


;;-----------------------------------------------------------------------------
;; End-to-end Index and Search Tests (existing, slightly reorganized)
;;-----------------------------------------------------------------------------

(deftest index-and-search-with-normalized-columns-test
  (testing "End-to-end: index with spaces, search with normalized names"
    (let [temp-dir   (File/createTempFile "lucene-test" "")
          _          (.delete temp-dir)
          _          (.mkdir temp-dir)
          index-path (.getAbsolutePath temp-dir)

          temp-csv   (File/createTempFile "test-data" ".csv")
          _          (spit temp-csv
                           "Customer Id,First Name,Last Name\n101,Alice,Smith\n102,Bob,Jones\n")
          csv-path   (.getAbsolutePath temp-csv)]

      (try
        ;; Index the CSV
        (sut/index-files!
         {:index-path index-path
          :docs-path  csv-path})

        ;; Search using normalized field name
        (let [results (sut/search
                       {:index-path index-path
                        :query      "customer_id:101"})
              rows    (ds/mapseq-reader results)]
          ;; Check we got results
          (is (pos? (ds/row-count results)))

          ;; Check first row
          (let [first-row (first rows)]
            (is (= "101" (:customer_id first-row)))
            (is (= "Alice" (:first_name first-row)))

            ;; Check metadata columns
            (is (number? (:score first-row)))
            (is (= 1 (:rank first-row)))  ; First result has rank 1
            (is (number? (:doc_id first-row)))))

        ;; Search for another field
        (let [results (sut/search
                       {:index-path index-path
                        :query      "first_name:Bob"})
              rows    (ds/mapseq-reader results)]
          ;; Check we got results
          (is (pos? (ds/row-count results)))

          ;; Check first row
          (let [first-row (first rows)]
            (is (= "102" (:customer_id first-row)))
            (is (= "Bob" (:first_name first-row)))

            ;; Check metadata columns
            (is (number? (:score first-row)))
            (is (= 1 (:rank first-row)))
            (is (number? (:doc_id first-row)))))

        (finally
          ;; Cleanup
          (.delete temp-csv)
          (doseq [f (reverse (file-seq temp-dir))]
            (.delete f)))))))


(deftest normalization-consistency-test
  (testing "Normalization is consistent between indexing and searching"
    (let [temp-dir   (File/createTempFile "lucene-test" "")
          _          (.delete temp-dir)
          _          (.mkdir temp-dir)
          index-path (.getAbsolutePath temp-dir)

          temp-csv   (File/createTempFile "test-data" ".csv")
          ;; Create CSV with various column name formats
          _          (spit temp-csv
                           "User_Name,Email@Domain,Price(USD),Phone 1\nAlice,alice@example.com,100,555-1234\n")
          csv-path   (.getAbsolutePath temp-csv)]

      (try
        ;; Index the CSV
        (sut/index-files!
         {:index-path index-path
          :docs-path  csv-path})

        ;; Verify all normalized field names work in search
        (let [results (sut/search
                       {:index-path index-path
                        :query      "*:*"
                        :limit      1})
              rows    (ds/mapseq-reader results)]
          ;; Check we got results
          (is (pos? (ds/row-count results)))

          ;; Check first row
          (let [first-row (first rows)]
            ;; Check that all fields are normalized
            (is (contains? first-row :user_name))
            (is (contains? first-row :emaildomain))
            (is (contains? first-row :priceusd))
            (is (contains? first-row :phone_1))
            ;; Verify values
            (is (= "Alice" (:user_name first-row)))
            (is (= "alice@example.com" (:emaildomain first-row)))
            (is (= "100" (:priceusd first-row)))
            (is (= "555-1234" (:phone_1 first-row)))))

        (finally
          ;; Cleanup
          (.delete temp-csv)
          (doseq [f (reverse (file-seq temp-dir))]
            (.delete f)))))))


;;-----------------------------------------------------------------------------
;; compile-lucene-query Tests (existing)
;;-----------------------------------------------------------------------------

(deftest compile-lucene-query-test
  (testing "Compile lucene query"
    (are [got compiled] (= compiled (sut/compile-lucene-query got))
      "data"
      "\"data\""

      "data and pata"
      "\"data and pata\""

      "d?ta"
      "\"d?ta\""

      #"d.*ta"
      "/d.*ta/"

      [:field "d?ta"]
      "field:\"d?ta\""

      [:field_name "something" "some phrase here"]
      "field_name:(\"something\" \"some phrase here\")"

      [:range {:exclusive? true} ["50" "100"]]
      "{\"50\" TO \"100\"}"

      [:range [50 100]]
      "[50 TO 100]"

      [:field [:range ["50" "100"]]]
      "field:[\"50\" TO \"100\"]"

      [:field ["a" [:range ["50" "100"]]]]
      "field:(\"a\" [\"50\" TO \"100\"])"

      [:fuzzy {:ed 0.7} "data"]
      "\"data\"~0.7"

      [:fuzzy "data"]
      "\"data\"~0.5"

      [:- [:field "data"]]
      "-field:\"data\""

      [:prox {:nw 10} "data data2"]
      "\"data data2\"~10"

      ["a" [:not "b"]]
      "(\"a\" NOT \"b\")"

      [:or "a" [:category "electronics"]]
      "(\"a\" OR category:\"electronics\")"

      [:and
       [:title "leather jacket"]
       [:color "gr?y"]
       [:size "M"]]
      "(title:\"leather jacket\" AND color:\"gr?y\" AND size:\"M\")"

      [:and
       [:or "a" [:and "c" "d"]]
       "a"
       [:fuzzy "e"]
       [:- "d"]
       [:condition [:- "refurbished"]]
       [:price
        [:range ["100" "500"]]]
       ["a" [:not "b"]]]
      "((\"a\" OR (\"c\" AND \"d\")) AND \"a\" AND \"e\"~0.5 AND -\"d\" AND condition:-\"refurbished\" AND price:[\"100\" TO \"500\"] AND (\"a\" NOT \"b\"))"

      [:title
       ["a"
        [:fuzzy {:ed 1} "smartphone"]
        [:+ "Samsung"]]]
      "title:(\"a\" \"smartphone\"~1 +\"Samsung\")"

      [:and
       [:headline
        [:or "climate change" "global warming"]]
       [:date
        [:range [20250101 20251231]]]]
      "(headline:(\"climate change\" OR \"global warming\") AND date:[20250101 TO 20251231])"))

  (testing "Throwing errors on invalid states"
    (is (thrown-with-msg? Exception #"invalid" (sut/compile-lucene-query "d*ata and pata")))
    (is (thrown-with-msg? Exception #"invalid" (sut/compile-lucene-query "?ata")))
    (is (thrown-with-msg? Exception #"invalid" (sut/compile-lucene-query [:prox {:nw 10} "data"])))))


;;-----------------------------------------------------------------------------
;; Integration Tests: Action Dispatch
;;-----------------------------------------------------------------------------

(deftest index-action-test
  (testing "::index action dispatch"
    (let [index-path (create-temp-index)
          csv-path   (create-test-csv "id,name\n1,Alice\n2,Bob\n")]
      (try
        (let [action-fn (action/action-fn {:type :collet.actions.lucene/index})
              result    (action-fn {:index-path index-path
                                    :docs-path  csv-path})]
          (is (= 2 (:indexed result)))
          (is (= 0 (:failed result))))
        (finally
          (cleanup-dir index-path)
          (io/delete-file csv-path))))))


(deftest query-action-test
  (testing "::query action dispatch with DSL"
    (let [index-path (create-temp-index)
          csv-path   (create-test-csv "id,name\n1,Alice\n2,Bob\n")]
      (try
        ;; Index first
        (sut/index-files! {:index-path index-path :docs-path csv-path})

        ;; Query via action
        (let [action-fn (action/action-fn {:type :collet.actions.lucene/query})
              result    (action-fn {:index index-path
                                    :query [:name "Alice"]})]
          (is (pos? (ds/row-count result)))
          (is (= "Alice" (-> result ds/mapseq-reader first :name))))
        (finally
          (cleanup-dir index-path)
          (io/delete-file csv-path))))))


(deftest query-action-with-options-test
  (testing "::query action with all options"
    (let [index-path (create-temp-index)
          csv-path   (create-test-csv "id,name,city\n1,Alice,London\n2,Bob,Paris\n3,Carol,London\n")]
      (try
        (sut/index-files! {:index-path index-path :docs-path csv-path})

        ;; Query with various options
        (let [action-fn (action/action-fn {:type :collet.actions.lucene/query})
              result    (action-fn {:index          index-path
                                    :query          [:city "London"]
                                    :limit          10
                                    :offset         0
                                    :fields         [:name :city]
                                    :include-score? false})]
          (is (= 2 (ds/row-count result)))
          (let [first-row (first (ds/mapseq-reader result))]
            (is (contains? first-row :name))
            (is (contains? first-row :city))
            (is (not (contains? first-row :score)))))
        (finally
          (cleanup-dir index-path)
          (io/delete-file csv-path))))))


;;-----------------------------------------------------------------------------
;; Integration Tests: Pipeline Execution
;;-----------------------------------------------------------------------------

(deftest lucene-pipeline-integration-test
  (testing "Full pipeline: index and query actions"
    (let [index-path (create-temp-index)
          csv-path   (create-test-csv "customer_id,name,city\n1,Alice,London\n2,Bob,Paris\n3,Carol,London\n")]
      (try
        (let [pipeline (collet/compile-pipeline
                        {:name  :lucene-test-pipeline
                         :deps  {:requires '[[collet.actions.lucene]]}
                         :tasks [{:name    :index-data
                                  :actions [{:name      :index-csv
                                             :type      :collet.actions.lucene/index
                                             :selectors {'idx-path [:config :index-path]
                                                         'csv-path [:config :csv-path]}
                                             :params    {:index-path 'idx-path
                                                         :docs-path  'csv-path}}]}
                                 {:name       :search-london
                                  :inputs     [:index-data]
                                  :keep-state true
                                  :actions    [{:name      :query-city
                                                :type      :collet.actions.lucene/query
                                                :selectors {'idx-path [:config :index-path]}
                                                :params    {:index 'idx-path
                                                            :query [:city "London"]}}]}]})]

          @(pipeline {:index-path index-path :csv-path csv-path})

          (let [results (:search-london pipeline)]
            (is (= 2 (ds/row-count results)))
            (is (every? #(= "London" (:city %))
                        (ds/mapseq-reader results)))))
        (finally
          (cleanup-dir index-path)
          (io/delete-file csv-path))))))


(deftest lucene-pipeline-with-query-dsl-test
  (testing "Pipeline with query DSL"
    (let [index-path (create-temp-index)
          csv-path   (create-test-csv "id,title,category\n1,Laptop,electronics\n2,Phone,electronics\n3,Tablet,accessories\n")]
      (try
        ;; Index first (outside pipeline for simplicity)
        (sut/index-files! {:index-path index-path :docs-path csv-path})

        ;; Pipeline with DSL query - find Laptop in electronics
        (let [pipeline (collet/compile-pipeline
                        {:name  :dsl-query-pipeline
                         :deps  {:requires '[[collet.actions.lucene]]}
                         :tasks [{:name       :search
                                  :keep-state true
                                  :actions    [{:name      :query
                                                :type      :collet.actions.lucene/query
                                                :selectors {'idx-path [:config :index-path]}
                                                :params    {:index 'idx-path
                                                            :query [:and
                                                                    [:title "Laptop"]
                                                                    [:category "electronics"]]}}]}]})]
          @(pipeline {:index-path index-path})
          (let [results (:search pipeline)]
            (is (= 1 (ds/row-count results)))
            (is (= "Laptop" (-> results ds/mapseq-reader first :title)))))
        (finally
          (cleanup-dir index-path)
          (io/delete-file csv-path))))))


(deftest lucene-pipeline-or-query-test
  (testing "Pipeline with OR query DSL"
    (let [index-path (create-temp-index)
          csv-path   (create-test-csv "id,category,brand\n1,electronics,Apple\n2,electronics,Samsung\n3,clothing,Nike\n")]
      (try
        ;; Full pipeline: index then query (matching working test pattern)
        (let [pipeline (collet/compile-pipeline
                        {:name  :or-query-pipeline
                         :deps  {:requires '[[collet.actions.lucene]]}
                         :tasks [{:name    :index-data
                                  :actions [{:name      :index-csv
                                             :type      :collet.actions.lucene/index
                                             :selectors {'idx-path [:config :index-path]
                                                         'csv-path [:config :csv-path]}
                                             :params    {:index-path 'idx-path
                                                         :docs-path  'csv-path}}]}
                                 {:name       :search
                                  :inputs     [:index-data]
                                  :keep-state true
                                  :actions    [{:name      :query
                                                :type      :collet.actions.lucene/query
                                                :selectors {'idx-path [:config :index-path]}
                                                :params    {:index 'idx-path
                                                            :query [:or
                                                                    [:brand "Apple"]
                                                                    [:brand "Nike"]]}}]}]})]
          @(pipeline {:index-path index-path :csv-path csv-path})
          (let [results (:search pipeline)]
            (is (= 2 (ds/row-count results)))))
        (finally
          (cleanup-dir index-path)
          (io/delete-file csv-path))))))


(deftest lucene-pipeline-exclusion-query-test
  (testing "Pipeline with AND exclusion query DSL"
    (let [index-path (create-temp-index)
          csv-path   (create-test-csv "id,category,brand\n1,electronics,Apple\n2,electronics,Samsung\n3,clothing,Nike\n")]
      (try
        ;; Full pipeline: index then query (matching working test pattern)
        (let [pipeline (collet/compile-pipeline
                        {:name  :and-exclude-pipeline
                         :deps  {:requires '[[collet.actions.lucene]]}
                         :tasks [{:name    :index-data
                                  :actions [{:name      :index-csv
                                             :type      :collet.actions.lucene/index
                                             :selectors {'idx-path [:config :index-path]
                                                         'csv-path [:config :csv-path]}
                                             :params    {:index-path 'idx-path
                                                         :docs-path  'csv-path}}]}
                                 {:name       :search
                                  :inputs     [:index-data]
                                  :keep-state true
                                  :actions    [{:name      :query
                                                :type      :collet.actions.lucene/query
                                                :selectors {'idx-path [:config :index-path]}
                                                :params    {:index 'idx-path
                                                            :query [:and
                                                                    [:category "electronics"]
                                                                    [:- [:brand "Apple"]]]}}]}]})]
          @(pipeline {:index-path index-path :csv-path csv-path})
          (let [results (:search pipeline)]
            (is (= 1 (ds/row-count results)))
            (is (= "Samsung" (-> results ds/mapseq-reader first :brand)))))
        (finally
          (cleanup-dir index-path)
          (io/delete-file csv-path))))))


;;-----------------------------------------------------------------------------
;; Dataset/Collection Input Tests
;;-----------------------------------------------------------------------------

(deftest index-dataset-test
  (testing "index! with a single dataset"
    (let [index-path (create-temp-index)
          dataset    (ds/->dataset [{:name "Alice" :age 30}
                                    {:name "Bob" :age 25}
                                    {:name "Carol" :age 35}])]
      (try
        (let [result (sut/index! {:index-path index-path
                                  :input      dataset})]
          (is (= 3 (:indexed result)))
          (is (= 0 (:failed result)))
          (is (number? (:duration-ms result)))
          ;; Verify searchability
          (let [search-results (sut/search {:index-path index-path
                                            :query      "name:Alice"})]
            (is (= 1 (ds/row-count search-results)))))
        (finally
          (cleanup-dir index-path))))))


(deftest index-collection-test
  (testing "index! with a collection of maps"
    (let [index-path (create-temp-index)
          coll       [{:product "Laptop" :price 999}
                      {:product "Phone" :price 599}
                      {:product "Tablet" :price 399}]]
      (try
        (let [result (sut/index! {:index-path index-path
                                  :input      coll})]
          (is (= 3 (:indexed result)))
          (is (= 0 (:failed result)))
          ;; Verify searchability
          (let [search-results (sut/search {:index-path index-path
                                            :query      "product:Laptop"})]
            (is (= 1 (ds/row-count search-results)))))
        (finally
          (cleanup-dir index-path))))))


(deftest index-dataset-seq-test
  (testing "index! with a sequence of datasets"
    (let [index-path (create-temp-index)
          ds1        (ds/->dataset [{:city "London" :country "UK"}
                                    {:city "Paris" :country "France"}])
          ds2        (ds/->dataset [{:city "Berlin" :country "Germany"}
                                    {:city "Rome" :country "Italy"}])
          datasets   [ds1 ds2]]
      (try
        (let [result (sut/index! {:index-path index-path
                                  :input      datasets})]
          (is (= 4 (:indexed result)))
          (is (= 0 (:failed result)))
          ;; Verify all batches were indexed
          (let [all-results (sut/search {:index-path index-path
                                         :query      "*:*"
                                         :limit      10})]
            (is (= 4 (ds/row-count all-results)))))
        (finally
          (cleanup-dir index-path))))))


(deftest index-backward-compat-test
  (testing "index! backward compatibility with :docs-path"
    (let [index-path (create-temp-index)
          csv-path   (create-test-csv "id,name\n1,Alice\n2,Bob\n")]
      (try
        ;; Use :docs-path instead of :input (backward compatible)
        (let [result (sut/index! {:index-path index-path
                                  :docs-path  csv-path})]
          (is (= 2 (:indexed result)))
          (is (= 0 (:failed result))))
        (finally
          (cleanup-dir index-path)
          (io/delete-file csv-path))))))


(deftest index-memory-metadata-test
  (testing "in-memory data gets proper default metadata"
    (let [index-path (create-temp-index)
          dataset    (ds/->dataset [{:item "Test"}])]
      (try
        (sut/index! {:index-path index-path
                     :input      dataset})
        (let [results (sut/search {:index-path index-path
                                   :query      "item:Test"
                                   :fields     [:source-path :source-extension]})]
          (is (= 1 (ds/row-count results)))
          ;; Check that memory:// prefix is used for in-memory data
          (let [row (first (ds/mapseq-reader results))]
            (is (str/starts-with? (get row :source-path) "memory://"))
            (is (= "memory" (get row :source-extension)))))
        (finally
          (cleanup-dir index-path))))))


(deftest index-with-custom-source-id-test
  (testing "index! with custom :source-id option"
    (let [index-path (create-temp-index)
          dataset    (ds/->dataset [{:name "Test"}])]
      (try
        (sut/index! {:index-path index-path
                     :input      dataset
                     :source-id  "custom://my-data-source"})
        (let [results (sut/search {:index-path index-path
                                   :query      "name:Test"
                                   :fields     [:source-path]})]
          (is (= 1 (ds/row-count results)))
          (let [row (first (ds/mapseq-reader results))]
            (is (= "custom://my-data-source" (get row :source-path)))))
        (finally
          (cleanup-dir index-path))))))


(deftest index-action-with-dataset-test
  (testing "::index action with dataset input"
    (let [index-path (create-temp-index)
          dataset    (ds/->dataset [{:name "Alice"} {:name "Bob"}])]
      (try
        (let [action-fn (action/action-fn {:type :collet.actions.lucene/index})
              result    (action-fn {:index-path index-path
                                    :input      dataset})]
          (is (= 2 (:indexed result)))
          (is (= 0 (:failed result))))
        (finally
          (cleanup-dir index-path))))))


(deftest index-error-missing-input-test
  (testing "index! throws when neither :input nor :docs-path provided"
    (let [index-path (create-temp-index)]
      (try
        (is (thrown-with-msg?
             ExceptionInfo
             #"Missing required parameter"
             (sut/index! {:index-path index-path})))
        (finally
          (cleanup-dir index-path))))))


(deftest index-error-file-not-found-test
  (testing "index! throws when file path doesn't exist"
    (let [index-path (create-temp-index)]
      (try
        (is (thrown-with-msg?
             ExceptionInfo
             #"File or directory not found"
             (sut/index! {:index-path index-path
                          :input      "/nonexistent/path/to/data.csv"})))
        (finally
          (cleanup-dir index-path))))))


(deftest index-error-unsupported-type-test
  (testing "index! throws for unsupported input type"
    (let [index-path (create-temp-index)]
      (try
        (is (thrown-with-msg?
             ExceptionInfo
             #"Unsupported input type"
             (sut/index! {:index-path index-path
                          :input      12345})))
        (finally
          (cleanup-dir index-path))))))


(deftest index-empty-collection-test
  (testing "index! with empty collection is a no-op"
    (let [index-path (create-temp-index)]
      (try
        (let [result (sut/index! {:index-path index-path :input []})]
          (is (= 0 (:indexed result)))
          (is (= 0 (:failed result))))
        (finally
          (cleanup-dir index-path))))))
