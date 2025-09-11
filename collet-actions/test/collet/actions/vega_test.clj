(ns collet.actions.vega-test
  (:require
   [charred.api :as charred]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [collet.actions.vega :as sut]
   [collet.test-fixtures :as tf]
   [tech.v3.dataset :as ds]))

(use-fixtures :once (tf/instrument! 'collet.actions.vega))

(def example-spec
  {:data
   {:values
    [{:a "A", :b 28}
     {:a "B", :b 55}
     {:a "C", :b 43}
     {:a "D", :b 91}]},
   :mark "bar",
   :encoding
   {:x {:field "a", :type "nominal", :axis {:labelAngle 0}},
    :y {:field "b", :type "quantitative"}}})


(def md5
  (let [hasher (java.security.MessageDigest/getInstance "MD5")]
    (fn [string]
      (javax.xml.bind.DatatypeConverter/printHexBinary
       (.digest hasher (.getBytes string "UTF-8"))))))

(defn file->md5 [path] (md5 (slurp path)))
(defn file->edn [path] (charred/read-json (slurp path) :key-fn keyword))
(defn file-exists? [path] (.exists (io/file path)))


(deftest write-vega-lite-into-svg-test
  (let [data-file-path "./tmp/vega-test.json"
        svg-file-path "./tmp/vega-test.svg"]

    (testing "render plain data"
      (sut/write-vega-lite-into-svg
       {:vega-lite-spec example-spec
        :svg-file-path svg-file-path})

      (is (file-exists? svg-file-path))
      (is (= (file->md5 svg-file-path) "BC7311FD90D4ECD20A4155C9CCB3D0B6")))

    (testing "render plain data and store it in JSON"
      (sut/write-vega-lite-into-svg
       {:vega-lite-spec example-spec
        :data-file-path data-file-path
        :data-format :json
        :svg-file-path svg-file-path})

      (is (file-exists? svg-file-path))
      (is (= (file->md5 svg-file-path) "BC7311FD90D4ECD20A4155C9CCB3D0B6"))

      (is (file-exists? data-file-path))
      (is (= [{:a "A", :b 28}
              {:a "B", :b 55}
              {:a "C", :b 43}
              {:a "D", :b 91}]
             (file->edn data-file-path))))

    (testing "render dataset"
      (sut/write-vega-lite-into-svg
       {:vega-lite-spec
        (-> example-spec
            (dissoc :data)
            (assoc-in [:data :values]
                      (seq [(ds/->dataset [{:a 1 :b 2} {:a 3 :b 4}])
                            (ds/->dataset [{:a 5 :b 6} {:a 7 :b 8}])])))
        :data-file-path data-file-path
        :data-format :json
        :svg-file-path svg-file-path})

      (is (file-exists? svg-file-path))
      (is (= (file->md5 svg-file-path) "F87E018AA5BE34BB3C3292F8C4B6BBD5"))

      (is (file-exists? data-file-path))
      (is (= [{:a 1 :b 2} {:a 3 :b 4} {:a 5 :b 6} {:a 7 :b 8}]
             (file->edn data-file-path))))

    (io/delete-file (io/file data-file-path))
    (io/delete-file (io/file svg-file-path)))


  (testing "render data from file"
    (let [svg-file-path "./tmp/vega-test.svg"
          spec (-> example-spec
                   (dissoc :data)
                   (assoc-in [:data :url] "./resources/vega-test.json"))]

      (sut/write-vega-lite-into-svg
       {:vega-lite-spec spec
        :svg-file-path svg-file-path})

      (is (file-exists? svg-file-path))
      (is (= (file->md5 svg-file-path) "F87E018AA5BE34BB3C3292F8C4B6BBD5"))

      (io/delete-file (io/file svg-file-path))))


  (testing "incorrect vega-lite spec with clear error message"
    (is (thrown-with-msg?
         Exception #"vega-lite spec is incorrect: Error: Invalid field type \"INCORRECT_TYPE\"."
         (sut/write-vega-lite-into-svg
          {:vega-lite-spec (assoc-in example-spec [:encoding :x :type] "INCORRECT_TYPE")
           :data-file-path "./tmp/vega-test.json"
           :data-format :json
           :svg-file-path "./tmp/vega-test.svg"}))))


  (testing "incorrect vega-lite spec with clear error message"
    (is (thrown-with-msg?
         Exception #"vega-lite spec is incorrect: TypeError: Cannot convert undefined or null to object"
         (sut/write-vega-lite-into-svg
          {:vega-lite-spec (assoc example-spec :mark "INCORRECT_PLOT_TYPE")
           :data-file-path "./tmp/vega-test.json"
           :data-format :json
           :svg-file-path "./tmp/vega-test.svg"})))))

(comment
  (md5 (slurp "./tmp/vega-test.svg"))

  ;;
  )