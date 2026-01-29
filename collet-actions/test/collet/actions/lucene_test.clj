(ns collet.actions.lucene-test
  (:require
   [clojure.test :refer :all]
   [collet.actions.lucene :as sut]
   [collet.test-fixtures :as tf]
   [tech.v3.dataset :as ds])
  (:import [java.io File]))


(use-fixtures :once (tf/instrument! 'collet.actions.lucene))


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

      [:field [:- "data"]]
      "field:-\"data\""

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

